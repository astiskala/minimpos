package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.adyenField
import app.minimpos.terminal.transport.decodeAdyenModel
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.CardDetails
import com.adyen.model.checkout.CheckoutPaymentMethod
import com.adyen.model.checkout.ListStoredPaymentMethodsResponse
import com.adyen.model.checkout.PaymentRequest
import com.adyen.model.checkout.PaymentResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A card Adyen saved for a shopper (a token), described only by its summary: never the card number.
 *
 * @property id Adyen's `storedPaymentMethodId`, sent to charge the card.
 * @property brand The card brand as Adyen names it, such as `visa` or `mc`; null when Adyen sent none.
 * @property lastFour The last four digits of the card number; null when Adyen sent none.
 * @property expiryMonth The expiry month as Adyen sent it, such as `03`; null when unknown.
 * @property expiryYear The expiry year as Adyen sent it, such as `2030`; null when unknown.
 * @property holderName The cardholder's name; null when Adyen sent none.
 * @property recurringModels The `recurringProcessingModel` values the token may be charged with; empty when Adyen did
 *   not say, which does not rule any out.
 */
data class StoredCard(
    val id: String,
    val brand: String? = null,
    val lastFour: String? = null,
    val expiryMonth: String? = null,
    val expiryYear: String? = null,
    val holderName: String? = null,
    val recurringModels: Set<String> = emptySet(),
) {
    /** Whether the token may be charged with [model]; a token without listed models is not ruled out. */
    fun supports(model: String): Boolean = recurringModels.isEmpty() || model in recurringModels
}

/** The cards saved for a shopper, or why they could not be listed. */
sealed interface StoredCardListing {
    /**
     * Every card Adyen listed, in its order.
     *
     * @property cards The cards; empty when the shopper has none.
     */
    data class Listed(
        val cards: List<StoredCard>,
    ) : StoredCardListing

    /**
     * They could not be listed; listing changes nothing, so it can always be asked again.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : StoredCardListing
}

/** Result of deleting one merchant/shopper token; uncertain failures require a fresh listing, never an automatic resend. */
sealed interface StoredCardDeletion {
    /** HTTP 204: the token can no longer be used for payment requests. */
    data object Deleted : StoredCardDeletion

    /**
     * No conclusive deletion answer.
     * @property fault Why; [Fault.mayHaveTakenEffect] distinguishes refusal from uncertainty.
     */
    data class Failed(
        val fault: Fault,
    ) : StoredCardDeletion
}

/**
 * A charge of a saved card, built from the stored sale so a retry sends the same request.
 *
 * @property storedPaymentMethodId The card to charge ([StoredCard.id]).
 * @property shopperReference The shopper the card is saved under.
 * @property amount What to charge.
 * @property reference The merchant reference of the sale.
 * @property recurringProcessingModel Adyen's `recurringProcessingModel` the card was saved with, such as
 *   `UnscheduledCardOnFile`.
 * @property shopperEmail The shopper's email, for Adyen's records; null for none.
 */
data class CardChargeRequest(
    val storedPaymentMethodId: String,
    val shopperReference: String,
    val amount: ModificationAmount,
    val reference: String,
    val recurringProcessingModel: String,
    val shopperEmail: String? = null,
)

/** How Adyen answered a saved-card charge. Network and HTTP errors are reported here, never thrown. */
sealed interface CardChargeResult {
    /**
     * The card was charged (result code `Authorised`).
     *
     * @property pspReference Adyen's reference for the payment, which refunds it.
     * @property brand The card brand Adyen reported; null when it sent none.
     * @property lastFour The card's last four digits from `cardSummary`; null when Adyen sent none.
     * @property authCode The issuer's approval code; null when Adyen sent none.
     */
    data class Authorised(
        val pspReference: String,
        val brand: String? = null,
        val lastFour: String? = null,
        val authCode: String? = null,
    ) : CardChargeResult

    /**
     * Adyen or the issuer refused it (result codes `Refused`, `Cancelled` or `Error`); nothing was charged.
     *
     * @property pspReference Adyen's reference for the attempt; null when it sent none.
     * @property said Adyen's refusal reason; null when it gave none.
     */
    data class Refused(
        val pspReference: String? = null,
        val said: ExternalText? = null,
    ) : CardChargeResult

    /**
     * No usable answer. When [Fault.mayHaveTakenEffect] the card may have been charged, and sending the same request
     * again with the same idempotency key returns Adyen's first answer instead of charging twice.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : CardChargeResult
}

/**
 * Saved cards through Adyen's Checkout API: listing a shopper's cards and charging one with the shopper not present
 * (`shopperInteraction=ContAuth`). Implementations never throw for network or API errors. Safe from any thread.
 */
interface SavedCardApi {
    /** Lists the cards saved under [shopperReference] (`GET /storedPaymentMethods`). */
    suspend fun list(shopperReference: String): StoredCardListing

    /** Deletes only [id] for [shopperReference]; no body or idempotency key. Unsupported adapters refuse without sending. */
    suspend fun delete(
        shopperReference: String,
        id: String,
    ): StoredCardDeletion = StoredCardDeletion.Failed(Fault.Unsupported)

    /**
     * Charges [request] (`POST /payments`) with [idempotencyKey] as `Idempotency-Key`, so repeating a charge whose
     * outcome is unknown cannot charge twice.
     */
    suspend fun charge(
        request: CardChargeRequest,
        idempotencyKey: String,
    ): CardChargeResult
}

/**
 * [SavedCardApi] over HTTPS with the Adyen library's Checkout models; [AdyenHttp] owns timeouts, idempotency and
 * delivery uncertainty. The API key is only sent in its header.
 *
 * @param credentials The merchant account, environment and API key.
 * @param baseUrl Where the API lives; tests point it at a local server.
 * @param baseClient The client to derive from, so an app can share one connection pool.
 * @param timeout The limit for each whole request.
 * @param dispatcher Where the blocking calls run.
 * @param application Application information sent with every charge.
 */
class CheckoutSavedCards(
    private val credentials: CheckoutCredentials,
    private val baseUrl: HttpUrl = credentials.baseUrl.toHttpUrl(),
    baseClient: OkHttpClient = OkHttpClient(),
    private val timeout: Duration = 30.seconds,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val application: ApplicationInfo? = null,
) : SavedCardApi {
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher)

    override suspend fun list(shopperReference: String): StoredCardListing {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("storedPaymentMethods")
                .addQueryParameter("merchantAccount", credentials.merchantAccount)
                .addQueryParameter("shopperReference", shopperReference)
                .build()
        return when (val reply = http.get(url, timeout)) {
            is AdyenReply.Failed -> StoredCardListing.Failed(reply.fault)
            is AdyenReply.Answered if !reply.ok -> StoredCardListing.Failed(reply.checkoutFault())
            is AdyenReply.Answered -> listed(reply.body, shopperReference)
        }
    }

    override suspend fun delete(
        shopperReference: String,
        id: String,
    ): StoredCardDeletion {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("storedPaymentMethods")
                .addPathSegment(id)
                .addQueryParameter("merchantAccount", credentials.merchantAccount)
                .addQueryParameter("shopperReference", shopperReference)
                .build()
        return when (val reply = http.delete(url, timeout)) {
            is AdyenReply.Failed -> StoredCardDeletion.Failed(reply.fault)
            is AdyenReply.Answered if reply.code == HTTP_DELETED -> StoredCardDeletion.Deleted
            is AdyenReply.Answered if reply.ok -> StoredCardDeletion.Failed(Fault.UnreadableReply())
            is AdyenReply.Answered -> StoredCardDeletion.Failed(reply.checkoutFault(AdyenRole.TOKENISE.displayName))
        }
    }

    private fun listed(
        text: String,
        reference: String,
    ): StoredCardListing {
        val response =
            decodeAdyenModel(text, ListStoredPaymentMethodsResponse::class.java)
                ?: return StoredCardListing.Failed(Fault.UnreadableReply())
        if (response.merchantAccount != null && response.merchantAccount != credentials.merchantAccount) {
            return StoredCardListing.Failed(Fault.UnreadableReply())
        }
        if (response.shopperReference != null && response.shopperReference != reference) {
            return StoredCardListing.Failed(Fault.UnreadableReply())
        }
        val methods =
            response.storedPaymentMethods
                ?: return StoredCardListing.Failed(Fault.UnreadableReply())
        // Dropping malformed entries could falsely confirm that an uncertain deletion removed its token.
        if (methods.any { it?.id.isNullOrBlank() }) return StoredCardListing.Failed(Fault.UnreadableReply())
        val cards =
            methods.mapNotNull { method ->
                method?.id?.takeIf { it.isNotBlank() }?.let { id ->
                    StoredCard(
                        id = id,
                        brand = method.brand?.takeIf { it.isNotBlank() },
                        lastFour = method.lastFour?.takeIf { it.isNotBlank() },
                        expiryMonth = method.expiryMonth?.takeIf { it.isNotBlank() },
                        expiryYear = method.expiryYear?.takeIf { it.isNotBlank() },
                        holderName = method.holderName?.takeIf { it.isNotBlank() },
                        recurringModels =
                            method.supportedRecurringProcessingModels
                                .orEmpty()
                                .filterNotNull()
                                .toSet(),
                    )
                }
            }
        return StoredCardListing.Listed(cards)
    }

    override suspend fun charge(
        request: CardChargeRequest,
        idempotencyKey: String,
    ): CardChargeResult {
        val model =
            runCatching { PaymentRequest.RecurringProcessingModelEnum.fromValue(request.recurringProcessingModel) }.getOrNull()
                ?: return CardChargeResult.Failed(Fault.NotStarted)
        val body =
            PaymentRequest()
                .merchantAccount(credentials.merchantAccount)
                .amount(Amount().currency(request.amount.currency).value(request.amount.value))
                .reference(request.reference)
                .paymentMethod(CheckoutPaymentMethod(CardDetails().storedPaymentMethodId(request.storedPaymentMethodId)))
                .shopperReference(request.shopperReference)
                .shopperInteraction(PaymentRequest.ShopperInteractionEnum.CONTAUTH)
                .recurringProcessingModel(model)
                .applicationInfo(application?.checkoutInfo())
                .apply { request.shopperEmail?.let { shopperEmail(it) } }
                .toJson()
        val url = baseUrl.newBuilder().addPathSegment("payments").build()
        return when (val reply = http.post(url, body, timeout, idempotencyKey)) {
            is AdyenReply.Failed -> CardChargeResult.Failed(reply.fault)
            is AdyenReply.Answered if !reply.ok -> CardChargeResult.Failed(reply.checkoutFault())
            is AdyenReply.Answered -> charged(reply.body)
        }
    }

    private companion object {
        const val HTTP_DELETED = 204
    }

    private fun charged(text: String): CardChargeResult {
        val response =
            decodeAdyenModel(text, PaymentResponse::class.java)
                // A charge may have been made even when its answer cannot be read.
                ?: return CardChargeResult.Failed(Fault.UnreadableReply())
        val psp = response.pspReference?.takeIf { it.isNotBlank() }
        return when (response.resultCode) {
            PaymentResponse.ResultCodeEnum.AUTHORISED -> {
                psp?.let {
                    CardChargeResult.Authorised(
                        it,
                        response.paymentMethod?.brand?.takeIf { brand -> brand.isNotBlank() },
                        response.additionalData?.get("cardSummary")?.takeIf { summary -> summary.isNotBlank() },
                        response.additionalData?.get("authCode")?.takeIf { code -> code.isNotBlank() },
                    )
                } ?: CardChargeResult.Failed(Fault.UnreadableReply())
            }

            PaymentResponse.ResultCodeEnum.REFUSED, PaymentResponse.ResultCodeEnum.CANCELLED, PaymentResponse.ResultCodeEnum.ERROR -> {
                CardChargeResult.Refused(psp, ExternalText.of(response.refusalReason))
            }

            // Pending, received or an unexpected step: Adyen has not decided yet, so the outcome stays unknown.
            PaymentResponse.ResultCodeEnum.PENDING, PaymentResponse.ResultCodeEnum.RECEIVED -> {
                CardChargeResult.Failed(Fault.StillInProgress)
            }

            else -> {
                CardChargeResult.Failed(Fault.UnreadableReply(ExternalText.of(adyenField(text, PaymentResponse.JSON_PROPERTY_RESULT_CODE))))
            }
        }
    }
}

package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.Fault
import java.time.Instant

/**
 * One item of a payment link, shown on Adyen's payment page (and needed by buy now, pay later methods). Amounts are
 * for one unit, in minor units of the link's currency; the items' amounts times their quantities add up to the link's
 * amount.
 *
 * @property id The item's identifier within the link, such as its position.
 * @property description What was sold, as the shopper sees it.
 * @property quantity The number of units, at least one.
 * @property amountIncludingTax One unit's price with tax.
 * @property amountExcludingTax One unit's price without tax.
 * @property taxAmount One unit's tax.
 * @property taxPercentage The tax rate in basis points (2100 for 21%).
 */
data class PaymentLinkLineItem(
    val id: String,
    val description: String,
    val quantity: Int,
    val amountIncludingTax: Long,
    val amountExcludingTax: Long,
    val taxAmount: Long,
    val taxPercentage: Long,
)

/**
 * A payment link to create (`POST /paymentLinks`): a single-use link to Adyen's payment page for one amount.
 *
 * @property reference The merchant reference of the payment.
 * @property amount What the shopper pays.
 * @property expiresAt When the link stops working; Adyen allows at most 70 days from now.
 * @property lineItems What was sold; empty for none.
 * @property shopperEmail Filled in on the payment page; null for none.
 * @property shopperReference Adyen's `shopperReference`, which saved cards are filed under; null for none.
 * @property recurringProcessingModel Set to offer saving the card on the payment page (`storePaymentMethodMode`
 *   `askForConsent`) for this kind of later use, such as `CardOnFile`; null not to offer it. Needs a [shopperReference].
 * @property shopperLocale The language of the payment page as a BCP 47 tag, such as `en-AU`; null follows the shopper's
 *   browser.
 * @property countryCode The shopper's country (ISO 3166-1 alpha-2), which decides the payment methods offered; null for
 *   Adyen's default.
 * @property metadata Key-value pairs stored with the payment.
 */
data class PaymentLinkRequest(
    val reference: String,
    val amount: ModificationAmount,
    val expiresAt: Instant,
    val lineItems: List<PaymentLinkLineItem> = emptyList(),
    val shopperEmail: String? = null,
    val shopperReference: String? = null,
    val recurringProcessingModel: String? = null,
    val shopperLocale: String? = null,
    val countryCode: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** Where a payment link stands, as Adyen reports it. */
enum class PaymentLinkStatus {
    /** It can be paid: not paid yet and not expired. */
    ACTIVE,

    /** The shopper started a payment method that completes later (such as a bank transfer); it is not paid yet. */
    PAYMENT_PENDING,

    /** The shopper paid (Adyen's `completed`, or `paid`). */
    COMPLETED,

    /** It can no longer be paid: its time ran out, it was expired on purpose, or too many attempts failed. */
    EXPIRED,
}

/**
 * A payment link as Adyen describes it.
 *
 * @property id Adyen's ID of the link, such as `PL50C5F751CED39G71`.
 * @property url The link the shopper opens, such as `https://test.adyen.link/PL50C5F751CED39G71`.
 * @property status Where it stands.
 * @property expiresAt When it stops working; null when Adyen did not say.
 */
data class PaymentLink(
    val id: String,
    val url: String,
    val status: PaymentLinkStatus,
    val expiresAt: Instant?,
)

/** How Adyen answered a payment link request. Network and HTTP errors are reported here, never thrown. */
sealed interface PaymentLinkResult {
    /**
     * Adyen answered with the link.
     *
     * @property link The link as it stands now.
     */
    data class Answered(
        val link: PaymentLink,
    ) : PaymentLinkResult

    /**
     * Adyen gave no usable answer. When [Fault.mayHaveTakenEffect] is false the request did not take effect (it never
     * reached Adyen, or Adyen rejected it, such as a wrong API key, an API credential without the Pay by Link role, or
     * an expiry too far ahead); otherwise it is not known whether it did (timeout, broken connection, HTTP 5xx, an
     * unexpected answer), and a creation can be sent again with the same idempotency key, which returns the same link
     * instead of a second one.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : PaymentLinkResult
}

/**
 * Adyen's payment links (Pay by Link) through the Checkout API: creating one, asking where it stands, and expiring it.
 * Without a server for webhooks, asking ([status]) is the only way to learn that a link was paid; Adyen's answer has no
 * PSP reference of the payment. Implementations never throw for network or API errors, and are safe to call from any
 * thread.
 */
interface PaymentLinkApi {
    /**
     * Creates a link (`POST /paymentLinks`).
     *
     * @param request What the link is for.
     * @param idempotencyKey Sent as `Idempotency-Key`, so sending the request again after an unknown outcome returns the
     *   link made the first time.
     */
    suspend fun create(
        request: PaymentLinkRequest,
        idempotencyKey: String,
    ): PaymentLinkResult

    /** Where the link [linkId] stands now (`GET /paymentLinks/{linkId}`). */
    suspend fun status(linkId: String): PaymentLinkResult

    /** Expires the link [linkId] now, so it can no longer be paid (`PATCH /paymentLinks/{linkId}`, status `expired`). */
    suspend fun expire(linkId: String): PaymentLinkResult
}

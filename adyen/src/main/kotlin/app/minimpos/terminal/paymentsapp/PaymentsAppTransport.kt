package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.parse.FormEncoding
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.nexoGson
import app.minimpos.terminal.transport.responseText
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.google.gson.JsonObject
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.time.Duration

/** What came of opening one App Link in the Adyen Payments app, see [AppLinkExchange.exchange]. */
sealed interface AppLinkAnswer {
    /**
     * The app called back.
     *
     * @property url The URL it called back with, query included.
     */
    data class Returned(
        val url: String,
    ) : AppLinkAnswer

    /** No answer came back; [fault] says whether the app may have acted. */
    sealed class Unanswered(
        /** Why, as a fault of the request the link carried. */
        val fault: Fault,
    ) : AppLinkAnswer

    /** The app could not be started, so nothing was sent. */
    data object NotStarted : Unanswered(Fault.NotStarted)

    /** The app did not call back in time; whether it acted is unknown. */
    data object TimedOut : Unanswered(Fault.TimedOut)

    /** The operator came back from the app without an answer; whether it acted is unknown. */
    data object Abandoned : Unanswered(Fault.Abandoned)
}

/**
 * Opens App Links in the Adyen Payments app and waits for it to call back, which on Android means starting its
 * activity and receiving the return URL in the POS app's own. Only one exchange runs at a time.
 */
interface AppLinkExchange {
    /**
     * Opens [link] in the app [packageName] and suspends until it calls back, at most [timeout]. Never throws.
     *
     * @return The URL the app called back with, or why there is none.
     */
    suspend fun exchange(
        link: String,
        packageName: String,
        timeout: Duration,
    ): AppLinkAnswer

    /**
     * Unacknowledged return URLs, oldest first. This includes callbacks after restart and financial callbacks retained
     * while an exchange waits, because an original response can race a status callback. Only verified, durably stored
     * results may be forgotten.
     */
    fun lateReplies(): List<String>

    /** Removes exactly [urls] after their verified financial answers were durably stored; unresolved replies remain. */
    fun forgetLateReplies(urls: Set<String>)
}

/**
 * The App Links of the Adyen Payments app, which has one Play Store app per environment. Every link carries a
 * `returnUrl`, which the Payments app calls with its answer as query parameters. As in Adyen's examples, the return URL
 * is URL-encoded before it is added as a query parameter, and the boarding token and requests are Base64URL-encoded.
 *
 * @param environment Which Payments app the links open.
 */
class PaymentsAppLinks(
    private val environment: TerminalEnvironment,
) {
    /** The package name of the Payments app for [environment]. */
    val packageName: String get() = packageName(environment)

    private val base: String
        get() =
            when (environment) {
                TerminalEnvironment.TEST -> "https://www.adyen.com/test"
                TerminalEnvironment.LIVE -> "https://www.adyen.com"
            }

    /**
     * Asks whether the app is boarded (`/boarded`); with [reboard] it starts boarding again, for another merchant
     * account or store. The answer has `boarded`, `installationId` and, when not boarded, `boardingRequestToken`.
     */
    fun boardedCheck(
        returnUrl: String,
        reboard: Boolean = false,
    ): String = link("boarded", listOfNotNull("returnUrl" to encode(returnUrl), ("reboard" to "true").takeIf { reboard }))

    /** Boards the app with the one-time [boardingToken] from the Management API (`/board`). */
    fun board(
        boardingToken: String,
        returnUrl: String,
    ): String = link("board", listOf("boardingToken" to base64(boardingToken), "returnUrl" to encode(returnUrl)))

    /** Sends a Terminal API request, encrypted and Base64URL-encoded as [encryptedRequest] (`/nexo`). */
    fun nexo(
        encryptedRequest: String,
        returnUrl: String,
    ): String = link("nexo", listOf("request" to encryptedRequest, "returnUrl" to encode(returnUrl)))

    private fun link(
        path: String,
        parameters: List<Pair<String, String>>,
    ): String = "$base/$path?" + parameters.joinToString("&") { (name, value) -> "$name=${encode(value)}" }

    /** Package names and the parsing of the answers. */
    companion object {
        /** The package name of the Payments app for [environment]. */
        fun packageName(environment: TerminalEnvironment): String =
            when (environment) {
                TerminalEnvironment.TEST -> "com.adyen.ipp.mobile.companion.test"
                TerminalEnvironment.LIVE -> "com.adyen.ipp.mobile.companion.live"
            }

        /** The query parameters of a return URL the Payments app called, decoded; empty when it has no query. */
        fun answer(returnUrl: String): Map<String, String> = FormEncoding.decode(returnUrl.substringAfter('?', missingDelimiterValue = ""))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun base64(value: String): String = Base64.UrlSafe.encode(value.toByteArray())
}

/**
 * The Payments app's encryption: Terminal API messages encrypted with the shared key (Adyen crypto version 1, as for
 * the local Terminal API) and Base64URL-encoded for a link.
 */
internal class PaymentsAppCrypto(
    key: TerminalKey,
) {
    private val crypto = NexoCrypto(key.toSecurityKey())

    /** [request] encrypted and encoded for [PaymentsAppLinks.nexo]; null when it cannot be encrypted. */
    fun encrypt(request: TerminalAPIRequest): String? {
        // NexoCrypto declares that it throws any Exception.
        val message =
            runCatching { crypto.encrypt(nexoGson.toJson(request), request.saleToPOIRequest.messageHeader) }.getOrNull() ?: return null
        val json = nexoGson.toJson(TerminalAPISecuredRequest().apply { saleToPOIRequest = message })
        return Base64.UrlSafe.encode(json.toByteArray())
    }

    /** Decodes and decrypts a `response` parameter; null when it cannot be read or verified with the shared key. */
    fun decrypt(response: String): TerminalAPIResponse? =
        // Base64, JSON and NexoCrypto each fail with their own exceptions, and NexoCrypto declares any Exception.
        runCatching {
            val secured =
                nexoGson.fromJson(
                    String(Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(response)),
                    TerminalAPISecuredResponse::class.java,
                )
            nexoGson.fromJson(crypto.decrypt(checkNotNull(secured?.saleToPOIResponse)), TerminalAPIResponse::class.java)
        }.getOrNull()

    /**
     * Allowlisted diagnostics from a Base64URL JSON short response without a nexo envelope, or null when unreadable.
     * This is not an authenticated payment result and must never establish its outcome.
     */
    fun shortError(response: String): ExternalText? =
        runCatching {
            val json =
                requireNotNull(
                    nexoGson.fromJson(
                        String(Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(response)),
                        JsonObject::class.java,
                    ),
                )
            if (json.has("SaleToPOIResponse")) return@runCatching null

            fun text(key: String) = json[key]?.takeIf { it.isJsonPrimitive }?.asString
            responseText(listOf(text("error"), text("message")), text("errorCode"), text("traceParent"), text("requestId"))
        }.getOrNull()
}

/**
 * Terminal API requests through the Adyen Payments app (Tap to Pay on this phone): payments and referenced refunds
 * travel in App Links, encrypted with the shared key, and the Payments app answers through [returnUrl]
 * (`<returnUrl>/nexo`).
 *
 * Payments, reversals and transaction status travel through the encrypted `/nexo` App Link. Status first uses a
 * validated [AppLinkExchange.lateReplies] answer, then queries installations whose version may support it. Version
 * 1.8.0 introduced status; version alone does not establish beta enablement. NotFound remains inconclusive on this
 * destination, so recovery never authorizes another payment. Other requests (abort, print, diagnosis) fail with
 * [Fault.Unsupported]. A request that cannot be encrypted, a Payments app that could not be started and an answer with
 * only an `error` (the Payments app did not take the payment) took no effect. Not calling back in time, coming back
 * without a result, and an answer that cannot be verified or belongs to another request may have.
 *
 * @param key The shared key the Payments app's requests are encrypted with.
 * @param environment Which Payments app to use.
 * @param exchange Opens the links.
 * @param returnUrl Where the Payments app calls back, without a trailing slash, e.g. `minimpos://paymentsapp`.
 * @param version Reads the installed version of the selected environment's app; null when unreadable. Unknown versions may be
 *   queried, but versions known to predate status support use only late answers.
 */
class PaymentsAppTransport(
    key: TerminalKey,
    environment: TerminalEnvironment,
    private val exchange: AppLinkExchange,
    private val returnUrl: String,
    private val version: () -> String? = { null },
) : TerminalTransport {
    private val crypto = PaymentsAppCrypto(key)
    private val links = PaymentsAppLinks(environment)
    private val decoded = mutableMapOf<String, TerminalAPIResponse?>()
    private val recovered = mutableMapOf<String, MutableSet<String>>()

    override fun acknowledge(serviceId: String) {
        synchronized(decoded) {
            val urls = recovered.remove(serviceId).orEmpty().toSet()
            exchange.forgetLateReplies(urls)
            urls.forEach(decoded::remove)
        }
    }

    override suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery {
        val message = request.saleToPOIRequest
        val status = message?.transactionStatusRequest
        return when {
            message?.paymentRequest != null || message?.reversalRequest != null -> {
                transact(request, timeout)
            }

            status != null -> {
                repeat(request)?.let(Delivery::Answered) ?: run {
                    val answer = if (supportsStatus(version())) transact(request, timeout) else Delivery.Failed(Fault.NoLateReply)
                    // A late original answer may have arrived while the status exchange was waiting.
                    repeat(request)?.let(Delivery::Answered) ?: answer
                }
            }

            else -> {
                Delivery.Failed(Fault.Unsupported)
            }
        }
    }

    private suspend fun transact(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery {
        val encryptedRequest = crypto.encrypt(request) ?: return Delivery.Failed(Fault.RequestNotEncrypted)
        val link = links.nexo(encryptedRequest, "$returnUrl/$NEXO")
        val returned =
            when (val returned = exchange.exchange(link, links.packageName, timeout)) {
                is AppLinkAnswer.Returned -> returned
                is AppLinkAnswer.Unanswered -> return Delivery.Failed(returned.fault)
            }
        val answer = PaymentsAppLinks.answer(returned.url)
        val encrypted =
            answer["response"]?.takeIf { it.isNotEmpty() }
                ?: return Delivery.Failed(
                    Fault.AppRefused(
                        responseText(
                            messages = listOf(answer["error"], answer["message"]),
                            errorCode = answer["errorCode"],
                            traceParent = answer["traceParent"],
                            requestId = answer["requestId"],
                        ),
                    ),
                )
        val response =
            crypto.decrypt(encrypted)
                ?: return Delivery.Failed(crypto.shortError(encrypted)?.let(Fault::UnreadableReply) ?: Fault.ReplyUnverified)
        val sent = request.saleToPOIRequest
        val received = response.saleToPOIResponse
        validate(received, sent)?.let { return Delivery.Failed(it) }
        val serviceId = sent.transactionStatusRequest?.messageReference?.serviceID ?: sent.messageHeader.serviceID
        synchronized(decoded) {
            decoded[returned.url] = response
            recovered.getOrPut(serviceId) { mutableSetOf() }.add(returned.url)
        }
        return Delivery.Answered(response)
    }

    private fun validate(
        response: SaleToPOIResponse?,
        request: SaleToPOIRequest,
    ): Fault? {
        if (!matches(response?.messageHeader, request.messageHeader)) return Fault.UnreadableReply()
        val status = request.transactionStatusRequest ?: return null
        val result = response?.transactionStatusResponse ?: return Fault.UnreadableReply()
        return when {
            result.messageReference != null && !sameReference(result.messageReference, status.messageReference) -> Fault.UnreadableReply()
            result.response?.errorCondition == ErrorConditionType.NOT_FOUND -> Fault.NoLateReply
            result.response?.result == ResultType.SUCCESS && !repeats(result, status, request.messageHeader) -> Fault.UnreadableReply()
            else -> null
        }
    }

    /** The late answer to the transaction [request] asks about, as a status response that repeats it; null without one. */
    private fun repeat(request: TerminalAPIRequest): TerminalAPIResponse? {
        val sent = request.saleToPOIRequest
        return exchange.lateReplies().firstNotNullOfOrNull { url ->
            val received = decodeLate(url)?.saleToPOIResponse ?: return@firstNotNullOfOrNull null
            val repeated = repeated(received, sent) ?: return@firstNotNullOfOrNull null
            synchronized(
                decoded,
            ) { recovered.getOrPut(sent.transactionStatusRequest.messageReference.serviceID) { mutableSetOf() }.add(url) }
            statusAnswer(sent.messageHeader, repeated)
        }
    }

    private fun decodeLate(url: String): TerminalAPIResponse? =
        synchronized(decoded) {
            if (!decoded.containsKey(url)) decoded[url] = PaymentsAppLinks.answer(url)["response"]?.let(crypto::decrypt)
            decoded[url]
        }

    private fun repeated(
        received: SaleToPOIResponse,
        request: SaleToPOIRequest,
    ): TransactionStatusResponse? {
        val status = request.transactionStatusRequest
        val reference = status.messageReference ?: return null
        val original = originalHeader(reference, request.messageHeader)
        val repeated =
            if (matches(received.messageHeader, original)) {
                TransactionStatusResponse().apply {
                    response = Response().apply { result = ResultType.SUCCESS }
                    messageReference = reference
                    repeatedMessageResponse =
                        RepeatedMessageResponse().apply {
                            messageHeader = received.messageHeader
                            repeatedResponseMessageBody =
                                RepeatedResponseMessageBody().apply {
                                    paymentResponse = received.paymentResponse
                                    reversalResponse = received.reversalResponse
                                }
                        }
                }
            } else {
                received.transactionStatusResponse.takeIf { sameStatusContext(received.messageHeader, request.messageHeader) }
            }
        return repeated?.takeIf { repeats(it, status, request.messageHeader) }
    }

    private fun sameStatusContext(
        answer: MessageHeader?,
        request: MessageHeader,
    ): Boolean =
        answer != null && answer.saleID == request.saleID && answer.poiid == request.poiid &&
            answer.messageCategory == MessageCategoryType.TRANSACTION_STATUS && answer.messageClass == request.messageClass &&
            answer.messageType == MessageType.RESPONSE

    private fun originalHeader(
        reference: MessageReference,
        header: MessageHeader,
    ) = MessageHeader().apply {
        saleID = reference.saleID
        serviceID = reference.serviceID
        poiid = header.poiid
        messageCategory = reference.messageCategory
        messageClass = header.messageClass
    }

    private fun statusAnswer(
        header: MessageHeader,
        status: TransactionStatusResponse,
    ) = TerminalAPIResponse().apply {
        saleToPOIResponse =
            SaleToPOIResponse().apply {
                messageHeader =
                    MessageHeader().apply {
                        saleID = header.saleID
                        serviceID = header.serviceID
                        poiid = header.poiid
                        messageCategory = header.messageCategory
                        messageClass = header.messageClass
                        messageType = MessageType.RESPONSE
                    }
                transactionStatusResponse = status
            }
    }

    private fun repeats(
        response: TransactionStatusResponse,
        request: TransactionStatusRequest,
        header: MessageHeader,
    ): Boolean {
        if (response.response?.result != ResultType.SUCCESS ||
            !sameReference(response.messageReference, request.messageReference)
        ) {
            return false
        }
        val repeated = response.repeatedMessageResponse ?: return false
        val original = originalHeader(request.messageReference, header)
        return (repeated.messageHeader == null || matches(repeated.messageHeader, original)) &&
            bodyMatches(repeated.repeatedResponseMessageBody, request.messageReference.messageCategory)
    }

    private fun bodyMatches(
        body: RepeatedResponseMessageBody?,
        category: MessageCategoryType?,
    ): Boolean =
        body != null && (
            (category == MessageCategoryType.PAYMENT && body.paymentResponse != null && body.reversalResponse == null) ||
                (category == MessageCategoryType.REVERSAL && body.reversalResponse != null && body.paymentResponse == null)
        )

    private fun sameReference(
        answer: MessageReference?,
        request: MessageReference?,
    ): Boolean =
        answer != null && request != null && answer.saleID == request.saleID && answer.serviceID == request.serviceID &&
            answer.messageCategory == request.messageCategory

    private fun matches(
        answer: MessageHeader?,
        request: MessageHeader,
    ): Boolean =
        answer != null && answer.serviceID == request.serviceID && answer.saleID == request.saleID &&
            answer.poiid == request.poiid && answer.messageCategory == request.messageCategory &&
            answer.messageClass == request.messageClass && answer.messageType == MessageType.RESPONSE

    /** Version eligibility does not establish account or beta enablement. */
    companion object {
        private const val NEXO = "nexo"
        private const val STATUS_MINOR_VERSION = 8

        /** Whether [version] may support status; unreadable versions remain unverified and can be queried safely. */
        fun supportsStatus(version: String?): Boolean {
            val parts = version?.let { Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(it) }?.groupValues ?: return true
            val major = parts[1].toIntOrNull() ?: return true
            val minor = parts[2].toIntOrNull() ?: return true
            return major > 1 || (major == 1 && minor >= STATUS_MINOR_VERSION)
        }
    }
}

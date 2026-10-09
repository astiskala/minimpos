package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.parse.FormEncoding
import app.minimpos.terminal.transport.CompletedTransactions
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import java.net.URLEncoder
import java.util.Base64
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
    sealed interface Unanswered : AppLinkAnswer {
        /** Why, as a fault of the request the link carried. */
        val fault: Fault
    }

    /** The app could not be started, so nothing was sent. */
    data object NotStarted : Unanswered {
        override val fault: Fault get() = Fault.NotStarted
    }

    /** The app did not call back in time; whether it acted is unknown. */
    data object TimedOut : Unanswered {
        override val fault: Fault get() = Fault.TimedOut
    }

    /** The operator came back from the app without an answer; whether it acted is unknown. */
    data object Abandoned : Unanswered {
        override val fault: Fault get() = Fault.Abandoned
    }
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
     * The return URLs that arrived while no exchange was waiting, oldest first: the app was restarted while the
     * Payments app was in front, so its answer had nobody to go to.
     */
    fun lateReplies(): List<String>
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

    private fun base64(value: String): String = Base64.getUrlEncoder().encodeToString(value.toByteArray())
}

/**
 * The Payments app's encryption: Terminal API messages encrypted with the shared key (Adyen crypto version 1, as for
 * the local Terminal API) and Base64URL-encoded for a link.
 */
internal class PaymentsAppCrypto(
    key: TerminalKey,
) {
    private val crypto = NexoCrypto(key.toSecurityKey())
    private val gson = TerminalAPIGsonBuilder.create()

    /** [request] encrypted and encoded for [PaymentsAppLinks.nexo]; null when it cannot be encrypted. */
    fun encrypt(request: TerminalAPIRequest): String? {
        // NexoCrypto declares that it throws any Exception.
        val message =
            runCatching { crypto.encrypt(gson.toJson(request), request.saleToPOIRequest.messageHeader) }.getOrNull() ?: return null
        val json = gson.toJson(TerminalAPISecuredRequest().apply { saleToPOIRequest = message })
        return Base64.getUrlEncoder().encodeToString(json.toByteArray())
    }

    /** Decodes and decrypts a `response` parameter; null when it cannot be read or verified with the shared key. */
    fun decrypt(response: String): TerminalAPIResponse? =
        // Base64, JSON and NexoCrypto each fail with their own exceptions, and NexoCrypto declares any Exception.
        runCatching {
            val secured = gson.fromJson(String(Base64.getUrlDecoder().decode(response)), TerminalAPISecuredResponse::class.java)
            gson.fromJson(crypto.decrypt(checkNotNull(secured?.saleToPOIResponse)), TerminalAPIResponse::class.java)
        }.getOrNull()
}

/**
 * Terminal API requests through the Adyen Payments app (Tap to Pay on this phone): payments and referenced refunds
 * travel in App Links, encrypted with the shared key, and the Payments app answers through [returnUrl]
 * (`<returnUrl>/nexo`).
 *
 * The Payments app takes only payments and reversals. A transaction status request is answered from
 * [AppLinkExchange.lateReplies] (an answer that arrived after the app was restarted), as a terminal would repeat it;
 * without one it fails with [Fault.NoLateReply], and every other request (abort, print, diagnosis) with
 * [Fault.Unsupported]. A request that cannot be encrypted, a Payments app that could not be started and an answer with
 * only an `error` (the Payments app did not take the payment) took no effect. Not calling back in time, coming back
 * without a result, and an answer that cannot be verified or belongs to another request may have.
 *
 * @param key The shared key the Payments app's requests are encrypted with.
 * @param environment Which Payments app to use.
 * @param exchange Opens the links.
 * @param returnUrl Where the Payments app calls back, without a trailing slash, e.g. `minimpos://paymentsapp`.
 */
class PaymentsAppTransport(
    key: TerminalKey,
    environment: TerminalEnvironment,
    private val exchange: AppLinkExchange,
    private val returnUrl: String,
) : TerminalTransport {
    private val crypto = PaymentsAppCrypto(key)
    private val links = PaymentsAppLinks(environment)

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
                repeat(status)?.let(Delivery::Answered) ?: Delivery.Failed(Fault.NoLateReply)
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
        val answer =
            when (val returned = exchange.exchange(link, links.packageName, timeout)) {
                is AppLinkAnswer.Returned -> PaymentsAppLinks.answer(returned.url)
                is AppLinkAnswer.Unanswered -> return Delivery.Failed(returned.fault)
            }
        val encrypted =
            answer["response"]?.takeIf { it.isNotEmpty() }
                ?: return Delivery.Failed(Fault.AppRefused(ExternalText.of(answer["error"])))
        val response = crypto.decrypt(encrypted) ?: return Delivery.Failed(Fault.ReplyUnverified)
        if (response.saleToPOIResponse?.messageHeader?.serviceID != request.saleToPOIRequest.messageHeader.serviceID) {
            return Delivery.Failed(Fault.UnreadableReply())
        }
        return Delivery.Answered(response)
    }

    /** The late answer to the transaction [request] asks about, as a status response that repeats it; null without one. */
    private fun repeat(request: TransactionStatusRequest): TerminalAPIResponse? {
        val late = CompletedTransactions()
        exchange
            .lateReplies()
            .mapNotNull { url -> PaymentsAppLinks.answer(url)["response"]?.let(crypto::decrypt) }
            .mapNotNull { it.saleToPOIResponse }
            .forEach { late.remember(it.messageHeader?.serviceID.orEmpty(), it.paymentResponse, it.reversalResponse) }
        val repeated = late.repeat(request) ?: return null
        return TerminalAPIResponse().apply {
            saleToPOIResponse =
                SaleToPOIResponse().apply {
                    messageHeader = MessageHeader()
                    transactionStatusResponse = repeated
                }
        }
    }

    private companion object {
        /** The path of the return URL the Payments app answers transactions on. */
        const val NEXO = "nexo"
    }
}

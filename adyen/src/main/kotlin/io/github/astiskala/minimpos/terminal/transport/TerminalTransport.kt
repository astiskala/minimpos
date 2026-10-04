package io.github.astiskala.minimpos.terminal.transport

import com.adyen.enums.Environment
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import java.io.IOException
import kotlin.time.Duration

/**
 * The Adyen environment a terminal belongs to. It is never configured: [TerminalTls] reads it from the terminal's
 * certificate, which chains to a different Adyen root per environment.
 */
enum class TerminalEnvironment(
    /** The Adyen library's name for the environment, used to check the certificate's terminal name. */
    val adyen: Environment,
    /** The classpath resource holding the environment's terminal fleet root certificate (PEM). */
    internal val certificateResource: String,
) {
    /** Test terminals (`*.test.terminal.adyen.com`); payments are not real. */
    TEST(Environment.TEST, "/adyen-terminalfleet-test.pem"),

    /** Live terminals (`*.live.terminal.adyen.com`), which take real payments. */
    LIVE(Environment.LIVE, "/adyen-terminalfleet-live.pem"),
}

/**
 * What became of one message sent with [TerminalTransport.send]: the terminal's reply, or how sure it is that the
 * request took no effect. This is the one statement of that certainty, which decides whether a payment is reported as
 * not processed or has its status checked; every transport works it out once, and no caller sees an exception.
 */
sealed interface Delivery {
    /**
     * The terminal answered.
     *
     * @property response Its reply; null when it answered with an empty body (as it does for an abort).
     */
    data class Answered(
        val response: TerminalAPIResponse?,
    ) : Delivery

    /** No usable answer came back; [NotSent] and [MaybeSent] tell whether the request can have taken effect. */
    sealed interface Failed : Delivery {
        /** Why, in English. */
        val reason: String
    }

    /**
     * The request took no effect: it never reached the terminal (no connection, an untrusted certificate, an unknown or
     * offline terminal) or the terminal refused it (such as a wrong shared key).
     */
    data class NotSent(
        override val reason: String,
    ) : Failed

    /**
     * The request may have reached the terminal, but no usable answer came back (a timeout, a dropped connection, an
     * HTTP error, or a reply that could not be read or verified), so whether it took effect is unknown.
     */
    data class MaybeSent(
        override val reason: String,
    ) : Failed
}

/** Sends one Terminal API message and returns what became of it. */
fun interface TerminalTransport {
    /**
     * Sends [request] and suspends until the reply arrives. The terminal holds a payment request open while the shopper
     * pays, so [timeout] limits the whole exchange, not just the connection. Failures are returned, never thrown.
     *
     * @param request The plain (unencrypted) Terminal API message; encryption is the transport's job.
     * @param timeout How long to wait for the reply.
     * @return The reply, or whether the request can have taken effect without one.
     */
    suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery
}

/**
 * The delivery this I/O failure means, read from its type: [TerminalUnreachableException] and
 * [TerminalRejectedException] are [Delivery.NotSent]; anything else, such as a [TerminalProtocolException] or a timeout,
 * is [Delivery.MaybeSent]. [fallback] is the reason when the failure has no message.
 */
internal fun IOException.toDelivery(fallback: String): Delivery =
    when (this) {
        is TerminalUnreachableException, is TerminalRejectedException -> Delivery.NotSent(message ?: fallback)
        else -> Delivery.MaybeSent(message ?: fallback)
    }

/**
 * The request was never delivered (e.g. connection refused), so it is safe to treat as not processed. This and the
 * exceptions below are how the parts of a transport that must throw (the Adyen library's HTTP client, App Link
 * exchanges) report failures; the transport turns them into a [Delivery].
 */
open class TerminalUnreachableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * TLS setup failed before anything was sent: the peer did not present a certificate from Adyen's terminal fleet whose
 * name matches the environment of the root it chains to (see [TerminalTls]).
 */
class TerminalUntrustedException(
    message: String,
    cause: Throwable? = null,
) : TerminalUnreachableException(message, cause)

/**
 * The terminal replied, but with something other than a valid response (e.g. an HTTP error, or a message that could
 * not be decrypted or verified with the shared key). The request may or may not have been processed.
 */
open class TerminalProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The terminal explicitly rejected the request (e.g. wrong shared key), so it was not processed. */
class TerminalRejectedException(
    message: String,
) : TerminalProtocolException(message)

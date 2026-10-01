package io.minimpos.terminal.transport

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
    TEST(Environment.TEST, "/io/minimpos/terminal/adyen-terminalfleet-test.pem"),

    /** Live terminals (`*.live.terminal.adyen.com`), which take real payments. */
    LIVE(Environment.LIVE, "/io/minimpos/terminal/adyen-terminalfleet-live.pem"),
}

/** Sends one Terminal API message and returns the terminal's reply (null when the terminal answers with no body). */
fun interface TerminalTransport {
    /**
     * Sends [request] and suspends until the reply arrives. The terminal holds a payment request open while the shopper
     * pays, so [timeout] limits the whole exchange, not just the connection.
     *
     * Implementations report failures as [IOException] subclasses, which tell the caller whether the request can have
     * taken effect: [TerminalUnreachableException] (never delivered), [TerminalRejectedException] (delivered but
     * refused), [TerminalProtocolException] (answered with something unusable), or a plain [IOException] such as a
     * timeout, after which the outcome is unknown.
     *
     * @param request The plain (unencrypted) Terminal API message; encryption is the transport's job.
     * @param timeout How long to wait for the reply.
     * @return The terminal's reply, or null when it answered with an empty body (as it does for an abort).
     * @throws IOException As described above.
     */
    suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): TerminalAPIResponse?
}

/** The request was never delivered (e.g. connection refused), so it is safe to treat as not processed. */
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

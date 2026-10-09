package app.minimpos.terminal.transport

import com.adyen.enums.Environment
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import java.io.IOException
import java.io.InterruptedIOException
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
 * What became of one message sent with [TerminalTransport.send]: the terminal's reply, or the [Fault] that left it
 * without one. The fault states whether the request may have taken effect, which decides whether a payment is reported
 * as not processed or has its status checked; every transport works it out once, and no caller sees an exception.
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

    /**
     * No usable answer came back.
     *
     * @property fault Why, including whether the request may have taken effect ([Fault.mayHaveTakenEffect]).
     */
    data class Failed(
        val fault: Fault,
    ) : Delivery
}

/** Sends one Terminal API message and returns what became of it. */
fun interface TerminalTransport {
    /**
     * Sends [request] and suspends until the reply arrives. The terminal holds a payment request open while the shopper
     * pays, so [timeout] limits the whole exchange, not just the connection. Failures are returned, never thrown.
     *
     * @param request The plain (unencrypted) Terminal API message; encryption is the transport's job.
     * @param timeout How long to wait for the reply.
     * @return The reply, or the fault that left it without one.
     */
    suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery
}

/**
 * The fault this I/O failure means: a [FaultException]'s own fault, a timeout ([InterruptedIOException], which OkHttp's
 * call timeout and socket timeouts are) as [Fault.TimedOut], and anything else as [Fault.ConnectionLost], which may
 * have taken effect.
 */
internal fun IOException.fault(): Fault =
    when (this) {
        is FaultException -> fault
        is InterruptedIOException -> Fault.TimedOut
        else -> Fault.ConnectionLost
    }

/**
 * Carries a [Fault] out of the parts of a transport that must throw (the Adyen library's HTTP client), so the transport
 * can return it as [Delivery.Failed]. It has no message: nothing reads exception text.
 *
 * @property fault What went wrong.
 */
internal class FaultException(
    val fault: Fault,
) : IOException()

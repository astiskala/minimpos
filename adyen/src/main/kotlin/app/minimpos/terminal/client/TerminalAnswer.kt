package app.minimpos.terminal.client

import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.SaleToPOIResponse

/** What reading the answer of a simple one-request nexo exchange found, see [readTerminalAnswer]. */
internal sealed interface TerminalAnswer<out R> {
    /**
     * The terminal answered with the expected response shape.
     *
     * @param R The response body's type.
     * @property body The response body it sent, e.g. a `Response` or `TransactionStatusResponse`.
     */
    data class Read<out R>(
        val body: R,
    ) : TerminalAnswer<R>

    /**
     * Nothing usable came back.
     *
     * @property fault Why: the transport failed, or the answer was empty or not the response shape this request
     *   expects ([Fault.UnreadableReply]).
     */
    data class Unread(
        val fault: Fault,
    ) : TerminalAnswer<Nothing>
}

/**
 * Reads [delivery] of a simple request as the response body [select] names, the way every such operation does: a
 * transport failure is its [Fault], an empty envelope or unexpected response shape is [Fault.UnreadableReply], and
 * only a body of the expected shape is [TerminalAnswer.Read]. What the body says (its `Result`, `ErrorCondition` and
 * terminal text) stays with each operation.
 */
internal fun <R : Any> readTerminalAnswer(
    delivery: Delivery,
    select: (SaleToPOIResponse) -> R?,
): TerminalAnswer<R> =
    when (delivery) {
        is Delivery.Failed -> {
            TerminalAnswer.Unread(delivery.fault)
        }

        is Delivery.Answered -> {
            delivery.response
                ?.saleToPOIResponse
                ?.let(select)
                ?.let { TerminalAnswer.Read(it) }
                ?: TerminalAnswer.Unread(Fault.UnreadableReply())
        }
    }

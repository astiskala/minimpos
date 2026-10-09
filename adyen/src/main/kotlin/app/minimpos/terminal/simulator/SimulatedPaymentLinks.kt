package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.PaymentLink
import app.minimpos.terminal.checkout.PaymentLinkApi
import app.minimpos.terminal.checkout.PaymentLinkRequest
import app.minimpos.terminal.checkout.PaymentLinkResult
import app.minimpos.terminal.checkout.PaymentLinkStatus
import app.minimpos.terminal.transport.Fault
import java.time.Clock
import java.time.Instant

/**
 * Offline payment-link answers. IDs contain the frozen expiry and operation identity, so recovery after a restart
 * needs no in-memory ledger. Completion and cancellation are retained by the caller's stored sale. The reserved
 * `.invalid` address is a demo QR payload, never a payable page. Calls are thread-safe and perform no network I/O.
 *
 * @param clock Determines whether a link has expired.
 */
class SimulatedPaymentLinks(
    private val clock: Clock = Clock.systemUTC(),
) : PaymentLinkApi {
    override suspend fun create(
        request: PaymentLinkRequest,
        idempotencyKey: String,
    ): PaymentLinkResult = status("$PREFIX${request.expiresAt.toEpochMilli()}:$idempotencyKey")

    override suspend fun status(linkId: String): PaymentLinkResult = answer(linkId, expire = false)

    override suspend fun expire(linkId: String): PaymentLinkResult = answer(linkId, expire = true)

    private fun answer(
        linkId: String,
        expire: Boolean,
    ): PaymentLinkResult {
        val expiry =
            linkId
                .takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.substringBefore(':')
                ?.toLongOrNull()
                ?: return PaymentLinkResult.Failed(Fault.NotFound(null))
        val status = if (expire || clock.millis() >= expiry) PaymentLinkStatus.EXPIRED else PaymentLinkStatus.ACTIVE
        return PaymentLinkResult.Answered(
            PaymentLink(linkId, "https://example.invalid/minimpos-demo/$linkId", status, Instant.ofEpochMilli(expiry)),
        )
    }

    private companion object {
        const val PREFIX = "simulated:"
    }
}

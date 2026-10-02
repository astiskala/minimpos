package io.minimpos.app.payment

import android.database.SQLException
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.ApiTarget
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.terminal.checkout.PaymentLink
import io.minimpos.terminal.checkout.PaymentLinkApi
import io.minimpos.terminal.checkout.PaymentLinkResult
import io.minimpos.terminal.checkout.PaymentLinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.Locale
import java.util.UUID

/** What asking Adyen about a payment link (or cancelling it) found, see [PaymentLinks]. */
sealed interface LinkUpdate {
    /**
     * The sale is settled: paid, expired or cancelled, or (after an unknown outcome) its link was created or refused;
     * or there was nothing left to ask.
     */
    data object Settled : LinkUpdate

    /** The link has still not been paid. */
    data object StillOpen : LinkUpdate

    /**
     * Adyen could not be asked, or did not answer, so nothing changed.
     *
     * @property message Why, as Adyen or the app worded it.
     */
    data class Failed(
        val message: String,
    ) : LinkUpdate
}

/**
 * Sales paid through Adyen payment links instead of the terminal: creating the link ([start]), asking Adyen whether it
 * was paid ([check]) and expiring it ([cancel]). This app has no server for Adyen's webhooks, so asking is the only way
 * to learn the outcome, and Adyen's answer has no PSP reference: a paid link is refunded in the Customer Area.
 *
 * Each sale is stored as PENDING before Adyen is called and always ends in a final status (an unknown outcome is
 * [SaleStatus.UNKNOWN], which [check] resolves by creating the link again with the same idempotency key, so Adyen
 * returns the link made the first time). The request is made from the stored sale ([PaymentLinkRequests]), so the
 * second one is the same as the first. Checks and cancellations of all links run one at a time, so an answer that
 * arrives late never overwrites a newer one.
 *
 * @param scope Where links are created, so a creation survives leaving the screen.
 * @param sales Where the sales are stored.
 * @param settings The payment settings, for how a saved card will be used.
 * @param target Where payment links go now: [AdyenApi.target] in the app, a fixed target in tests.
 * @param unknownOutcome Stored as the message of a sale whose link may or may not have been created.
 * @param describe Words what is missing when the API is not set up, for the message stored with the sale.
 * @param onCreated Called with the sale ID and the request once its link was created by [start] (not by a [check]), to
 *   clear what the sale was rung up from and deliver it automatically.
 * @param clock Stamps the sales.
 * @param newId Generates sale IDs.
 * @param locale The device's locale, for the payment page's language and country.
 */
class PaymentLinks(
    private val scope: CoroutineScope,
    private val sales: SaleRepository,
    private val settings: SettingsRepository,
    private val target: suspend () -> ApiTarget,
    private val unknownOutcome: String,
    private val describe: (SetupProblem) -> String = { it.name },
    private val onCreated: (id: String, start: PaymentLinkStart) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val mutex = Mutex()

    /**
     * Creates the payment link for [link] in the background and returns the new sale's ID straight away; the stored
     * sale follows: PENDING, then [SaleStatus.AWAITING_PAYMENT] once Adyen made the link, else failed or unknown.
     */
    fun start(link: PaymentLinkStart): String {
        val id = newId()
        scope.launch {
            try {
                sales.createPending(link.pendingSale(id, clock.millis()), SaleBook.lines(id, link.payment.totals))
                val created = mutex.withLock { sales.get(id)?.let { send(it) } }
                if (created == LinkUpdate.StillOpen) onCreated(id, link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                interrupted(id, e)
            } catch (e: IllegalStateException) {
                interrupted(id, e)
            }
        }
        return id
    }

    /** Stores sale [id] as unknown after [error] stopped its creation, so it does not look as if it were still running. */
    private suspend fun interrupted(
        id: String,
        error: Exception,
    ) {
        try {
            sales.settle(id, SaleStatus.UNKNOWN, error.message ?: unknownOutcome, null)
        } catch (ignored: SQLException) {
            // It stays PENDING, which the next start turns into UNKNOWN.
        }
    }

    /**
     * Asks Adyen where the link of sale [saleId] stands and stores it: paid, expired or still open. A sale whose link
     * may not have been created ([SaleStatus.UNKNOWN]) has it created again (same idempotency key). Anything else is
     * already [LinkUpdate.Settled].
     */
    suspend fun check(saleId: String): LinkUpdate =
        mutex.withLock {
            val record = sales.get(saleId)?.takeIf { it.sale.paymentLink } ?: return@withLock LinkUpdate.Settled
            val sale = record.sale
            val linkId = sale.paymentLinkId
            when {
                sale.status != SaleStatus.AWAITING_PAYMENT && sale.status != SaleStatus.UNKNOWN -> LinkUpdate.Settled
                linkId == null -> send(record)
                else -> withApi { stored(saleId, it.status(linkId)) }
            }
        }

    /**
     * Expires the link of sale [saleId], so it can no longer be paid, and stores the sale as cancelled (or as paid, when
     * the shopper paid just before). A sale not awaiting its payment is already [LinkUpdate.Settled].
     */
    suspend fun cancel(saleId: String): LinkUpdate =
        mutex.withLock {
            val sale = sales.get(saleId)?.sale?.takeIf { it.status == SaleStatus.AWAITING_PAYMENT }
            val linkId = sale?.paymentLinkId ?: return@withLock LinkUpdate.Settled
            withApi { stored(saleId, it.expire(linkId), expired = SaleStatus.CANCELLED) }
        }

    /** Creates the link of [record] (again, after an unknown outcome) and stores how that went. */
    private suspend fun send(record: SaleWithLines): LinkUpdate {
        val id = record.sale.id
        val api = target().let { it.links ?: return settleFailed(id, describe(it.setup.problem ?: SetupProblem.API_REQUIRED)) }
        val request = PaymentLinkRequests.request(record, settings.current().payment.recurringProcessingModel, locale())
        return when (val result = api.create(request, idempotencyKey(id))) {
            is PaymentLinkResult.Answered -> {
                applied(id, result.link, SaleStatus.EXPIRED)
            }

            is PaymentLinkResult.NotProcessed -> {
                settleFailed(id, result.message)
            }

            is PaymentLinkResult.Unknown -> {
                sales.settle(id, SaleStatus.UNKNOWN, unknownOutcome, null)
                LinkUpdate.Failed(result.message)
            }
        }
    }

    /** Runs [call] with the payment link API, or fails (changing nothing) when it is not set up now. */
    private suspend fun withApi(call: suspend (PaymentLinkApi) -> LinkUpdate): LinkUpdate {
        val target = target()
        val api = target.links ?: return LinkUpdate.Failed(describe(target.setup.problem ?: SetupProblem.API_REQUIRED))
        return call(api)
    }

    /** Stores Adyen's answer [result] about the link of sale [id]; a link that ended counts as [expired]. */
    private suspend fun stored(
        id: String,
        result: PaymentLinkResult,
        expired: SaleStatus = SaleStatus.EXPIRED,
    ): LinkUpdate =
        when (result) {
            is PaymentLinkResult.Answered -> applied(id, result.link, expired)
            is PaymentLinkResult.NotProcessed -> LinkUpdate.Failed(result.message)
            is PaymentLinkResult.Unknown -> LinkUpdate.Failed(result.message)
        }

    /** Stores where [link], the link of sale [id], stands: still open, paid, or ended (as [expired]). */
    private suspend fun applied(
        id: String,
        link: PaymentLink,
        expired: SaleStatus,
    ): LinkUpdate {
        val status =
            when (link.status) {
                PaymentLinkStatus.ACTIVE, PaymentLinkStatus.PAYMENT_PENDING -> SaleStatus.AWAITING_PAYMENT
                PaymentLinkStatus.COMPLETED -> SaleStatus.APPROVED
                PaymentLinkStatus.EXPIRED -> expired
            }
        sales.settle(id, status, null, null, link)
        return if (status == SaleStatus.AWAITING_PAYMENT) LinkUpdate.StillOpen else LinkUpdate.Settled
    }

    private suspend fun settleFailed(
        id: String,
        message: String,
    ): LinkUpdate {
        sales.settle(id, SaleStatus.FAILED, message, null)
        return LinkUpdate.Settled
    }

    /** Payment link values. */
    companion object {
        /** The idempotency key of the link of sale [saleId]: one link per sale, however often it is sent. */
        fun idempotencyKey(saleId: String): String = "link-$saleId"
    }
}

package io.minimpos.app.payment

import android.database.SQLException
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.repo.SaleEvent
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.ApiTarget
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

    /**
     * Adyen could not be asked, because the Checkout API is not set up now, so nothing changed.
     *
     * @property problem What is missing.
     */
    data class NotSetUp(
        val problem: SetupProblem,
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
 * @param onCreated Called once with the sale ID and its original request when the link is created or recovered by
 *   [check] in this process, to reconcile its originating session and arm automatic delivery.
 * @param clock Stamps the sales.
 * @param newId Generates sale IDs.
 * @param locale The device's locale, for the payment page's language and country.
 * @param permits Whether cancellation is authorized now, checked under the operation lock.
 */
class PaymentLinks(
    private val scope: CoroutineScope,
    private val sales: SaleRepository,
    private val settings: SettingsRepository,
    private val target: suspend () -> ApiTarget,
    private val onCreated: (id: String, start: PaymentLinkStart) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val locale: () -> Locale = { Locale.getDefault() },
    private val permits: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val unresolvedStarts = mutableMapOf<String, PaymentLinkStart>()

    /**
     * Creates the payment link for [link] in the background and returns the new sale's ID straight away; the stored
     * sale follows: PENDING, then [SaleStatus.AWAITING_PAYMENT] once Adyen made the link, else failed or unknown.
     */
    fun start(link: PaymentLinkStart): String {
        val id = newId()
        synchronized(unresolvedStarts) { unresolvedStarts[id] = link }
        scope.launch {
            try {
                val initial = target()
                val language = locale()
                val pending =
                    link.pendingSale(
                        id,
                        clock.millis(),
                        initial.context,
                        language,
                        settings.current().payment.recurringProcessingModel,
                    )
                sales.createPending(pending, SaleBook.lines(id, link.payment.totals))
                mutex.withLock { sales.get(id)?.let { send(it) } }
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
            sales.record(id, SaleEvent.OutcomeUnknown(error.message))
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
                else -> withApi(sale) { stored(saleId, it.status(linkId)) }
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
            if (!permits()) return@withLock LinkUpdate.NotSetUp(SetupProblem.MANAGER_APPROVAL)
            withApi(sale) { stored(saleId, it.expire(linkId), cancelling = true) }
        }

    /** Creates the link of [record] (again, after an unknown outcome) and stores how that went. */
    private suspend fun send(record: SaleWithLines): LinkUpdate {
        val id = record.sale.id
        val target = target()
        val api = target.links
        if (api == null) {
            val problem = target.setup.problem ?: SetupProblem.API_REQUIRED
            return if (record.sale.status == SaleStatus.UNKNOWN) {
                LinkUpdate.NotSetUp(problem)
            } else {
                sales.record(id, SaleEvent.NotSetUp(problem))
                synchronized(unresolvedStarts) { unresolvedStarts.remove(id) }
                LinkUpdate.Settled
            }
        }
        if (target.context != null &&
            record.sale.context?.matchesApi(target.context) != true
        ) {
            return LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT)
        }
        val request = PaymentLinkRequests.request(record)
        return when (val result = api.create(request, idempotencyKey(id))) {
            is PaymentLinkResult.Answered -> {
                applied(id, result.link)
            }

            is PaymentLinkResult.NotProcessed -> {
                settleFailed(id, result.message)
            }

            is PaymentLinkResult.Unknown -> {
                sales.record(id, SaleEvent.OutcomeUnknown())
                LinkUpdate.Failed(result.message)
            }
        }
    }

    /** Runs [call] with the payment link API, or fails (changing nothing) when it is not set up now. */
    private suspend fun withApi(
        sale: SaleEntity,
        call: suspend (PaymentLinkApi) -> LinkUpdate,
    ): LinkUpdate {
        val target = target()
        val api = target.links ?: return LinkUpdate.NotSetUp(target.setup.problem ?: SetupProblem.API_REQUIRED)
        if (target.context != null &&
            sale.context?.matchesApi(target.context) != true
        ) {
            return LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT)
        }
        return call(api)
    }

    /** Stores Adyen's answer [result] about the link of sale [id], which was being expired when [cancelling]. */
    private suspend fun stored(
        id: String,
        result: PaymentLinkResult,
        cancelling: Boolean = false,
    ): LinkUpdate =
        when (result) {
            is PaymentLinkResult.Answered -> applied(id, result.link, cancelling)
            is PaymentLinkResult.NotProcessed -> LinkUpdate.Failed(result.message)
            is PaymentLinkResult.Unknown -> LinkUpdate.Failed(result.message)
        }

    /** Stores where [link], the link of sale [id], stands (see [SaleEvent.LinkAnswered]): still open, paid, or ended. */
    private suspend fun applied(
        id: String,
        link: PaymentLink,
        cancelling: Boolean = false,
    ): LinkUpdate {
        val answered = SaleEvent.LinkAnswered(link, cancelling)
        sales.record(id, answered)
        val start = synchronized(unresolvedStarts) { unresolvedStarts.remove(id) }
        if (start != null && (answered.stillOpen || link.status == PaymentLinkStatus.COMPLETED)) onCreated(id, start)
        return if (answered.stillOpen) LinkUpdate.StillOpen else LinkUpdate.Settled
    }

    private suspend fun settleFailed(
        id: String,
        message: String,
    ): LinkUpdate {
        sales.record(id, SaleEvent.NotSent(message))
        synchronized(unresolvedStarts) { unresolvedStarts.remove(id) }
        return LinkUpdate.Settled
    }

    /** Payment link values. */
    companion object {
        /** The idempotency key of the link of sale [saleId]: one link per sale, however often it is sent. */
        fun idempotencyKey(saleId: String): String = "link-$saleId"
    }
}

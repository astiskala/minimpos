package app.minimpos.app.payment

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.simulatedLink
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.PaymentLink
import app.minimpos.terminal.checkout.PaymentLinkApi
import app.minimpos.terminal.checkout.PaymentLinkResult
import app.minimpos.terminal.checkout.PaymentLinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.Locale
import kotlin.uuid.Uuid

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
     * Nothing changed: Adyen could not be asked (the Checkout API or Manager approval is missing, or the setup no longer
     * matches the link), refused, or did not answer a question about where the link stands.
     *
     * @property failure Why.
     */
    data class NotDone(
        val failure: Failure,
    ) : LinkUpdate

    /**
     * Adyen did not confirm a creation or expiry that may have taken effect; checking again establishes it. An unknown
     * creation is stored as unknown; an unconfirmed expiry leaves the stored sale awaiting its payment.
     *
     * @property failure What prevented the confirmation.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : LinkUpdate
}

/**
 * Sales paid through Adyen payment links instead of the terminal: creating the link ([start]), asking Adyen whether it
 * was paid ([check]) and expiring it ([cancel]). This app has no server for Adyen's webhooks, so asking is the only way
 * to learn the outcome, and Adyen's answer has no PSP reference: a paid link is refunded in the Customer Area.
 *
 * Each sale is stored as PENDING before Adyen is called and always ends in a final status (an unknown outcome is
 * [SaleStatus.UNKNOWN], which [check] resolves by creating the link again with the same idempotency key, so Adyen
 * returns the link made the first time). Creation and unknown-creation recovery run on [CheckoutOperations] through
 * [Book]; checks and cancellations of all links share the same lock, so an answer that arrives late never overwrites a
 * newer one. One coherent checkout ([PaymentStart.sessionRevision]) can start only one link, because each link can be
 * paid on its own, so a second would be a second charge.
 *
 * @param scope Where links are created, so a creation survives leaving the screen.
 * @param sales Where the sales are stored.
 * @param target Where payment links go now: [AdyenApi.target] in the app, a fixed target in tests.
 * @param onCreated Called once with the sale ID and its original request when the link is created or recovered by
 *   [check] in this process, to reconcile its originating session and arm automatic delivery.
 * @param clock Stamps the sales.
 * @param newId Generates sale IDs.
 * @param locale The device's locale, for the payment page's language and country.
 * @param approval Manager approval of cancelling a link or completing a demo, checked as held now under the operation
 *   lock; creating a link needs none.
 */
class PaymentLinks(
    scope: CoroutineScope,
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    onCreated: (id: String, start: PaymentLinkStart) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    newId: () -> String = { Uuid.random().toString() },
    locale: () -> Locale = { Locale.getDefault() },
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
) {
    private val mutex = Mutex()
    private val operations =
        CheckoutOperations(
            scope = scope,
            book = Book(sales, locale),
            target = target,
            onSucceeded = onCreated,
            clock = clock,
            newId = newId,
            mutex = mutex,
            approval = approval.forActions(setOf(ManagerAction.SALE, ManagerAction.LINK_CREATE)),
        )

    /**
     * Payment links as [FinancialOperations] holds them: busy while creation, recovery, a status check or cancellation
     * is using the current API target.
     */
    internal val operationKind =
        OperationKind(
            { operations.state.value is TransactionState.Processing || mutex.isLocked },
            operations::forget,
            operations::forgetAll,
        )

    /** Highest accepted revision per payment kind; older starts stay rejected without retaining every checkout. */
    private val linkedRevisions = mutableMapOf<SaleKind, Long>()

    /**
     * Creates the payment link for [link] in the background and returns the new sale's ID straight away; null, with
     * nothing stored or sent, while another link is being created or this checkout ([PaymentStart.sessionRevision])
     * already started one. The stored sale follows: PENDING, then [SaleStatus.AWAITING_PAYMENT] once Adyen made the
     * link, else failed or unknown.
     */
    fun start(link: PaymentLinkStart): String? {
        val revision = link.payment.sessionRevision ?: return started(link)
        return synchronized(linkedRevisions) {
            val kind = link.payment.kind
            if (revision <= (linkedRevisions[kind] ?: -1L)) return@synchronized null
            started(link)?.also { linkedRevisions[kind] = revision }
        }
    }

    private fun started(link: PaymentLinkStart): String? = operations.start(link)

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
                linkId == null -> recheckCreation(saleId)
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
            val approval = approval.forAction(ManagerAction.LINK_CHANGE)
            val target = target()
            val expiry =
                object : IdentifiedSend<PaymentLinkApi, PaymentLinkResult> {
                    override val unresolved = false
                    override val firstSentAt: Long? = null
                    override val reason: StoredReason? = null

                    override suspend fun approved() = approval.allowsNow()

                    override suspend fun access() = target.links(sale.context)

                    override suspend fun exchange(client: PaymentLinkApi) = client.expire(linkId)

                    override fun fault(answer: PaymentLinkResult) = (answer as? PaymentLinkResult.Failed)?.fault
                }
            when (val sent = OperationIdentity.send(expiry, clock.millis())) {
                Sent.Unapproved -> notSetUp(SetupProblem.MANAGER_APPROVAL)
                is Sent.Blocked -> notSetUp(sent.problem)
                is Sent.Expired -> LinkUpdate.NotDone(sent.failure)
                Sent.Withdrawn -> LinkUpdate.Settled
                is Sent.Unchanged -> LinkUpdate.Unconfirmed(sent.failure)
                is Sent.Answered -> expired(saleId, sent.answer, sent.failed)
            }
        }

    /**
     * Stores the answer [result] to expiring the link of sale [id], which [failed] classifies when it failed: an expiry
     * that may have taken effect is unconfirmed, and checking again establishes it.
     */
    private suspend fun expired(
        id: String,
        result: PaymentLinkResult,
        failed: FailedSend?,
    ): LinkUpdate =
        when (result) {
            is PaymentLinkResult.Answered -> applied(id, result.link, cancelling = true)
            is PaymentLinkResult.Failed if failed == FailedSend.UNCONFIRMED -> LinkUpdate.Unconfirmed(Failure.Remote(result.fault))
            is PaymentLinkResult.Failed -> LinkUpdate.NotDone(Failure.Remote(result.fault))
        }

    /**
     * Completes an open demo link explicitly, never a real link. Rechecks Manager approval and the original simulator
     * context under the operation lock. An already expired link expires instead; settled records stay unchanged.
     * The sale retains the outcome across restarts without relying on a simulator ledger.
     */
    suspend fun simulate(saleId: String): LinkUpdate =
        mutex.withLock {
            val sale = sales.get(saleId)?.sale ?: return@withLock LinkUpdate.Settled
            if (!sale.simulatedLink) return@withLock notSetUp(SetupProblem.PAYMENT_CONTEXT)
            if (sale.status != SaleStatus.AWAITING_PAYMENT) return@withLock LinkUpdate.Settled
            val linkId = sale.paymentLinkId ?: return@withLock LinkUpdate.Settled
            if (!approval.forAction(ManagerAction.LINK_CHANGE).allowsNow()) return@withLock notSetUp(SetupProblem.MANAGER_APPROVAL)
            withApi(sale) { api ->
                when (val result = api.status(linkId)) {
                    is PaymentLinkResult.Answered -> {
                        val link = result.link
                        val paid = if (link.status == PaymentLinkStatus.ACTIVE) link.copy(status = PaymentLinkStatus.COMPLETED) else link
                        applied(saleId, paid)
                    }

                    is PaymentLinkResult.Failed -> {
                        stored(saleId, result)
                    }
                }
            }
        }

    private suspend fun recheckCreation(saleId: String): LinkUpdate =
        when (val rechecked = operations.recheckUnlocked(saleId)) {
            Recheck.Settled -> {
                if (sales.get(saleId)?.sale?.status == SaleStatus.AWAITING_PAYMENT) {
                    LinkUpdate.StillOpen
                } else {
                    LinkUpdate.Settled
                }
            }

            is Recheck.StillUnknown -> {
                val failure = rechecked.failure
                if (failure is Failure.NotSetUp) LinkUpdate.NotDone(failure) else LinkUpdate.Unconfirmed(failure)
            }
        }

    /** Runs [call] with the payment link API, or fails (changing nothing) when it is not set up now. */
    private suspend fun withApi(
        sale: SaleEntity,
        call: suspend (PaymentLinkApi) -> LinkUpdate,
    ): LinkUpdate =
        when (val access = target().links(sale.context)) {
            is ApiAccess.Ready -> call(access.client)
            is ApiAccess.Blocked -> notSetUp(access.problem)
        }

    /** Stores Adyen's answer [result] about where the link of sale [id] stands; a question that got no answer changed nothing. */
    private suspend fun stored(
        id: String,
        result: PaymentLinkResult,
    ): LinkUpdate =
        when (result) {
            is PaymentLinkResult.Answered -> applied(id, result.link)
            is PaymentLinkResult.Failed -> LinkUpdate.NotDone(Failure.Remote(result.fault))
        }

    private fun notSetUp(problem: SetupProblem): LinkUpdate = LinkUpdate.NotDone(Failure.NotSetUp(problem))

    /** Stores where [link], the link of sale [id], stands (see [SaleEvent.LinkAnswered]): still open, paid, or ended. */
    private suspend fun applied(
        id: String,
        link: PaymentLink,
        cancelling: Boolean = false,
    ): LinkUpdate {
        val answered = SaleEvent.LinkAnswered(link, cancelling)
        sales.record(id, answered)
        return if (answered.stillOpen) LinkUpdate.StillOpen else LinkUpdate.Settled
    }

    /** Payment-link creation as a stored sale, sent with [PaymentLinkApi.create] built only from what the sale stored. */
    private class Book(
        private val sales: SaleRepository,
        private val locale: () -> Locale,
    ) : CheckoutBook<PaymentLinkStart, PaymentLinkApi> {
        override fun amount(request: PaymentLinkStart): OperationAmount = request.payment.amount

        override suspend fun open(
            id: String,
            request: PaymentLinkStart,
            createdAt: Long,
            current: PaymentContext?,
        ) = sales.createPending(request.pendingSale(id, createdAt, current, locale()), SaleBook.lines(id, request.payment.totals))

        override suspend fun unsent(id: String): CheckoutRecord? =
            sales
                .get(id)
                ?.sale
                ?.takeIf {
                    it.paymentLink && it.paymentLinkId == null && (it.status == SaleStatus.PENDING || it.status == SaleStatus.UNKNOWN)
                }?.let { CheckoutRecord(it.status == SaleStatus.PENDING, it.createdAt, it.context, it.reason) }

        override fun access(
            target: ApiTarget,
            context: PaymentContext?,
        ): ApiAccess<PaymentLinkApi> = target.links(context)

        override suspend fun send(
            id: String,
            client: PaymentLinkApi,
            now: Long,
        ): CheckoutReply {
            val record = sales.get(id) ?: return CheckoutReply { CheckoutAnswer.NotTaken }
            val result = client.create(PaymentLinkRequests.request(record), idempotencyKey(id))
            return CheckoutReply((result as? PaymentLinkResult.Failed)?.fault) { failed -> stored(id, result, failed) }
        }

        /** Stores [result], which [failed] classifies when it failed, and says what it established. */
        private suspend fun stored(
            id: String,
            result: PaymentLinkResult,
            failed: FailedSend?,
        ): CheckoutAnswer =
            when (result) {
                is PaymentLinkResult.Answered -> {
                    val answered = SaleEvent.LinkAnswered(result.link)
                    sales.record(id, answered)
                    if (answered.stillOpen || result.link.status == PaymentLinkStatus.COMPLETED) {
                        CheckoutAnswer.Taken
                    } else {
                        CheckoutAnswer.NotTaken
                    }
                }

                is PaymentLinkResult.Failed if failed == FailedSend.NOT_DONE -> {
                    sales.record(id, SaleEvent.NotSent(result.fault))
                    CheckoutAnswer.NotTaken
                }

                is PaymentLinkResult.Failed -> {
                    val failure = Failure.Remote(result.fault)
                    sales.record(id, SaleEvent.OutcomeUnknown(failure))
                    CheckoutAnswer.Unconfirmed(failure)
                }
            }

        override suspend fun notSent(
            id: String,
            problem: SetupProblem,
        ) {
            sales.record(id, SaleEvent.NotSetUp(problem))
        }

        override suspend fun interrupted(
            id: String,
            fault: DeviceFault,
        ) {
            sales.record(id, SaleEvent.OutcomeUnknown(Failure.Device(fault)))
        }
    }

    /** Payment link values. */
    companion object {
        /** The idempotency key of the link of sale [saleId]: one link per sale, however often it is sent. */
        fun idempotencyKey(saleId: String): String = "link-$saleId"
    }
}

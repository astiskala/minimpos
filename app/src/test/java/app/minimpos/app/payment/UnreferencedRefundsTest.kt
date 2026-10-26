package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.UnreferencedRefundReason
import app.minimpos.app.data.db.unresolved
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.app.feature.unreferencedRefundFacts
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.approvedSynchronously
import app.minimpos.app.history.cancellable
import app.minimpos.app.items
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class UnreferencedRefundsTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container
    private var time = 1000L
    private var configured = true
    private val lock = SessionLock { time }.apply { unlock() }
    private val approval = ManagerApproval({ configured }, lock) { 60_000 }
    internal val book get() = UnreferencedRefundBook(container.refundRecords)

    /** Enables unreferenced refunds for the simulator's verified offline demonstration, and returns the request. */
    private fun request(amountMinor: Long = 900): UnreferencedRefundStart {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "EUR")) }
        await { container.walletDiscovery.refresh() }
        val facts = container.walletDiscovery.state.value
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        unreferencedRefunds =
                            UnreferencedRefundSettings(
                                enabled = true,
                                limitMinor = 5000,
                                delayed = true,
                                context = facts.context,
                                currency = "EUR",
                            ),
                    ),
            )
        }
        val identity = container.settingsState.value.payment.unreferencedRefunds.identity
        return UnreferencedRefundStart(amountMinor, "EUR", "R-260930-1", UnreferencedRefundReason.RETURN_WITHOUT_RECEIPT, identity)
    }

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC)

    private fun runner(store: TransactionBook<UnreferencedRefundStart> = book) =
        TransactionLifecycle(env.scope, container.gateway, store, approval = approval)

    private fun run(
        start: UnreferencedRefundStart,
        lifecycle: TransactionLifecycle<UnreferencedRefundStart> = runner(),
    ): String {
        val id = checkNotNull(lifecycle.start(start))
        await { lifecycle.state.first { it == TransactionState.Finished(id) } }
        return id
    }

    @Test
    fun `starts disabled and is refused without explicit enablement`() {
        env.useSimulator()
        await { container.walletDiscovery.refresh() }
        val facts = container.walletDiscovery.state.value
        assertThat(container.storedPaymentActions.refundWithoutReceipt(900, "EUR", UnreferencedRefundReason.OTHER, facts)).isNull()
        assertThat(await { container.history.items().first() }).isEmpty()
    }

    @Test
    fun `an accepted refund has no original payment, is sent as a Payment and is only requested`() {
        val id = run(request())
        val refund = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.unreferenced).isTrue()
        assertThat(refund.saleId).isNull()
        assertThat(refund.originalTransactionId).isNull()
        assertThat(refund.originalTimestamp).isNull()
        assertThat(refund.originalReference).isNull()
        assertThat(refund.unreferencedReason).isEqualTo(UnreferencedRefundReason.RETURN_WITHOUT_RECEIPT)
        assertThat(refund.serviceId).isNotNull()
        assertThat(refund.context).isNotNull()
        assertThat(refund.pspReference).isNotNull()
        // The simulator answers like Adyen's usual asynchronous processing: accepted, not approved.
        assertThat(refund.acquirerResponseCode).isNull()
        assertThat(refund.approvedSynchronously).isFalse()
        assertThat(book.kind).isEqualTo(TransactionKind.PAYMENT)
        assertThat(env.context.unreferencedRefundFacts(refund)).containsAtLeast(
            "Not linked to an original payment",
            "Reason: Return without receipt",
        )
        val receipt = container.receiptFactory.refund(refund, container.settingsState.value.receipt)
        assertThat(receipt.toString()).contains("Not linked to an original payment")
        assertThat(receipt.toString()).doesNotContain("Original sale")
    }

    @Test
    fun `a synchronous acquirer answer is kept verbatim and read as approved, never as settlement`() {
        val start = request()
        val synchronous =
            object : TransactionBook<UnreferencedRefundStart> by book {
                override suspend fun settle(
                    id: String,
                    settlement: Settlement,
                ): Boolean =
                    book.settle(
                        id,
                        settlement.copy(
                            details =
                                settlement.details?.let {
                                    it.copy(additionalData = it.additionalData + ("acquirerResponseCode" to "Approved"))
                                },
                        ),
                    )
            }
        val id = run(start, runner(synchronous))
        val refund = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.acquirerResponseCode).isEqualTo("Approved")
        assertThat(refund.approvedSynchronously).isTrue()
        assertThat(env.context.unreferencedRefundFacts(refund)).contains("Acquirer response: Approved")
    }

    @Test
    fun `a missing answer is recovered from the original Payment after restart without a second refund`() {
        val start = request()
        env.updateSettings { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.TIMEOUT, delayMillis = 0)) }
        val unanswered =
            object : TransactionBook<UnreferencedRefundStart> by book {
                override suspend fun settle(
                    id: String,
                    settlement: Settlement,
                ): Boolean = book.settle(id, settlement.copy(status = SettlementStatus.UNKNOWN, details = null))
            }
        val id = run(start, runner(unanswered))
        val unknown = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(unknown.status).isEqualTo(RefundStatus.UNKNOWN)
        assertThat(unknown.unresolved).isTrue()
        // Routine history clearing keeps the unknown refund and its recovery evidence.
        await { container.history.prune(retentionDays = 1, now = Long.MAX_VALUE / 2) }
        assertThat(await { container.refundRecords.get(id) }).isNotNull()
        // A recreated runner asks the terminal about the original Payment; a Reversal check would find nothing.
        val recreated = runner()
        await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
        val settled = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(settled.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(settled.serviceId).isEqualTo(unknown.serviceId)
        assertThat(settled.context).isEqualTo(unknown.context)
        // The referenced refunds' runner never rechecks it as a reversal.
        assertThat(await { RefundBook(container.refundRecords).unsettledServiceId(id) }).isNull()
    }

    @Test
    fun `an interrupted refund becomes unknown at startup and is routed to its own status check`() {
        val start = request()
        val id = "interrupted-unreferenced"
        await {
            container.refundRecords.create(start.pendingRefund(id, 10, "SVC1"))
            container.history.settleInterrupted()
        }
        val interrupted = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(interrupted.status).isEqualTo(RefundStatus.UNKNOWN)
        assertThat(interrupted.reason).isEqualTo(StoredReason.Interrupted)
        // Never sent, so the simulator has no record of it: a known not-processed outcome, not a fresh refund.
        await { container.operations.recheck(StoredTransaction.Refund(id)) }
        assertThat(await { checkNotNull(container.refundRecords.get(id)).status }).isEqualTo(RefundStatus.FAILED)
    }

    @Test
    fun `changed enablement, limit or confirmation after review is refused by the send owner`() {
        val start = request()
        val original = container.settingsState.value.payment.unreferencedRefunds
        listOf(original.copy(enabled = false), original.copy(limitMinor = 500), original.copy(currency = "GBP"))
            .forEach { changed ->
                env.updateSettings { it.copy(payment = it.payment.copy(unreferencedRefunds = changed)) }
                val id = run(start)
                val refund = await { checkNotNull(container.refundRecords.get(id)) }
                assertThat(refund.status).isEqualTo(RefundStatus.FAILED)
                assertThat(refund.reason).isEqualTo(StoredReason.NotDone(Failure.Remote(Fault.Unsupported)))
            }
    }

    @Test
    fun `a Manager PIN must be configured and still valid when the refund is sent`() {
        val start = request()
        configured = false
        val unconfigured = await { checkNotNull(container.refundRecords.get(run(start))) }
        assertThat(unconfigured.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        configured = true
        val expiring =
            object : TransactionBook<UnreferencedRefundStart> by book {
                override suspend fun open(
                    id: String,
                    request: UnreferencedRefundStart,
                    serviceId: String,
                    createdAt: Long,
                ): Boolean = book.open(id, request, serviceId, createdAt).also { time += 61_000 }
            }
        val expired = await { checkNotNull(container.refundRecords.get(run(start, runner(expiring)))) }
        assertThat(expired.status).isEqualTo(RefundStatus.FAILED)
        assertThat(expired.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
    }

    @Test
    fun `a second start while one runs stores and sends nothing`() {
        val start = request()
        env.updateSettings { it.copy(simulator = it.simulator.copy(delayMillis = 60_000)) }
        val lifecycle = runner()
        val first = checkNotNull(lifecycle.start(start))
        assertThat(lifecycle.start(start)).isNull()
        await { lifecycle.state.first { (it as? TransactionState.Processing)?.canCancel == true } }
        assertThat(lifecycle.start(start)).isNull()
        lifecycle.cancel()
        await { lifecycle.state.first { it == TransactionState.Finished(first) } }
        assertThat(await { container.history.items().first() }.filterIsInstance<HistoryItem.Refund>()).hasSize(1)
    }

    @Test
    fun `accounting counts the refund separately and the terminal credit includes it`() {
        val id = run(request(1200))
        val refund = await { checkNotNull(container.refundRecords.get(id)) }
        val date = Instant.ofEpochMilli(checkNotNull(refund.processedAt)).atZone(ZoneOffset.UTC).toLocalDate()
        val day =
            HistoryAccounting
                .report(listOf(HistoryItem.Refund(refund)), date, ZoneOffset.UTC, 0)
                .sections
                .single()
                .totals
                .getValue("EUR")
        assertThat(day.refundsMinor).isEqualTo(1200)
        assertThat(day.unreferencedRefundsMinor).isEqualTo(1200)
        assertThat(day.refundedSurchargesMinor).isEqualTo(0)
        assertThat(day.unknownRefundSurchargeCount).isEqualTo(0)
    }

    @Test
    fun `a delayed refund is cancelled once, with its identity kept while the answer is unknown`() {
        val id = run(request())
        val accepted = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(accepted.cancellable(delayed = true)).isTrue()
        assertThat(accepted.cancellable(delayed = false)).isFalse()
        val voids = Voids()
        val sent = voids.sent
        val target = ApiTarget(ApiSetup.Complete, voids, context = accepted.context)
        val cancellations = RefundCancellations(container.refundRecords, { target }, { true }, approval, clock)
        await { assertThat(cancellations.cancel(id)).isInstanceOf(RefundCancelResult.Unconfirmed::class.java) }
        val unknown = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(unknown.cancelStatus).isEqualTo(RefundCancelStatus.UNKNOWN)
        assertThat(unknown.unresolved).isTrue()
        // A later definite rejection cannot prove the first request was not received.
        voids.answer = ModificationResult.Failed(Fault.AdyenRejected(422, null, null))
        await { assertThat(cancellations.cancel(id)).isInstanceOf(RefundCancelResult.Unconfirmed::class.java) }
        voids.answer = ModificationResult.Received("VOID1")
        await { assertThat(cancellations.cancel(id)).isEqualTo(RefundCancelResult.Requested) }
        val requested = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(requested.cancelStatus).isEqualTo(RefundCancelStatus.REQUESTED)
        assertThat(requested.cancelPspReference).isEqualTo("VOID1")
        assertThat(requested.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(requested.cancelStartedAt).isEqualTo(unknown.cancelStartedAt)
        assertThat(sent.map { it.second }.distinct()).containsExactly("void-$id")
        assertThat(sent.map { it.first }.distinct()).containsExactly(accepted.pspReference)
        await { assertThat(cancellations.cancel(id)).isEqualTo(RefundCancelResult.NotAllowed) }
        assertThat(sent).hasSize(3)
        assertThat(env.context.unreferencedRefundFacts(requested)).contains(
            "Cancellation requested. Check your Customer Area to see if the refund was stopped.",
        )
    }

    @Test
    fun `cancellation is refused for referenced refunds, other contexts and without approval`() {
        val id = run(request())
        val accepted = await { checkNotNull(container.refundRecords.get(id)) }
        val target = ApiTarget(ApiSetup.Complete, Voids(), context = accepted.context?.copy(simulated = false, environment = "TEST"))
        val mismatched = RefundCancellations(container.refundRecords, { target }, { true }, approval, clock)
        await { assertThat(mismatched.cancel(id)).isInstanceOf(RefundCancelResult.NotDone::class.java) }
        configured = true
        lock.lock()
        val unapproved = RefundCancellations(container.refundRecords, { target }, { true }, approval, clock)
        await { assertThat(unapproved.cancel(id)).isEqualTo(RefundCancelResult.NotAllowed) }
        lock.unlock()
        val undelayed = RefundCancellations(container.refundRecords, { target }, { false }, approval, clock)
        await { assertThat(undelayed.cancel(id)).isEqualTo(RefundCancelResult.NotAllowed) }
        await { assertThat(undelayed.cancel("missing")).isEqualTo(RefundCancelResult.NotAllowed) }
        // With the simulator's own Checkout API in the right context, the pending refund is cancelled once.
        val cancellations = RefundCancellations(container.refundRecords, container.api::target, { true }, approval, clock)
        await { assertThat(cancellations.cancel(id)).isEqualTo(RefundCancelResult.Requested) }
        await { assertThat(cancellations.cancel(id)).isEqualTo(RefundCancelResult.NotAllowed) }
    }

    @Test
    fun `approval expiring while the cancellation target is unlocked sends nothing`() {
        val id = run(request())
        val accepted = await { checkNotNull(container.refundRecords.get(id)) }
        val voids = Voids()
        val target = ApiTarget(ApiSetup.Complete, voids, context = accepted.context)
        val cancellations =
            RefundCancellations(container.refundRecords, {
                time += 60_001L
                target
            }, { true }, approval, clock)
        assertThat(await { cancellations.cancel(id) }).isEqualTo(RefundCancelResult.NotAllowed)
        assertThat(voids.sent).isEmpty()
        assertThat(await { container.refundRecords.get(id)?.cancelStatus }).isNull()
    }

    @Test
    fun `approval expiring while the terminal context is stored sends no refund`() {
        val start = request()
        val delayed =
            object : TransactionBook<UnreferencedRefundStart> by book {
                override suspend fun recordContext(
                    id: String,
                    context: PaymentContext,
                ) {
                    book.recordContext(id, context)
                    time += 60_001L
                }
            }
        val id = run(start, runner(delayed))
        val refund = await { checkNotNull(container.refundRecords.get(id)) }
        assertThat(refund.status).isEqualTo(RefundStatus.FAILED)
        assertThat(refund.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        assertThat(refund.pspReference).isNull()
    }

    /** Records cancellation requests and answers them with [answer]; nothing else is sent. */
    private class Voids : PaymentModifications {
        var answer: ModificationResult = ModificationResult.Failed(Fault.TimedOut)
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun capture(
            paymentPspReference: String,
            amount: ModificationAmount,
            reference: String,
            idempotencyKey: String,
        ) = error("unused")

        override suspend fun updateAmount(
            paymentPspReference: String,
            amount: ModificationAmount,
            reference: String,
            adjustAuthorisationData: String?,
            idempotencyKey: String,
        ) = error("unused")

        override suspend fun cancelPendingRefund(
            refundPspReference: String,
            idempotencyKey: String,
        ): ModificationResult = answer.also { sent += refundPspReference to idempotencyKey }

        override suspend fun verify(): Fault? = null
    }

    @Test
    fun `cancellation edge paths keep an unresolved request and never misread other answers`() {
        val id = run(request())
        val accepted = await { checkNotNull(container.refundRecords.get(id)) }
        val voids = Voids()
        val ready = ApiTarget(ApiSetup.Complete, voids, context = accepted.context)
        val cancellations = RefundCancellations(container.refundRecords, { ready }, { true }, approval, clock)
        // A refused or synchronous answer the API does not document is unreadable or treated as received.
        voids.answer = ModificationResult.Refused(null)
        await { assertThat(cancellations.cancel(id)).isInstanceOf(RefundCancelResult.NotDone::class.java) }
        assertThat(await { container.refundRecords.get(id)?.cancelStatus }).isEqualTo(RefundCancelStatus.FAILED)
        voids.answer = ModificationResult.Failed(Fault.AdyenRejected(422, null, null))
        await { assertThat(cancellations.cancel(id)).isInstanceOf(RefundCancelResult.NotDone::class.java) }
        voids.answer = ModificationResult.Failed(Fault.TimedOut)
        await { assertThat(cancellations.cancel(id)).isInstanceOf(RefundCancelResult.Unconfirmed::class.java) }
        // Without setup, an unresolved request stays unconfirmed rather than becoming not done.
        val missing = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
        val blocked = RefundCancellations(container.refundRecords, { missing }, { false }, approval, clock)
        await { assertThat(blocked.cancel(id)).isInstanceOf(RefundCancelResult.Unconfirmed::class.java) }
        // Beyond Adyen's idempotency window it is not sent again.
        val late = Clock.offset(clock, Duration.ofDays(8))
        val expired = RefundCancellations(container.refundRecords, { ready }, { true }, approval, late)
        await { assertThat(expired.cancel(id)).isInstanceOf(RefundCancelResult.Unconfirmed::class.java) }
        voids.answer = ModificationResult.Authorised("VOID2", null)
        await { assertThat(cancellations.cancel(id)).isEqualTo(RefundCancelResult.Requested) }
        assertThat(cancellations.running).isFalse()
        // The repository refuses anything but an accepted unreferenced refund, and a received cancellation is final.
        await {
            assertThat(container.refundRecords.cancelSending(id, 0)).isNull()
            container.refundRecords.cancelAnswered(id, RefundCancelStatus.FAILED, null, null)
            container.refundRecords.cancelAnswered("missing", RefundCancelStatus.FAILED, null, null)
            assertThat(container.refundRecords.cancelSending("missing", 0)).isNull()
        }
        assertThat(await { container.refundRecords.get(id)?.cancelStatus }).isEqualTo(RefundCancelStatus.REQUESTED)
        val referenced = accepted.copy(id = "referenced", unreferenced = false)
        await { container.refundRecords.create(referenced.copy(status = RefundStatus.PENDING)) }
        await { assertThat(container.refundRecords.cancelSending("referenced", 0)).isNull() }
        assertThat(referenced.cancellable(delayed = true)).isFalse()
        assertThat(accepted.copy(pspReference = null).cancellable(delayed = true)).isFalse()
        assertThat(accepted.copy(status = RefundStatus.UNKNOWN).cancellable(delayed = true)).isFalse()
        assertThat(accepted.copy(cancelStatus = RefundCancelStatus.UNKNOWN).cancellable(delayed = false)).isTrue()
        assertThat(accepted.copy(cancelStatus = RefundCancelStatus.SENDING).unresolved).isTrue()
        assertThat(accepted.copy(cancelStatus = RefundCancelStatus.FAILED).unresolved).isFalse()
        assertThat(accepted.copy(acquirerResponseCode = "Approved", status = RefundStatus.FAILED).approvedSynchronously).isFalse()
        assertThat(referenced.copy(acquirerResponseCode = "Approved").approvedSynchronously).isFalse()
    }
}

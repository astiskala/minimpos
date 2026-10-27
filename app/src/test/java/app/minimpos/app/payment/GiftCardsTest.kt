package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.GiftCardCategory
import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.GiftCardAction
import app.minimpos.app.data.repo.GiftCardActivity
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.protectedApproval
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/** Stored-value activities on the simulator: funding stages, value changes, repairs, cash handover and recovery. */
@RunWith(RobolectricTestRunner::class)
class GiftCardsTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val records = container.giftCardRecords
    private var configured = true
    private val lock = SessionLock { 1_000L }.apply { unlock() }
    private val approval = protectedApproval(lock, 60_000L) { configured }
    private val svs = GiftCardSetting(GiftCardProvider.SVS, entries = setOf(GiftCardEntry.SWIPED, GiftCardEntry.SCANNED))
    private val givex = GiftCardSetting(GiftCardProvider.GIVEX, entries = setOf(GiftCardEntry.SWIPED, GiftCardEntry.SCANNED))
    private var busy = false
    private val giftCards by lazy {
        GiftCards(
            scope = CoroutineScope(SupervisorJob()),
            gateway = container.gateway,
            records = records,
            cash = container.cashTenders,
            approval = approval,
            busy = { busy },
            environment = { "SIMULATOR" },
        )
    }

    @After
    fun tearDown() = env.close()

    private fun simulator() =
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "AUD", giftCards = listOf(svs, givex)))
        }

    private fun card(
        setting: GiftCardSetting = svs,
        number: String? = null,
    ): PresentedGiftCard =
        checkNotNull(
            if (number == null) {
                PresentedGiftCard.of(setting, GiftCardEntry.SWIPED)
            } else {
                PresentedGiftCard.of(setting, GiftCardEntry.SCANNED, number, "1230")
            },
        )

    /** Runs [request] to its end and returns its activity. */
    private fun run(request: GiftCardRequest): GiftCardActivity {
        val id = checkNotNull(giftCards.start(request))
        await { giftCards.state.first { it == TransactionState.Finished(id) } }
        giftCards.acknowledge()
        return activity(id)
    }

    private fun activity(id: String) = await { checkNotNull(records.activity(id)) }

    @Test
    fun `presenting a card needs a configured entry and a valid scan`() {
        assertThat(PresentedGiftCard.of(givex, GiftCardEntry.KEYED)).isNull()
        assertThat(PresentedGiftCard.of(svs, GiftCardEntry.SCANNED, "12", "1230")).isNull()
        assertThat(PresentedGiftCard.of(GiftCardSetting(GiftCardProvider.INTERSOLVE), GiftCardEntry.SWIPED)).isNull()
        val scanned = card(number = "6006491234567890")
        assertThat(scanned.entry).isEqualTo(GiftCardEntry.SCANNED)
        assertThat(scanned.toString()).doesNotContain("6006")
        assertThat(card().entry).isEqualTo(GiftCardEntry.SWIPED)
        assertThat(
            PresentedGiftCard.of(GiftCardSetting(GiftCardProvider.SVS, entries = setOf(GiftCardEntry.KEYED)), GiftCardEntry.KEYED)?.entry,
        ).isEqualTo(GiftCardEntry.KEYED)
    }

    @Test
    fun `start problems follow the destination, the provider and the Manager PIN`() {
        assertThat(giftCardStartProblem(GiftCardOperation.LOAD, svs, SetupProblem.GIFT_CARDS, true)).isEqualTo(SetupProblem.GIFT_CARDS)
        assertThat(giftCardStartProblem(GiftCardOperation.DEACTIVATE, svs, null, true)).isEqualTo(SetupProblem.GIFT_CARD_PROVIDER)
        assertThat(giftCardStartProblem(GiftCardOperation.LOAD, null, null, true)).isEqualTo(SetupProblem.GIFT_CARD_PROVIDER)
        assertThat(giftCardStartProblem(GiftCardOperation.LOAD, svs, null, false)).isEqualTo(SetupProblem.MANAGER_APPROVAL)
        assertThat(giftCardStartProblem(GiftCardOperation.BALANCE, svs, null, false)).isNull()
        assertThat(giftCardStartProblem(GiftCardOperation.DEACTIVATE, givex, null, true)).isNull()
    }

    @Test
    fun `card funding is stored and approved before the load, as linked stages`() {
        simulator()
        val activated = run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        assertThat(activated.stages.single().status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(activated.stages.single().category).isEqualTo(GiftCardCategory.STORED_VALUE)
        assertThat(activated.stages.single().cardLastFour).isEqualTo("0042")

        val loaded = run(GiftCardRequest.Load(card(), 1500, "AUD", GiftCardFunding.CARD))
        val (funding, load) = loaded.stages
        assertThat(funding.stage).isEqualTo(GiftCardStage.FUNDING_CARD)
        assertThat(funding.category).isEqualTo(GiftCardCategory.PAYMENT)
        assertThat(funding.funds).isEqualTo(GiftCardStage.LOAD)
        assertThat(funding.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(funding.context).isNotNull()
        assertThat(load.stage).isEqualTo(GiftCardStage.LOAD)
        assertThat(load.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(load.balanceMinor).isEqualTo(1500)
        assertThat(load.merchantReference).startsWith("G-")
        assertThat(loaded.kind).isEqualTo(GiftCardStage.LOAD)
        assertThat(loaded.delivered).isTrue()
        assertThat(loaded.needsAttention).isFalse()
        assertThat(loaded.totals.cardFundingMinor).isEqualTo(1500)
        assertThat(loaded.totals.valueAddedMinor).isEqualTo(1500)

        val balance = await { giftCards.balance(card()) }
        assertThat(balance).isEqualTo(GiftCardBalanceResult.Read(1500, "AUD", "0042"))

        val document = container.receiptFactory.giftCard(loaded, container.settingsState.value.receipt)
        val rows = document.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }
        assertThat(rows).containsAtLeast("Gift card", "Card payment", "Load", "Balance")
    }

    @Test
    fun `value changes need a configured Manager PIN checked before sending`() {
        simulator()
        configured = false
        val refused = run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        assertThat(refused.stages.single().status).isEqualTo(GiftCardStatus.FAILED)
        assertThat(refused.stages.single().reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        // External funding records nothing without approval.
        val external = checkNotNull(giftCards.start(GiftCardRequest.Activate(card(), 500, "AUD", GiftCardFunding.EXTERNAL)))
        await { giftCards.state.first { it == TransactionState.Finished(external) } }
        giftCards.acknowledge()
        assertThat(await { records.activity(external) }).isNull()
        // A balance inquiry is an ordinary request.
        assertThat(await { giftCards.balance(card()) }).isInstanceOf(GiftCardBalanceResult.NotRead::class.java)
        configured = true
        lock.lock()
        val locked = run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        assertThat(locked.stages.single().status).isEqualTo(GiftCardStatus.FAILED)
    }

    @Test
    fun `a failed activation after confirmed funding is repaired by refunding the payment, never repeated blindly`() {
        simulator()
        run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        val again = run(GiftCardRequest.Activate(card(), 500, "AUD", GiftCardFunding.CARD))
        val (funding, activation) = again.stages
        assertThat(funding.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(activation.status).isEqualTo(GiftCardStatus.DECLINED)
        assertThat(activation.message).contains("already active")
        assertThat(again.needsRepair).isTrue()
        assertThat(again.attention).containsExactly(GiftCardAction.CONTINUE, GiftCardAction.REFUND_FUNDING)
        val history = HistoryAccounting.activities(listOf(HistoryItem.GiftCard(again)))
        assertThat(HistoryAccounting.totals(history).getValue("AUD").unresolvedCount).isEqualTo(1)

        val repaired = run(GiftCardRequest.RefundFunding(again.groupId, 500, "AUD"))
        val refund = repaired.stages.last()
        assertThat(refund.stage).isEqualTo(GiftCardStage.FUNDING_REFUND)
        assertThat(refund.category).isEqualTo(GiftCardCategory.REVERSAL)
        assertThat(refund.originalId).isEqualTo(funding.id)
        assertThat(refund.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(repaired.needsAttention).isFalse()
        assertThat(repaired.totals.fundingReturnedMinor).isEqualTo(500)
        // Nothing is left to repair, so a second refund stores nothing.
        val second = checkNotNull(giftCards.start(GiftCardRequest.RefundFunding(again.groupId, 500, "AUD")))
        await { giftCards.state.first { it == TransactionState.Finished(second) } }
        assertThat(activity(again.groupId).stages).hasSize(3)
    }

    @Test
    fun `a load refused before activation continues once the card is active, with the same funding`() {
        simulator()
        val failed = run(GiftCardRequest.Load(card(), 700, "AUD", GiftCardFunding.EXTERNAL))
        assertThat(failed.funding?.stage).isEqualTo(GiftCardStage.FUNDING_EXTERNAL)
        assertThat(failed.funding?.environment).isEqualTo("SIMULATOR")
        assertThat(failed.value?.status).isEqualTo(GiftCardStatus.DECLINED)
        assertThat(failed.attention).containsExactly(GiftCardAction.CONTINUE, GiftCardAction.RETURN_FUNDS)

        run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        val continued = run(GiftCardRequest.Continue(failed.groupId, card(), 700, "AUD"))
        assertThat(continued.stages.map { it.stage })
            .containsExactly(GiftCardStage.FUNDING_EXTERNAL, GiftCardStage.LOAD, GiftCardStage.LOAD)
            .inOrder()
        assertThat(continued.delivered).isTrue()
        assertThat(continued.needsAttention).isFalse()
        assertThat(continued.totals.externalFundingMinor).isEqualTo(700)
        assertThat(continued.totals.valueAddedMinor).isEqualTo(700)
        assertThat(await { giftCards.balance(card()) }).isEqualTo(GiftCardBalanceResult.Read(700, "AUD", "0042"))
    }

    @Test
    fun `funds received outside the app can be recorded as given back`() {
        simulator()
        val failed = run(GiftCardRequest.Load(card(), 300, "AUD", GiftCardFunding.EXTERNAL))
        val returned = run(GiftCardRequest.ReturnFunds(failed.groupId, 300, "AUD"))
        assertThat(returned.stages.last().stage).isEqualTo(GiftCardStage.FUNDING_RETURNED)
        assertThat(returned.stages.last().status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(returned.needsAttention).isFalse()
    }

    @Test
    fun `unknown funding blocks the value until its status check settles it`() {
        simulator()
        val groupId = "interrupted"
        await {
            records.open(
                GiftCardOperationEntity(
                    id = "fund",
                    groupId = groupId,
                    stage = GiftCardStage.FUNDING_CARD,
                    category = GiftCardCategory.PAYMENT,
                    createdAt = 1,
                    status = GiftCardStatus.PENDING,
                    provider = "SVS",
                    currency = "AUD",
                    amountMinor = 400,
                    merchantReference = "G-1",
                    serviceId = "LOSTFUND01",
                    funds = GiftCardStage.LOAD,
                ),
            )
            container.history.settleInterrupted()
        }
        val unknown = activity(groupId)
        assertThat(unknown.funding?.status).isEqualTo(GiftCardStatus.UNKNOWN)
        assertThat(unknown.funding?.reason).isEqualTo(StoredReason.Interrupted)
        assertThat(unknown.attention).containsExactly(GiftCardAction.CHECK_AGAIN)
        // No value can be sent while the funding is unknown.
        val blocked = checkNotNull(giftCards.start(GiftCardRequest.Continue(groupId, card(), 400, "AUD")))
        await { giftCards.state.first { it == TransactionState.Finished(blocked) } }
        assertThat(activity(groupId).stages).hasSize(1)
        // The simulator never saw it, so the check settles it as not done; nothing needs repair.
        assertThat(await { container.operations.recheck(StoredTransaction.GiftCard(groupId)) }).isEqualTo(Recheck.Settled)
        val settled = activity(groupId)
        assertThat(settled.funding?.status).isEqualTo(GiftCardStatus.FAILED)
        assertThat(settled.needsAttention).isFalse()
    }

    @Test
    fun `an unexpected failure keeps its stored stage recoverable before request cleanup`() {
        simulator()
        val presented = card(number = "6006491234567890")
        presented.discard()
        val interrupted = run(GiftCardRequest.Activate(presented, 0, "AUD", null))

        assertThat(interrupted.stages.single().status).isEqualTo(GiftCardStatus.UNKNOWN)
        assertThat(interrupted.stages.single().reason)
            .isEqualTo(StoredReason.Unconfirmed(Failure.Device(DeviceFault.UNEXPECTED)))
        assertThat(interrupted.attention).containsExactly(GiftCardAction.CHECK_AGAIN)
        await { container.history.clear() }
        assertThat(await { records.activity(interrupted.groupId) }).isNotNull()
    }

    @Test
    fun `a receipt callback failure cannot downgrade an already stored gift card approval`() {
        simulator()
        val notifying =
            GiftCards(
                scope = env.scope,
                gateway = container.gateway,
                records = records,
                cash = container.cashTenders,
                approval = approval,
                onFinished = { throw IllegalStateException("Receipt callback failed") },
            )
        val id = checkNotNull(notifying.start(GiftCardRequest.Activate(card(), 0, "AUD", null)))
        await { notifying.state.first { it == TransactionState.Finished(id) } }
        assertThat(activity(id).stages.single().status).isEqualTo(GiftCardStatus.SUCCEEDED)
    }

    @Test
    fun `history clearing retains a pending gift card stage without waiting for startup recovery`() {
        simulator()
        val pending =
            GiftCardOperationEntity(
                id = "pending-load",
                groupId = "pending-activity",
                stage = GiftCardStage.LOAD,
                category = GiftCardCategory.STORED_VALUE,
                createdAt = 1,
                status = GiftCardStatus.PENDING,
                provider = "SVS",
                currency = "AUD",
                amountMinor = 400,
                merchantReference = "G-pending",
                serviceId = "PENDING01",
            )
        await {
            records.open(pending)
            container.history.clear()
        }
        assertThat(await { records.get(pending.id) }).isEqualTo(pending)
    }

    @Test
    fun `cash out with an unreported amount keeps the payout unknown and cannot authorize a handover`() {
        simulator()
        val cashOut =
            GiftCardOperationEntity(
                id = "unreported-cash",
                groupId = "unreported-payout",
                stage = GiftCardStage.CASH_OUT,
                category = GiftCardCategory.PAYMENT,
                createdAt = 1,
                status = GiftCardStatus.PENDING,
                provider = "SVS",
                currency = "AUD",
                amountMinor = 400,
                merchantReference = "G-cash",
                serviceId = "CASH01",
            )
        await {
            records.open(cashOut)
            records.settle(cashOut.id, GiftCardStatus.SUCCEEDED, null, null, null)
        }
        val payout = activity(cashOut.groupId)
        assertThat(payout.change?.approvedMinor).isNull()
        assertThat(payout.cashHandover?.needsAttention).isTrue()
        assertThat(payout.cashHandover?.canRecord).isFalse()
        assertThat(payout.totals.valueRemovedMinor).isEqualTo(0)
        assertThat(await { giftCards.confirmHandover(cashOut.groupId) }).isFalse()
        await { container.history.clear() }
        assertThat(await { records.activity(cashOut.groupId) }).isNotNull()
    }

    @Test
    fun `cash out removes the value and leaves the cash handover for staff, even after a restart`() {
        simulator()
        run(GiftCardRequest.Activate(card(), 1000, "AUD", GiftCardFunding.EXTERNAL))
        val partial = run(GiftCardRequest.CashOut(card(), 400, "AUD"))
        assertThat(partial.change?.status).isEqualTo(GiftCardStatus.DECLINED)
        assertThat(partial.stages.none { it.stage == GiftCardStage.CASH_HANDOVER }).isTrue()

        val cashOut = run(GiftCardRequest.CashOut(card(), 1000, "AUD"))
        val handover = cashOut.stages.last()
        assertThat(handover.stage).isEqualTo(GiftCardStage.CASH_HANDOVER)
        assertThat(handover.status).isEqualTo(GiftCardStatus.AWAITING)
        assertThat(handover.amountMinor).isEqualTo(1000)
        assertThat(cashOut.attention).containsExactly(GiftCardAction.CONFIRM_HANDOVER)
        // A restart settles only interrupted requests; the awaiting handover stays for review.
        await { container.history.settleInterrupted() }
        assertThat(activity(cashOut.groupId).stages.last().status).isEqualTo(GiftCardStatus.AWAITING)
        // Retention keeps it whatever its age.
        await { container.history.clear() }
        assertThat(await { records.activity(cashOut.groupId) }).isNotNull()

        configured = false
        assertThat(await { giftCards.confirmHandover(cashOut.groupId) }).isFalse()
        configured = true
        assertThat(await { giftCards.confirmHandover(cashOut.groupId) }).isTrue()
        assertThat(await { giftCards.confirmHandover(cashOut.groupId) }).isFalse()
        val done = activity(cashOut.groupId)
        assertThat(done.needsAttention).isFalse()
        assertThat(done.totals.valueRemovedMinor).isEqualTo(1000)
        assertThat(done.totals.cashHandedOverMinor).isEqualTo(1000)
        assertThat(await { giftCards.balance(card()) }).isInstanceOf(GiftCardBalanceResult.NotRead::class.java)
        await { container.history.clear() }
        assertThat(await { records.activity(cashOut.groupId) }).isNull()
    }

    @Test
    fun `only the latest load within the window is undone, and its card payment then needs a refund`() {
        simulator()
        run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        val first = run(GiftCardRequest.Load(card(), 300, "AUD", GiftCardFunding.CARD))
        val second = run(GiftCardRequest.Load(card(), 200, "AUD", GiftCardFunding.CARD))
        val now = System.currentTimeMillis()
        val recent = await { records.recentActivities(now) }
        assertThat(GiftCardAction.UNDO_LOAD in first.actions(now, recent)).isFalse()
        assertThat(GiftCardAction.UNDO_LOAD in second.actions(now, recent)).isTrue()
        assertThat(GiftCardAction.UNDO_LOAD in second.actions(now + 25 * 60 * 60 * 1000L, recent)).isFalse()

        val refusedFirst = checkNotNull(giftCards.start(GiftCardRequest.UndoLoad(first.groupId, 300, "AUD")))
        await { giftCards.state.first { it == TransactionState.Finished(refusedFirst) } }
        giftCards.acknowledge()
        assertThat(activity(first.groupId).stages).hasSize(2)

        val undone = run(GiftCardRequest.UndoLoad(second.groupId, 200, "AUD"))
        val undo = undone.stages.last()
        assertThat(undo.stage).isEqualTo(GiftCardStage.UNDO_LOAD)
        assertThat(undo.originalId).isEqualTo(second.value?.id)
        assertThat(undo.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(undo.balanceMinor).isEqualTo(300)
        assertThat(undone.undone).isTrue()
        assertThat(undone.totals.valueAddedMinor).isEqualTo(0)
        assertThat(undone.attention).containsExactly(GiftCardAction.REFUND_FUNDING)
    }

    @Test
    fun `deactivation is offered by Givex only, and starts are refused while anything runs`() {
        simulator()
        val unsupported = checkNotNull(giftCards.start(GiftCardRequest.Deactivate(card(), "AUD")))
        await { giftCards.state.first { it == TransactionState.Finished(unsupported) } }
        giftCards.acknowledge()
        assertThat(await { records.activity(unsupported) }).isNull()

        run(GiftCardRequest.Activate(card(givex, "6006491234567890"), 0, "AUD", null))
        val off = run(GiftCardRequest.Deactivate(card(givex, "6006491234567890"), "AUD"))
        assertThat(off.change?.stage).isEqualTo(GiftCardStage.DEACTIVATE)
        assertThat(off.change?.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(off.cardLastFour).isEqualTo("7890")

        busy = true
        assertThat(giftCards.start(GiftCardRequest.Activate(card(), 0, "AUD", null))).isNull()
    }

    @Test
    fun `an unreferenced refund credits the card`() {
        simulator()
        run(GiftCardRequest.Activate(card(), 100, "AUD", GiftCardFunding.EXTERNAL))
        val refund = run(GiftCardRequest.Refund(card(), 250, "AUD"))
        assertThat(refund.change?.stage).isEqualTo(GiftCardStage.REFUND)
        assertThat(refund.change?.category).isEqualTo(GiftCardCategory.PAYMENT)
        assertThat(refund.change?.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(await { giftCards.balance(card()) }).isEqualTo(GiftCardBalanceResult.Read(350, "AUD", "0042"))
    }

    @Test
    fun `a gift card redeems part of a split sale and its return goes back to the card`() {
        simulator()
        run(GiftCardRequest.Activate(card(), 500, "AUD", GiftCardFunding.EXTERNAL))
        val session = container.session(SaleKind.SALE)
        await {
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val productId = container.catalog.saveProduct(ProductEntity(name = "Hamper", priceMinor = 1300, taxRateId = taxId))
            session.addProduct(container.catalog.product(productId)!!, TaxRateEntity(taxId, "GST", 10_000))
        }
        val checkout =
            await {
                session
                    .checkout(
                        container.settingsState,
                        container.terminalStatus.state,
                        currency = container::currency,
                    ).first { it.canPay }
            }
        val start = checkNotNull(session.splitStart(checkout, Instant.now(), ZoneOffset.UTC, 1300, null)).withGiftCard(card())
        assertThat(start.allowPartialAuthorisation).isTrue()
        val id = checkNotNull(container.payments.start(start))
        await { container.payments.state.first { it == TransactionState.Finished(id) } }
        container.payments.acknowledge()
        val record = await { checkNotNull(container.sales.get(id)) }
        val tender = record.tenders.single()
        assertThat(tender.giftCard).isEqualTo("SVS")
        assertThat(tender.authorisedMinor).isEqualTo(500)
        assertThat(record.sale.paidMinor).isEqualTo(500)
        assertThat(record.sale.remainingMinor).isEqualTo(800)
        val legs = (RefundablePayment.check(record) as Refundability.Refundable).payment.legs
        assertThat(legs.single().tender?.giftCard).isEqualTo("SVS")

        // Local gift cards take payments only, so their value is never offered for return here.
        val local = record.copy(tenders = listOf(tender.copy(giftCard = GiftCardProvider.LOCAL.name)))
        assertThat(RefundablePayment.check(local)).isNotInstanceOf(Refundability.Refundable::class.java)
    }

    @Test
    fun `follow-ups that do not apply store nothing, and unsupported or blocked starts send nothing`() {
        simulator()
        val local = GiftCardSetting(GiftCardProvider.LOCAL, code = "genericgiftcard")
        assertThat(await { giftCards.balance(card(local)) })
            .isEqualTo(GiftCardBalanceResult.NotRead(Failure.Remote(Fault.Unsupported)))
        val done = run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        listOf(
            GiftCardRequest.Continue(done.groupId, card(), 0, "AUD"),
            GiftCardRequest.RefundFunding(done.groupId, 0, "AUD"),
            GiftCardRequest.ReturnFunds(done.groupId, 0, "AUD"),
            GiftCardRequest.UndoLoad(done.groupId, 0, "AUD"),
            GiftCardRequest.Load(card(local), 100, "AUD", GiftCardFunding.CARD),
            GiftCardRequest.CashOut(card(local), 100, "AUD"),
        ).forEach { request ->
            val id = checkNotNull(giftCards.start(request))
            await { giftCards.state.first { it == TransactionState.Finished(id) } }
            giftCards.acknowledge()
        }
        assertThat(activity(done.groupId).stages).hasSize(1)
        assertThat(await { records.recentActivities(System.currentTimeMillis()) }).hasSize(1)
        assertThat(await { giftCards.recheck(done.groupId) }).isEqualTo(Recheck.Settled)
        assertThat(await { giftCards.recheck("missing") }).isEqualTo(Recheck.Settled)
        assertThat(await { giftCards.confirmHandover(done.groupId) }).isFalse()
        giftCards.cancel()

        val blocked =
            GiftCards(
                scope = CoroutineScope(SupervisorJob()),
                gateway = container.gateway,
                records = records,
                cash = container.cashTenders,
                approval = approval,
                blocked = { SetupProblem.GIFT_CARDS },
            )
        assertThat(blocked.start(GiftCardRequest.Activate(card(), 0, "AUD", null))).isNull()
    }

    @Test
    fun `a request cancelled on the terminal is stored as cancelled`() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD", giftCards = listOf(svs)),
                simulator = it.simulator.copy(outcome = SimulatedOutcome.CANCEL),
            )
        }
        val cancelled = run(GiftCardRequest.Activate(card(), 0, "AUD", null))
        assertThat(cancelled.stages.single().status).isEqualTo(GiftCardStatus.CANCELLED)
        val refused = await { giftCards.balance(card()) } as GiftCardBalanceResult.NotRead
        assertThat(refused.cancelled).isTrue()
    }

    @Test
    fun `a stage can be aborted only once its request is being sent`() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD", giftCards = listOf(svs)),
                simulator = it.simulator.copy(delayMillis = 20_000),
            )
        }
        giftCards.cancel()
        val id = checkNotNull(giftCards.start(GiftCardRequest.Activate(card(), 0, "AUD", null)))
        await { giftCards.state.first { (it as? TransactionState.Processing)?.canCancel == true } }
        giftCards.cancel()
        assertThat((giftCards.state.value as? TransactionState.Processing)?.cancelling).isTrue()
        await { giftCards.state.first { it == TransactionState.Finished(id) } }
        assertThat(activity(id).stages.single().status).isEqualTo(GiftCardStatus.CANCELLED)
    }
}

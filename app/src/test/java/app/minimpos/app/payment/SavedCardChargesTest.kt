package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.history.StoredPayment
import app.minimpos.app.history.canCheckAgain
import app.minimpos.app.simulatedModifications
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Saved-card charges and their Checkout refunds: persisted before sending, idempotent, context- and approval-checked. */
@RunWith(RobolectricTestRunner::class)
class SavedCardChargesTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val context = PaymentContext("TERMINAL", "S1F2-1", "POS", "Merchant", "TEST")
    private var now = Instant.parse("2026-10-17T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val card = StoredCard("TOKEN1", "visa", "1111", recurringModels = setOf("UnscheduledCardOnFile"))
    private val sent = mutableListOf<Pair<CardChargeRequest, String>>()
    private var answers = ArrayDeque<CardChargeResult>()
    private var failure: RuntimeException? = null
    private val cards =
        object : SavedCardApi {
            override suspend fun list(shopperReference: String) =
                if (shopperReference == "CUST-001") StoredCardListing.Listed(listOf(card)) else StoredCardListing.Failed(Fault.TimedOut)

            override suspend fun charge(
                request: CardChargeRequest,
                idempotencyKey: String,
            ): CardChargeResult {
                failure?.let { throw it }
                sent += request to idempotencyKey
                return answers.removeFirst()
            }
        }
    private val refundsSent = mutableListOf<String>()
    private var refundAnswer: ModificationResult = ModificationResult.Received("REF1")
    private val modifications =
        object : PaymentModifications by simulatedModifications() {
            override suspend fun refund(
                paymentPspReference: String,
                amount: ModificationAmount,
                reference: String,
                idempotencyKey: String,
            ): ModificationResult {
                refundsSent += "$paymentPspReference:${amount.value}:$idempotencyKey"
                return refundAnswer
            }
        }
    private var target = ApiTarget(ApiSetup.Complete, modifications, context = context, cards = cards)
    private val manager = SessionLock { 0L }.apply { unlock() }
    private val approval = ManagerApproval({ true }, manager, { 0L })
    private val charged = mutableListOf<String>()
    private var ids = 0
    private val charges =
        SavedCardCharges(
            container.appScope,
            container.sales,
            { target },
            onCharged = { id, _ -> charged += id },
            clock = clock,
            newId = { "sale-${++ids}" },
            approval = approval,
        )
    private val accepted = mutableListOf<String>()
    private val refunds =
        CheckoutRefunds(container.appScope, container.refundRecords, container.sales, {
            target
        }, { accepted += it }, clock, { "refund-${++ids}" }, approval)

    private val operations =
        FinancialOperations(
            container.payments,
            container.refunds,
            charges,
            refunds,
            container.links,
            container.sales,
            container.refundRecords,
        )

    @After
    fun tearDown() = env.close()

    private fun start(): SavedCardStart {
        val cart = Cart().addProduct(CartProduct(1, "Flat white", null, 450, AppliedTax("GST", 10_000))) { "line" }
        val payment =
            PaymentStart(
                totals = cart.totals(TaxMode.INCLUSIVE),
                currency = CurrencySpec.of("AUD"),
                merchantReference = "261017-100000-AB12",
                customerReference = "CUST-001",
                shopperEmail = null,
                tokenization = null,
                shopperReference = "CUST-001",
            )
        return SavedCardStart(payment, card, "UnscheduledCardOnFile")
    }

    private fun charge(): String {
        val id = checkNotNull(charges.start(start()))
        await { charges.state.first { it == TransactionState.Finished(id) } }
        return id
    }

    private fun sale(id: String) = checkNotNull(await { container.sales.get(id) }).sale

    @Test
    fun `a second start while charging stores and sends nothing`() {
        answers += CardChargeResult.Authorised("PSP9")
        val first = checkNotNull(charges.start(start()))
        assertThat(charges.start(start())).isNull()
        await { charges.state.first { it == TransactionState.Finished(first) } }
        assertThat(sent).hasSize(1)
        assertThat(await { container.sales.get("sale-2") }).isNull()
    }

    @Test
    fun `cards are listed only where the Checkout API is ready`() {
        assertThat(await { charges.cards("CUST-001") }).isEqualTo(CardLookup.Listed(listOf(card)))
        assertThat(await { charges.cards("OTHER") }).isEqualTo(CardLookup.NotDone(Failure.Remote(Fault.TimedOut)))
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
        assertThat(await { charges.cards("CUST-001") }).isEqualTo(CardLookup.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
    }

    @Test
    fun `an approved charge is stored before sending and refunded through the Checkout API without a QR code`() {
        answers += CardChargeResult.Authorised("PSP9", "visa", "1111", "123456")
        val id = charge()
        val sale = sale(id)
        assertThat(sent.single().second).isEqualTo(SavedCardCharges.idempotencyKey(id))
        assertThat(sent.single().first).isEqualTo(SavedCardCharges.request(sale))
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.savedCard).isTrue()
        assertThat(sale.chargedCardId).isEqualTo("TOKEN1")
        assertThat(sale.pspReference).isEqualTo("PSP9")
        assertThat(sale.poiTransactionId).isEqualTo(".PSP9")
        assertThat(sale.maskedPan).isEqualTo("**** 1111")
        assertThat(sale.context).isEqualTo(context)
        assertThat(charged).containsExactly(id)
        // A finished charge does not block the next one.
        answers += CardChargeResult.Authorised("PSP10")
        val next = checkNotNull(charges.start(start()))
        await { charges.state.first { it == TransactionState.Finished(next) } }
        val record = checkNotNull(await { container.sales.get(id) })
        assertThat(RefundablePayment.qrCode(record)).isNull()
        val payment = (RefundablePayment.check(record) as Refundability.Refundable).payment
        val request = checkNotNull(payment.request(RefundChoice.Amount(200), "", now, ZoneOffset.UTC))
        assertThat(request.checkout).isTrue()
        val refundId = checkNotNull(refunds.start(request))
        await { refunds.state.first { it == TransactionState.Finished(refundId) } }
        val refund = checkNotNull(await { container.refundRecords.get(refundId) })
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.checkoutRefund).isTrue()
        assertThat(refund.pspReference).isEqualTo("REF1")
        assertThat(refundsSent).containsExactly("PSP9:200:${CheckoutRefunds.idempotencyKey(refundId)}")
        assertThat(accepted).containsExactly(refundId)
        assertThat(sale(id).refundedMinor).isEqualTo(200)
        // Only Checkout refunds go this way.
        assertThat(refunds.start(request.copy(checkout = false))).isNull()
    }

    @Test
    fun `refused, not taken and unknown charges settle as such and only the unknown one is sent again with its key`() {
        answers += CardChargeResult.Refused("PSP8", ExternalText("Expired Card"))
        val refused = charge()
        assertThat(sale(refused).status).isEqualTo(SaleStatus.DECLINED)
        assertThat(sale(refused).message).isEqualTo("Expired Card")
        charges.acknowledge()
        answers += CardChargeResult.Failed(Fault.AdyenRejected(422))
        val failed = charge()
        assertThat(sale(failed).status).isEqualTo(SaleStatus.FAILED)
        charges.acknowledge()
        answers += CardChargeResult.Failed(Fault.TimedOut)
        val unknown = charge()
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(sale(unknown).reason).isEqualTo(StoredReason.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        assertThat(charged).isEmpty()
        assertThat(await { charges.recheck(refused) }).isEqualTo(Recheck.Settled)
        answers += CardChargeResult.Authorised("PSP7")
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.Settled)
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sent.map { it.second }.takeLast(2)).containsExactly("card-$unknown", "card-$unknown")
        assertThat(sent.takeLast(2).map { it.first }.distinct()).hasSize(1)
        assertThat(charged).containsExactly(unknown)
    }

    @Test
    fun `a charge keeps the approval it started with when leaving the approving screen locks the session`() {
        answers += CardChargeResult.Authorised("PSP8")
        val id = checkNotNull(charges.start(start()))
        // Navigating to the waiting screen, which needs no Manager approval, locks the session straight away.
        manager.lock()
        await { charges.state.first { it == TransactionState.Finished(id) } }
        assertThat(sale(id).status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `nothing is sent without Manager approval, setup or the original context, and old unknown charges stay unknown`() {
        manager.lock()
        val refused = charge()
        assertThat(sent).isEmpty()
        assertThat(sale(refused).reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        charges.acknowledge()
        manager.unlock()
        answers += CardChargeResult.Failed(Fault.ConnectionLost)
        val unknown = charge()
        target = target.copy(context = context.copy(merchantAccount = "Other"))
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.PAYMENT_CONTEXT)))
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY), context = context)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.UNKNOWN)
        manager.lock()
        target = ApiTarget(ApiSetup.Complete, modifications, context = context, cards = cards)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        manager.unlock()
        now = now.plus(CheckoutOperations.RECHECK_WINDOW).plusSeconds(1)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(sent).hasSize(1)
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.UNKNOWN)
    }

    @Test
    fun `a selected listing cannot charge a different account and a never sent start is not left pending`() {
        val original = start().forLookup(card, context)
        target = target.copy(context = context.copy(merchantAccount = "Other"))
        val id = checkNotNull(charges.start(original))
        await { charges.state.first { it == TransactionState.Finished(id) } }
        assertThat(sent).isEmpty()
        val stored = sale(id)
        assertThat(stored.context).isEqualTo(context)
        assertThat(stored.status).isEqualTo(SaleStatus.FAILED)
        assertThat(stored.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.PAYMENT_CONTEXT)))
    }

    @Test
    fun `a Checkout refund whose answer is lost is sent again with the same key and a rejected one fails`() {
        answers += CardChargeResult.Authorised("PSP9")
        val id = charge()
        val record = checkNotNull(await { container.sales.get(id) })
        val payment = (RefundablePayment.check(record) as Refundability.Refundable).payment
        val request = checkNotNull(payment.request(RefundChoice.Everything, "", now, ZoneOffset.UTC))
        refundAnswer = ModificationResult.Failed(Fault.TimedOut)
        val refundId = checkNotNull(refunds.start(request))
        await { refunds.state.first { it == TransactionState.Finished(refundId) } }
        assertThat(checkNotNull(await { container.refundRecords.get(refundId) }).status).isEqualTo(RefundStatus.UNKNOWN)
        refunds.acknowledge()
        refundAnswer = ModificationResult.Received("REF2")
        assertThat(await { refunds.recheck(refundId) }).isEqualTo(Recheck.Settled)
        assertThat(checkNotNull(await { container.refundRecords.get(refundId) }).status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refundsSent.map { it.substringAfterLast(':') }.distinct()).containsExactly(CheckoutRefunds.idempotencyKey(refundId))
        assertThat(await { refunds.recheck(refundId) }).isEqualTo(Recheck.Settled)
        assertThat(refundsSent).hasSize(2)
    }

    @Test
    fun `recovery transport and credential failures preserve unknown charges and refunds`() {
        answers += CardChargeResult.Failed(Fault.TimedOut)
        val id = charge()
        val unknown = sale(id)
        for (fault in listOf(Fault.UnknownHost("checkout", false), Fault.Credential(ApiKey.ADYEN))) {
            answers += CardChargeResult.Failed(fault)
            assertThat(await { charges.recheck(id) }).isEqualTo(Recheck.StillUnknown(Failure.Remote(fault)))
            assertThat(sale(id)).isEqualTo(unknown)
        }
        answers += CardChargeResult.Authorised("PSP9")
        assertThat(await { charges.recheck(id) }).isEqualTo(Recheck.Settled)
        assertThat(sent.map { it.second }.distinct()).containsExactly(SavedCardCharges.idempotencyKey(id))
        val record = checkNotNull(await { container.sales.get(id) })
        val payment = (RefundablePayment.check(record) as Refundability.Refundable).payment
        val request = checkNotNull(payment.request(RefundChoice.Everything, "", now, ZoneOffset.UTC))
        refundAnswer = ModificationResult.Failed(Fault.TimedOut)
        val refundId = checkNotNull(refunds.start(request))
        await { refunds.state.first { it == TransactionState.Finished(refundId) } }
        val original = checkNotNull(await { container.refundRecords.get(refundId) })
        refundAnswer = ModificationResult.Failed(Fault.Unreachable("checkout", false))
        assertThat(
            await { refunds.recheck(refundId) },
        ).isEqualTo(Recheck.StillUnknown(Failure.Remote(Fault.Unreachable("checkout", false))))
        assertThat(await { container.refundRecords.get(refundId) }).isEqualTo(original)
        refundAnswer = ModificationResult.Received("REF")
        assertThat(await { refunds.recheck(refundId) }).isEqualTo(Recheck.Settled)
        assertThat(refundsSent.map { it.substringAfterLast(':') }.distinct()).containsExactly(CheckoutRefunds.idempotencyKey(refundId))
    }

    @Test
    fun `HTTP retry permission survives storage and blocks resending nontransient errors`() {
        for (transient in listOf(false, true)) {
            answers += CardChargeResult.Failed(Fault.AdyenUnavailable(422, "704", transientError = transient))
            val id = charge()
            val count = sent.size
            if (transient) {
                answers += CardChargeResult.Authorised("PSP-$id")
                assertThat(await { charges.recheck(id) }).isEqualTo(Recheck.Settled)
                assertThat(sent).hasSize(count + 1)
            } else {
                assertThat(
                    await { charges.recheck(id) },
                ).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.OPERATION_RECONCILIATION)))
                assertThat(sent).hasSize(count)
                assertThat(sale(id).status).isEqualTo(SaleStatus.UNKNOWN)
            }
            charges.acknowledge()
        }
    }

    @Test
    fun `unknown refunds cannot be replayed once their key reaches seven days`() {
        answers += CardChargeResult.Authorised("PSP9")
        val id = charge()
        val payment = (RefundablePayment.check(checkNotNull(await { container.sales.get(id) })) as Refundability.Refundable).payment
        val request = checkNotNull(payment.request(RefundChoice.Everything, "", now, ZoneOffset.UTC))
        refundAnswer = ModificationResult.Failed(Fault.TimedOut)
        val refundId = checkNotNull(refunds.start(request))
        await { refunds.state.first { it == TransactionState.Finished(refundId) } }
        now = now.plusSeconds(7 * 24 * 3600)
        assertThat(await { refunds.recheck(refundId) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(refundsSent).hasSize(1)
        assertThat(checkNotNull(await { container.refundRecords.get(refundId) }).status).isEqualTo(RefundStatus.UNKNOWN)
    }

    @Test
    fun `unknown charges and Checkout refunds are offered and checked again the way they were sent`() {
        answers += CardChargeResult.Failed(Fault.TimedOut)
        val unknown = charge()
        charges.acknowledge()
        val stored = checkNotNull(await { container.sales.get(unknown) })
        // A saved-card charge has no terminal ServiceID, yet it can be checked again.
        assertThat(stored.sale.serviceId).isNull()
        assertThat(StoredPayment(stored).actions).contains(PaymentAction.CHECK_AGAIN)
        answers += CardChargeResult.Authorised("PSP9")
        assertThat(await { operations.recheck(StoredTransaction.Sale(unknown)) }).isEqualTo(Recheck.Settled)
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.APPROVED)
        assertThat(StoredPayment(checkNotNull(await { container.sales.get(unknown) })).actions).doesNotContain(PaymentAction.CHECK_AGAIN)
        assertThat(sent.map { it.second }).containsExactly("card-$unknown", "card-$unknown")

        val record = checkNotNull(await { container.sales.get(unknown) })
        val payment = (RefundablePayment.check(record) as Refundability.Refundable).payment
        refundAnswer = ModificationResult.Failed(Fault.TimedOut)
        val refundId = checkNotNull(operations.refund(checkNotNull(payment.request(RefundChoice.Everything, "", now, ZoneOffset.UTC))))
        await { operations.refundProgress.first { it == TransactionState.Finished(refundId) } }
        val refund = checkNotNull(await { container.refundRecords.get(refundId) })
        assertThat(refund.serviceId).isNull()
        assertThat(refund.canCheckAgain).isTrue()
        operations.acknowledgeRefund()
        assertThat(operations.currentRefund).isEqualTo(TransactionState.Idle)
        refundAnswer = ModificationResult.Received("REF2")
        assertThat(await { operations.recheck(StoredTransaction.Refund(refundId)) }).isEqualTo(Recheck.Settled)
        assertThat(checkNotNull(await { container.refundRecords.get(refundId) }).canCheckAgain).isFalse()
        assertThat(operations.running).isFalse()
    }

    @Test
    fun `an unexpected failure while charging leaves the charge unknown rather than pending`() {
        failure = IllegalArgumentException("unexpected")
        val id = charge()
        assertThat(sale(id).status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(sale(id).reason).isEqualTo(StoredReason.Unconfirmed(Failure.Device(DeviceFault.UNEXPECTED)))
        assertThat(charged).isEmpty()
    }
}

package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
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

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val card = StoredCard("TOKEN1", "visa", "1111", recurringModels = setOf("UnscheduledCardOnFile"))
    private val sent = mutableListOf<Pair<CardChargeRequest, String>>()
    private var answers = ArrayDeque<CardChargeResult>()
    private val cards =
        object : SavedCardApi {
            override suspend fun list(shopperReference: String) =
                if (shopperReference == "CUST-001") StoredCardListing.Listed(listOf(card)) else StoredCardListing.Failed(Fault.TimedOut)

            override suspend fun charge(
                request: CardChargeRequest,
                idempotencyKey: String,
            ): CardChargeResult {
                sent += request to idempotencyKey
                return answers.removeFirst()
            }
        }
    private val refundsSent = mutableListOf<String>()
    private var refundAnswer: ModificationResult = ModificationResult.Received("REF1")
    private val modifications =
        object : PaymentModifications by app.minimpos.terminal.simulator
            .SimulatedModifications() {
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
    private var approved = true
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
            permits = { approved },
        )
    private val accepted = mutableListOf<String>()
    private val refunds =
        CheckoutRefunds(container.appScope, container.refundRecords, container.sales, {
            target
        }, { accepted += it }, clock, { "refund-${++ids}" }, { approved })

    @After
    fun tearDown() = env.close()

    private fun start(): SavedCardStart {
        val cart = Cart().addProduct(CartProduct(1, "Flat white", null, 450, AppliedTax("GST", 10_000)), { "line" })
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
        assertThat(charges.start(start())).isNotNull()
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
    fun `nothing is sent without Manager approval, setup or the original context, and old unknown charges stay unknown`() {
        approved = false
        val refused = charge()
        assertThat(sent).isEmpty()
        assertThat(sale(refused).reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        charges.acknowledge()
        approved = true
        answers += CardChargeResult.Failed(Fault.ConnectionLost)
        val unknown = charge()
        target = target.copy(context = context.copy(merchantAccount = "Other"))
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.PAYMENT_CONTEXT)))
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY), context = context)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.UNKNOWN)
        approved = false
        target = ApiTarget(ApiSetup.Complete, modifications, context = context, cards = cards)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
        approved = true
        now = now.plus(SavedCardCharges.RECHECK_WINDOW).plusSeconds(1)
        assertThat(await { charges.recheck(unknown) }).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(sent).hasSize(1)
        assertThat(sale(unknown).status).isEqualTo(SaleStatus.UNKNOWN)
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
}

package io.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.CaptureMode
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.refund.PaymentStanding
import io.minimpos.app.refund.standing
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.ApiTarget
import io.minimpos.app.terminal.TerminalSetupSource
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartProduct
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.checkout.CheckoutCredentials
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.ModificationResult
import io.minimpos.terminal.checkout.PaymentModifications
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Answers captures and adjustments as told, and records what was sent. */
private class FakeModifications : PaymentModifications {
    var captureResult: ModificationResult = ModificationResult.Received("CAP")
    var adjustResult: ModificationResult? = null
    val captures = mutableListOf<Triple<String, ModificationAmount, String>>()
    val adjustments = mutableListOf<Pair<ModificationAmount, String?>>()
    val keys = mutableListOf<String>()

    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult {
        captures += Triple(paymentPspReference, amount, reference)
        keys += idempotencyKey
        return captureResult
    }

    override suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult {
        adjustments += amount to adjustAuthorisationData
        keys += idempotencyKey
        return adjustResult
            ?: if (adjustAuthorisationData !=
                null
            ) {
                ModificationResult.Authorised("ADJ", "BQABAQnext")
            } else {
                ModificationResult.Received("ADJ")
            }
    }

    override suspend fun verify(): String? = null
}

@RunWith(RobolectricTestRunner::class)
class CapturesTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val fake = FakeModifications()

    /** Where captures go; each test sets what it needs, without touching settings or secrets. */
    private var target: ApiTarget = ApiTarget.Ready(fake)
    private val captures = Captures(container.sales) { target }

    @After
    fun tearDown() = env.close()

    private val bill =
        SaleEntity(
            id = "s1",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_818,
            taxMinor = 182,
            totalMinor = 2_000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP-1",
            pspReference = "PSP1",
            poiTransactionId = "T.PSP1",
            poiTimestamp = "2026-09-29T11:04:00.000Z",
            tipOnReceipt = true,
            adjustAuthorisationData = "BQABAQfirst",
        )

    private fun store(sale: SaleEntity = bill) =
        await {
            container.sales.createPending(
                sale,
                listOf(SaleLineEntity(0, sale.id, 0, null, "Dinner", null, 2_000, 1, "GST", 10_000, 1_818, 182, 2_000)),
            )
        }

    private fun sale(id: String = "s1") = await { container.sales.get(id)!!.sale }

    @Test
    fun `a small tip is captured with the bill without adjusting`() {
        store()
        assertThat(await { captures.addTip("s1", 300) }).isEqualTo(CaptureResult.Requested)
        val captured = sale()
        assertThat(captured.tipMinor).isEqualTo(300)
        assertThat(captured.capturedMinor).isEqualTo(2_300)
        assertThat(captured.captureStatus).isEqualTo(CaptureStatus.REQUESTED)
        assertThat(captured.authorisedMinor).isNull()
        assertThat(captured.amountMinor).isEqualTo(2_300)
        assertThat(fake.adjustments).isEmpty()
        assertThat(fake.captures.single()).isEqualTo(Triple("PSP1", ModificationAmount("AUD", 2_300), "MP-1"))
        assertThat(fake.keys.single()).isEqualTo("capture-s1-2300")
        // The tip is final.
        assertThat(await { captures.addTip("s1", 500) }).isEqualTo(CaptureResult.NotAllowed)
    }

    @Test
    fun `no tip captures the bill`() {
        store()
        assertThat(await { captures.addTip("s1", 0) }).isEqualTo(CaptureResult.Requested)
        assertThat(sale().amountMinor).isEqualTo(2_000)
        assertThat(sale().tipMinor).isEqualTo(0)
        assertThat(await { captures.addTip("missing", 0) }).isEqualTo(CaptureResult.NotAllowed)
        assertThat(await { captures.addTip("s1", -1) }).isEqualTo(CaptureResult.NotAllowed)
    }

    @Test
    fun `a large tip raises the authorisation first, synchronously with the blob`() {
        store()
        assertThat(await { captures.addTip("s1", 600) }).isEqualTo(CaptureResult.Requested)
        assertThat(fake.adjustments.single()).isEqualTo(ModificationAmount("AUD", 2_600) to "BQABAQfirst")
        val captured = sale()
        assertThat(captured.authorisedMinor).isEqualTo(2_600)
        assertThat(captured.adjustment).isEqualTo(AdjustmentStatus.AUTHORISED)
        assertThat(captured.adjustAuthorisationData).isEqualTo("BQABAQnext")
        assertThat(captured.capturedMinor).isEqualTo(2_600)
        assertThat(fake.keys).containsExactly("adjust-s1-2000-2600", "capture-s1-2600").inOrder()
    }

    @Test
    fun `a refused adjustment keeps the tip unsaved, so a smaller one can be entered`() {
        store()
        fake.adjustResult = ModificationResult.Refused("Not enough balance")
        assertThat(await { captures.addTip("s1", 1_000) }).isEqualTo(CaptureResult.Refused("Not enough balance"))
        val refused = sale()
        assertThat(refused.tipMinor).isNull()
        assertThat(refused.modificationMessage).isEqualTo("Not enough balance")
        assertThat(fake.captures).isEmpty()
        assertThat(refused.standing).isEqualTo(PaymentStanding.AWAITING_TIP)

        fake.adjustResult = ModificationResult.Unknown("timeout")
        assertThat(await { captures.addTip("s1", 1_000) }).isEqualTo(CaptureResult.Failed("timeout"))
        assertThat(sale().tipMinor).isNull()

        fake.adjustResult = null
        assertThat(await { captures.addTip("s1", 200) }).isEqualTo(CaptureResult.Requested)
        assertThat(sale().modificationMessage).isNull()
    }

    @Test
    fun `a capture Adyen did not take, or whose outcome is unknown, is sent again with the same key`() {
        store()
        fake.captureResult = ModificationResult.NotProcessed("Invalid amount (HTTP 422, code 137)")
        assertThat(await { captures.addTip("s1", 100) }).isEqualTo(CaptureResult.Failed("Invalid amount (HTTP 422, code 137)"))
        val failed = sale()
        assertThat(failed.tipMinor).isEqualTo(100)
        assertThat(failed.captureStatus).isEqualTo(CaptureStatus.FAILED)
        assertThat(failed.modificationMessage).contains("Invalid amount")
        assertThat(failed.standing).isEqualTo(PaymentStanding.CAPTURE_FAILED)

        fake.captureResult = ModificationResult.Unknown("timeout")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Failed("timeout"))
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.UNKNOWN)

        fake.captureResult = ModificationResult.Received("CAP")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Requested)
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.REQUESTED)
        assertThat(fake.keys.distinct()).containsExactly("capture-s1-2100")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.NotAllowed)
    }

    @Test
    fun `without a Checkout API the capture is recorded for the Customer Area`() {
        target = ApiTarget.CustomerArea
        store()
        assertThat(await { captures.addTip("s1", 600) }).isEqualTo(CaptureResult.Recorded)
        val recorded = sale()
        assertThat(recorded.captureStatus).isEqualTo(CaptureStatus.MANUAL)
        assertThat(recorded.capturedMinor).isEqualTo(2_600)
        assertThat(recorded.tipMinor).isEqualTo(600)
        assertThat(recorded.standing).isEqualTo(PaymentStanding.CAPTURED_MANUALLY)
        assertThat(fake.keys).isEmpty()
    }

    @Test
    fun `a partly set up API sends nothing and says what is missing`() {
        target = ApiTarget.NotSetUp("Enter the Checkout API key in Terminal settings")
        store()
        val result = await { captures.addTip("s1", 100) }
        assertThat(result).isEqualTo(CaptureResult.Failed("Enter the Checkout API key in Terminal settings"))
        assertThat(sale().tipMinor).isNull()
        assertThat(sale().modificationMessage).isEqualTo("Enter the Checkout API key in Terminal settings")
        assertThat(fake.keys).isEmpty()
    }

    @Test
    fun `pre-authorisations are captured up to what they hold directly, beyond it after adjusting`() {
        store(bill.copy(id = "p1", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION, adjustAuthorisationData = null))
        store(bill.copy(id = "p2", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION, adjustAuthorisationData = null))
        assertThat(await { captures.capture("p1", 1_500) }).isEqualTo(CaptureResult.Requested)
        assertThat(sale("p1").capturedMinor).isEqualTo(1_500)
        assertThat(fake.adjustments).isEmpty()
        assertThat(await { captures.capture("p1", 1_500) }).isEqualTo(CaptureResult.NotAllowed)

        // Without the blob the adjustment is asynchronous, and the capture follows straight away.
        assertThat(await { captures.capture("p2", 2_500) }).isEqualTo(CaptureResult.Requested)
        val p2 = sale("p2")
        assertThat(p2.adjustment).isEqualTo(AdjustmentStatus.REQUESTED)
        assertThat(p2.authorisedMinor).isEqualTo(2_500)
        assertThat(p2.capturedMinor).isEqualTo(2_500)
        assertThat(fake.adjustments.single()).isEqualTo(ModificationAmount("AUD", 2_500) to null)
        assertThat(await { captures.capture("p2", 0) }).isEqualTo(CaptureResult.NotAllowed)
    }

    @Test
    fun `adjusting what a pre-authorisation holds needs the API`() {
        store(bill.copy(id = "p1", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION))
        assertThat(await { captures.adjust("p1", 3_000) }).isEqualTo(CaptureResult.Adjusted)
        assertThat(sale("p1").heldMinor).isEqualTo(3_000)
        assertThat(sale("p1").captureStatus).isNull()
        fake.adjustResult = ModificationResult.NotProcessed("Not allowed")
        assertThat(await { captures.adjust("p1", 3_500) }).isEqualTo(CaptureResult.Failed("Not allowed"))
        assertThat(sale("p1").heldMinor).isEqualTo(3_000)
        // Adjusting a sale awaiting its tip is not offered.
        store()
        assertThat(await { captures.adjust("s1", 3_000) }).isEqualTo(CaptureResult.NotAllowed)

        // An adjustment cannot be left to the Customer Area; a capture can.
        target = ApiTarget.CustomerArea
        assertThat(await { captures.adjust("p1", 3_000) }).isEqualTo(CaptureResult.NotAllowed)
        assertThat(await { captures.capture("p1", 2_800) }).isEqualTo(CaptureResult.Recorded)
        assertThat(sale("p1").capturedMinor).isEqualTo(2_800)
    }

    @Test
    fun `captures interrupted by the app stopping become unknown at the next start`() {
        store(bill.copy(tipMinor = 100, capturedMinor = 2_100, captureStatus = CaptureStatus.PENDING))
        await { container.history.settleInterrupted("stopped", "capture stopped") }
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.UNKNOWN)
        assertThat(sale().modificationMessage).isEqualTo("capture stopped")
    }

    @Test
    fun `the API is used once anything of it is set up, and needs the environment and live prefix`() {
        val connected = mutableListOf<CheckoutCredentials>()
        val setups = TerminalSetupSource(container.settings, container.secrets, container.device)
        val live =
            AdyenApi(setups, container.secrets, simulated = fake, connect = {
                connected += it
                fake
            })
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        assertThat(await { live.target() }).isEqualTo(ApiTarget.CustomerArea)
        assertThat(await { live.verify() }).isEqualTo("Enter the merchant account and the Checkout API key first")

        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await { container.secrets.set(Secret.CHECKOUT_API_KEY, "key") }
        assertThat(await { live.verify() }).contains("Test the connection to the terminal first")
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = TerminalEnvironment.LIVE)) }
        assertThat(await { live.verify() }).isEqualTo("Enter the live URL prefix in Terminal settings")
        env.updateSettings { it.copy(terminal = it.terminal.copy(liveUrlPrefix = "abc-Company")) }
        assertThat(await { live.verify() }).isNull()
        assertThat(await { live.target() }).isEqualTo(ApiTarget.Ready(fake))
        assertThat(connected.single()).isEqualTo(CheckoutCredentials("key", "Merchant", TerminalEnvironment.LIVE, "abc-Company"))
        await { container.terminalStatus.state.first { it.captureMode == CaptureMode.API && it.apiProblem == null } }

        // A key that no longer decrypts has to be entered again.
        env.cipher.fail = true
        assertThat(await { live.verify() }).contains("could not be read")
        env.cipher.fail = false
        env.useSimulator()
        assertThat(await { live.target() }).isEqualTo(ApiTarget.Ready(fake))
    }

    @Test
    fun `a sale taken for a tip on the receipt is pre-authorised and keeps the adjustment blob`() {
        env.useSimulator()
        val totals = Cart().addProduct(CartProduct(1, "Dinner", null, 2_000, AppliedTax("GST", 10_000))) { "k" }.totals(TaxMode.INCLUSIVE)
        val start =
            PaymentStart(totals, CurrencySpec.of("AUD"), "MP-T", null, null, null, tipOnReceipt = true)
        assertThat((SaleBook(container.sales).operation(start) as TerminalOperation.Pay).params.preAuthorisation).isTrue()
        container.payments.start(start)
        val id = await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        val tipSale = sale(id)
        assertThat(tipSale.tipOnReceipt).isTrue()
        assertThat(tipSale.adjustAuthorisationData).startsWith("BQABAQ")
        assertThat(tipSale.standing).isEqualTo(PaymentStanding.AWAITING_TIP)
        // A pre-authorisation is never also taken for a tip.
        assertThrows(IllegalArgumentException::class.java) { start.copy(kind = SaleKind.PRE_AUTHORISATION) }
    }
}

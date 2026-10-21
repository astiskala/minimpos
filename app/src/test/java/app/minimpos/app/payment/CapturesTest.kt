package app.minimpos.app.payment

import app.minimpos.app.FakeLinkApi
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.AdjustmentStatus
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.standing
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.app.terminal.SetupAccessCheck
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.CheckoutApi
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Answers captures and adjustments as told, and records what was sent. */
private class FakeModifications : PaymentModifications {
    var captureResult: ModificationResult = ModificationResult.Received("CAP")
    var adjustResult: ModificationResult? = null
    val captures = mutableListOf<Triple<String, ModificationAmount, String>>()
    val adjustments = mutableListOf<Pair<ModificationAmount, String?>>()
    val keys = mutableListOf<String>()
    var beforeAnswer: suspend () -> Unit = {}

    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult {
        captures += Triple(paymentPspReference, amount, reference)
        keys += idempotencyKey
        beforeAnswer()
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
        beforeAnswer()
        return adjustResult
            ?: if (adjustAuthorisationData !=
                null
            ) {
                ModificationResult.Authorised("ADJ", "BQABAQnext")
            } else {
                ModificationResult.Received("ADJ")
            }
    }

    override suspend fun verify(): Fault? = null
}

@RunWith(RobolectricTestRunner::class)
class CapturesTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val fake = FakeModifications()

    /** Where captures go; each test sets what it needs, without touching settings or secrets. */
    private var target: ApiTarget = ApiTarget(ApiSetup.Complete, fake)
    private val captures = Captures(container.sales, { target })

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
    fun `closing the caller during adjustment still captures and records the answer without continuing the caller`() {
        store()
        await {
            coroutineScope {
                val sending = CompletableDeferred<Unit>()
                val answer = CompletableDeferred<Unit>()
                fake.beforeAnswer = {
                    sending.complete(Unit)
                    answer.await()
                }
                var continued = false
                val caller =
                    launch {
                        captures.addTip("s1", 600)
                        continued = true
                    }
                sending.await()
                caller.cancel()
                answer.complete(Unit)
                caller.join()
                val stored = container.sales.get("s1")!!.sale
                assertThat(stored.adjustment).isEqualTo(AdjustmentStatus.AUTHORISED)
                assertThat(stored.captureStatus).isEqualTo(CaptureStatus.REQUESTED)
                assertThat(stored.tipMinor).isEqualTo(600)
                assertThat(fake.adjustments).hasSize(1)
                assertThat(fake.captures).hasSize(1)
                assertThat(fake.keys.last()).isEqualTo("capture-s1-2600")
                assertThat(continued).isFalse()
            }
        }
    }

    @Test
    fun `cancelling a queued capture never commits it`() {
        store()
        store(bill.copy(id = "s2"))
        await {
            coroutineScope {
                val sending = CompletableDeferred<Unit>()
                val answer = CompletableDeferred<Unit>()
                fake.beforeAnswer = {
                    sending.complete(Unit)
                    answer.await()
                }
                val first = launch { captures.addTip("s1", 0) }
                sending.await()
                val queued = launch(start = CoroutineStart.UNDISPATCHED) { captures.addTip("s2", 0) }
                queued.cancelAndJoin()
                answer.complete(Unit)
                first.join()
                assertThat(fake.captures).hasSize(1)
                val untouched = container.sales.get("s2")!!.sale
                assertThat(untouched.tipMinor).isNull()
                assertThat(untouched.captureStatus).isEqualTo(bill.captureStatus)
            }
        }
    }

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
        assertThat(fake.keys.first()).startsWith("adjust-")
        assertThat(fake.keys.last()).isEqualTo("capture-s1-2600")
    }

    @Test
    fun `a refused adjustment keeps the tip unsaved, so a smaller one can be entered`() {
        store()
        fake.adjustResult = ModificationResult.Refused(ExternalText("Not enough balance"))
        assertThat(await { captures.addTip("s1", 1_000) }).isEqualTo(CaptureResult.Refused(ExternalText("Not enough balance")))
        val refused = sale()
        assertThat(refused.tipMinor).isNull()
        assertThat(refused.modificationMessage).isEqualTo("Not enough balance")
        assertThat(fake.captures).isEmpty()
        assertThat(refused.standing).isEqualTo(PaymentStanding.AWAITING_TIP)

        fake.adjustResult = ModificationResult.Failed(Fault.TimedOut)
        assertThat(await { captures.addTip("s1", 1_000) }).isEqualTo(CaptureResult.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        assertThat(sale().tipMinor).isNull()

        fake.adjustResult = null
        assertThat(await { captures.addTip("s1", 200) }).isEqualTo(CaptureResult.NotAllowed)
        assertThat(sale().adjustmentPending).isTrue()
        assertThat(await { captures.addTip("s1", 1_000) }).isEqualTo(CaptureResult.Requested)
        assertThat(sale().modificationMessage).isNull()
    }

    @Test
    fun `a capture Adyen did not take, or whose outcome is unknown, is sent again with the same key`() {
        store()
        fake.captureResult = ModificationResult.Failed(Fault.AdyenRejected(422, null, ExternalText("Invalid amount (HTTP 422, code 137)")))
        val invalid = Fault.AdyenRejected(422, null, ExternalText("Invalid amount (HTTP 422, code 137)"))
        assertThat(await { captures.addTip("s1", 100) }).isEqualTo(CaptureResult.NotDone(Failure.Remote(invalid)))
        val failed = sale()
        assertThat(failed.tipMinor).isEqualTo(100)
        assertThat(failed.captureStatus).isEqualTo(CaptureStatus.FAILED)
        assertThat(failed.modificationReason).isEqualTo(StoredReason.NotDone(Failure.Remote(invalid)))
        assertThat(failed.standing).isEqualTo(PaymentStanding.CAPTURE_FAILED)

        fake.captureResult = ModificationResult.Failed(Fault.TimedOut)
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.UNKNOWN)

        fake.captureResult = ModificationResult.Received("CAP")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Requested)
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.REQUESTED)
        assertThat(fake.keys.distinct()).containsExactly("capture-s1-2100")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.NotAllowed)
    }

    @Test
    fun `an API not set up sends nothing and says what is missing`() {
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
        store()
        val result = await { captures.addTip("s1", 100) }
        assertThat(result).isEqualTo(CaptureResult.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale().tipMinor).isNull()
        assertThat(sale().modificationReason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale().modificationMessage).isNull()
        assertThat(fake.keys).isEmpty()
        // Nothing of it entered is no longer left to the Customer Area either.
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.MERCHANT_ACCOUNT))
        assertThat(await { captures.addTip("s1", 600) }).isEqualTo(CaptureResult.NotDone(Failure.NotSetUp(SetupProblem.MERCHANT_ACCOUNT)))
        assertThat(sale().captureStatus).isNull()
        assertThat(sale().standing).isEqualTo(PaymentStanding.AWAITING_TIP)
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
    fun `adjusting what a pre-authorisation holds goes through the API`() {
        store(bill.copy(id = "p1", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION))
        assertThat(await { captures.adjust("p1", 3_000) }).isEqualTo(CaptureResult.Adjusted)
        assertThat(sale("p1").heldMinor).isEqualTo(3_000)
        assertThat(sale("p1").captureStatus).isNull()
        fake.adjustResult = ModificationResult.Failed(Fault.AdyenRejected(422, null, ExternalText("Not allowed")))
        assertThat(await { captures.adjust("p1", 3_500) })
            .isEqualTo(CaptureResult.NotDone(Failure.Remote(Fault.AdyenRejected(422, null, ExternalText("Not allowed")))))
        assertThat(sale("p1").heldMinor).isEqualTo(3_000)
        // Adjusting a sale awaiting its tip is not offered.
        store()
        assertThat(await { captures.adjust("s1", 3_000) }).isEqualTo(CaptureResult.NotAllowed)

        // Without the API neither an adjustment nor a capture is made.
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
        assertThat(await { captures.adjust("p1", 3_500) }).isEqualTo(CaptureResult.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(await { captures.capture("p1", 2_800) }).isEqualTo(CaptureResult.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale("p1").capturedMinor).isNull()
    }

    @Test
    fun `a different payment context sends nothing and leaves the capture record unchanged`() {
        val context = PaymentContext("TERMINAL", "AMS1-1", "POS", "Merchant", "TEST")
        store(bill.copy(context = context))
        target = ApiTarget(ApiSetup.Complete, fake, context = context.copy(merchantAccount = "Other"))
        assertThat(await { captures.addTip("s1", 100) }).isEqualTo(CaptureResult.NotDone(Failure.NotSetUp(SetupProblem.PAYMENT_CONTEXT)))
        assertThat(fake.captures).isEmpty()
        assertThat(fake.adjustments).isEmpty()
        assertThat(sale()).isEqualTo(bill.copy(context = context))
    }

    @Test
    fun `captures interrupted by the app stopping become unknown at the next start`() {
        store(bill.copy(tipMinor = 100, capturedMinor = 2_100, captureStatus = CaptureStatus.PENDING))
        await { container.history.settleInterrupted() }
        assertThat(sale().captureStatus).isEqualTo(CaptureStatus.UNKNOWN)
        assertThat(sale().modificationReason).isEqualTo(StoredReason.Interrupted)
    }

    @Test
    fun `failed recovery preserves capture and adjustment identities until a definitive answer`() {
        store()
        fake.captureResult = ModificationResult.Failed(Fault.TimedOut)
        await { captures.addTip("s1", 100) }
        val capture = sale()
        target = ApiTarget(ApiSetup.Incomplete(SetupProblem.API_KEY))
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.API_KEY)))
        assertThat(sale()).isEqualTo(capture)
        target = ApiTarget(ApiSetup.Complete, fake)
        fake.captureResult = ModificationResult.Failed(Fault.UnknownHost("checkout", false))
        assertThat(
            await { captures.retryCapture("s1") },
        ).isEqualTo(CaptureResult.Unconfirmed(Failure.Remote(Fault.UnknownHost("checkout", false))))
        assertThat(sale()).isEqualTo(capture)
        fake.captureResult = ModificationResult.Received("CAP")
        assertThat(await { captures.retryCapture("s1") }).isEqualTo(CaptureResult.Requested)
        assertThat(fake.keys.distinct()).containsExactly("capture-s1-2100")

        store(bill.copy(id = "p", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION))
        fake.adjustResult = ModificationResult.Failed(Fault.TimedOut)
        await { captures.adjust("p", 3000) }
        val adjustment = sale("p")
        fake.adjustResult = ModificationResult.Failed(Fault.Credential(ApiKey.ADYEN))
        assertThat(await { captures.adjust("p", 3000) }).isInstanceOf(CaptureResult.Unconfirmed::class.java)
        assertThat(sale("p")).isEqualTo(adjustment)
        assertThat(await { captures.capture("p", 1500) }).isEqualTo(CaptureResult.NotAllowed)
        fake.adjustResult = ModificationResult.Authorised("ADJ", "new-blob")
        assertThat(await { captures.adjust("p", 3000) }).isEqualTo(CaptureResult.Adjusted)
        assertThat(fake.keys.takeLast(3).distinct()).containsExactly(adjustment.adjustmentKey)
        assertThat(await { captures.adjust("p", 3000) }).isEqualTo(CaptureResult.Adjusted)
        assertThat(fake.keys.last()).isNotEqualTo(adjustment.adjustmentKey)
    }

    @Test
    fun `capture and adjustment retries stop at seven days and cannot renew their first send times`() {
        val instant = Instant.parse("2026-10-21T00:00:00Z")
        val first = Captures(container.sales, { target }, clock = Clock.fixed(instant, ZoneOffset.UTC))
        store()
        store(bill.copy(id = "p", tipOnReceipt = false, kind = SaleKind.PRE_AUTHORISATION))
        fake.captureResult = ModificationResult.Failed(Fault.TimedOut)
        fake.adjustResult = ModificationResult.Failed(Fault.TimedOut)
        await { first.addTip("s1", 100) }
        await { first.adjust("p", 3000) }
        val capture = sale()
        val adjustment = sale("p")
        val beforeExpiry =
            Captures(container.sales, { target }, clock = Clock.fixed(instant.plus(7, ChronoUnit.DAYS).minusMillis(1), ZoneOffset.UTC))
        await { beforeExpiry.retryCapture("s1") }
        await { beforeExpiry.adjust("p", 3000) }
        assertThat(sale().captureStartedAt).isEqualTo(instant.toEpochMilli())
        assertThat(sale("p").adjustmentStartedAt).isEqualTo(instant.toEpochMilli())
        val expired = Captures(container.sales, { target }, clock = Clock.fixed(instant.plus(7, ChronoUnit.DAYS), ZoneOffset.UTC))
        val count = fake.keys.size
        assertThat(await { expired.retryCapture("s1") }).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(await { expired.adjust("p", 3000) }).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(fake.keys).hasSize(count)
        assertThat(sale()).isEqualTo(capture)
        assertThat(sale("p")).isEqualTo(adjustment)
    }

    @Test
    fun `legacy unresolved modifications and nontransient HTTP errors cannot be replayed`() {
        store(bill.copy(captureStatus = CaptureStatus.UNKNOWN, capturedMinor = 2000))
        store(
            bill.copy(
                id = "p",
                tipOnReceipt = false,
                kind = SaleKind.PRE_AUTHORISATION,
                adjustmentPending = true,
                adjustmentKey = "legacy",
                adjustmentAmountMinor = 3000,
            ),
        )
        assertThat(
            await { captures.retryCapture("s1") },
        ).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(await { captures.adjust("p", 3000) }).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD)))
        assertThat(fake.keys).isEmpty()
        store(bill.copy(id = "http"))
        fake.captureResult = ModificationResult.Failed(Fault.AdyenUnavailable(409, "704"))
        await { captures.addTip("http", 100) }
        assertThat(
            await {
                captures.retryCapture("http")
            },
        ).isEqualTo(CaptureResult.Unconfirmed(Failure.NotSetUp(SetupProblem.OPERATION_RECONCILIATION)))
        assertThat(fake.keys).hasSize(1)
    }

    @Test
    fun `the API needs the merchant account, key, environment and live prefix`() {
        val connected = mutableListOf<CheckoutCredentials>()
        val links = FakeLinkApi()
        val cards =
            object : SavedCardApi {
                override suspend fun list(shopperReference: String) = StoredCardListing.Failed(Fault.Unsupported)

                override suspend fun charge(
                    request: CardChargeRequest,
                    idempotencyKey: String,
                ) = CardChargeResult.Failed(Fault.Unsupported)
            }
        val setups = TerminalSetupSource(container.settings, container.secrets, container.device)
        val live =
            AdyenApi(setups, simulated = CheckoutApi.of(fake, links, cards), connect = {
                connected += it
                CheckoutApi.of(fake, links, cards)
            }, verifyAccess = { SetupAccessCheck() })
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        assertThat(await { live.target() }.modifications(null)).isEqualTo(ApiAccess.Unavailable(SetupProblem.MERCHANT_ACCOUNT))
        assertThat(await { live.verify() }).isEqualTo(ApiCheck.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))

        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(await { live.verify() }).isEqualTo(ApiCheck.NotSetUp(SetupProblem.ENVIRONMENT))
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = TerminalEnvironment.LIVE)) }
        assertThat(await { live.verify() }).isEqualTo(ApiCheck.NotSetUp(SetupProblem.LIVE_PREFIX))
        env.updateSettings { it.copy(terminal = it.terminal.copy(liveUrlPrefix = "abc-Company")) }
        assertThat(await { live.verify() }).isEqualTo(ApiCheck.Works)
        assertThat(await { live.target() }).isEqualTo(
            ApiTarget(
                ApiSetup.Complete,
                fake,
                links,
                PaymentContext("TERMINAL", "", "MiniMPOS", "Merchant", "LIVE"),
                cards,
            ),
        )
        assertThat(connected.single()).isEqualTo(CheckoutCredentials("key", "Merchant", TerminalEnvironment.LIVE, "abc-Company"))
        await { container.terminalStatus.state.first { it.apiSetup == ApiSetup.Complete && it.apiProblem == null } }

        // A key that no longer decrypts has to be entered again.
        env.cipher.fail = true
        assertThat(await { live.verify() }).isEqualTo(ApiCheck.NotSetUp(SetupProblem.UNREADABLE_API_KEY))
        env.cipher.fail = false
        env.useSimulator()
        assertThat(await { live.target() }).isEqualTo(
            ApiTarget(
                ApiSetup.Simulated,
                fake,
                links,
                context = PaymentContext("SIMULATOR", "SIMULATOR-000000001", "MiniMPOS", "Merchant", null, simulated = true),
                cards = cards,
            ),
        )
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

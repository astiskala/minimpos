package io.github.astiskala.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.db.StoredReason
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.ReceiptLinesJson
import io.github.astiskala.minimpos.app.data.repo.RefundedLine
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.payment.StoredTransaction
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.RefundStart
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.app.refund.actions
import io.github.astiskala.minimpos.app.refund.standing
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.cart.Cart
import io.github.astiskala.minimpos.core.cart.CartProduct
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.terminal.client.RecurringModel
import io.github.astiskala.minimpos.terminal.client.RefundParams
import io.github.astiskala.minimpos.terminal.simulator.SimulatedOutcome
import io.github.astiskala.minimpos.terminal.simulator.TerminalSimulator
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/** Payments and refunds share one lifecycle; these tests run it through the simulator and an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class TransactionLifecycleTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val payments = container.payments
    private val refunds = container.refunds
    private val gst = AppliedTax("GST", 10_000)

    @After
    fun tearDown() = env.close()

    private fun start(
        tokenize: Boolean = false,
        email: String? = null,
    ): String {
        val cart =
            Cart()
                .addProduct(CartProduct(1, "Latte", null, 450, gst)) { "a" }
                .addProduct(CartProduct(1, "Latte", null, 450, gst)) { "b" }
                .addCustom("Custom", 300, AppliedTax("Free", 0), "c")
        return payments.start(
            PaymentStart(
                totals = cart.totals(TaxMode.INCLUSIVE),
                currency = CurrencySpec("AUD", 2),
                merchantReference = "MP-1",
                customerReference = "CUST-1",
                shopperEmail = email,
                tokenization = TokenizationRequest(RecurringModel.CARD_ON_FILE).takeIf { tokenize },
                shopperReference = "CUST-1",
            ),
        )
    }

    private fun finished(saleId: String) =
        await {
            payments.state.first { it == TransactionState.Finished(saleId) }
            container.sales.get(saleId)!!
        }

    /** Automatic printing on, so [ReceiptDelivery.automation] shows whether a transaction armed it. */
    private fun useSimulatorWithAutoPrint(outcome: SimulatedOutcome = SimulatedOutcome.APPROVE) {
        env.useSimulator {
            it.copy(receipt = it.receipt.copy(autoPrint = true), simulator = it.simulator.copy(outcome = outcome))
        }
        await { container.terminalStatus.state.first { it.printerAvailable } }
    }

    @Test
    fun `a configured Manager PIN also blocks direct refunds until approved`() {
        env.useSimulator()
        await { container.managerPin.setPin("2468") }
        val id = refunds.start(refundStart(null, 100, full = false))
        await { refunds.state.first { it == TransactionState.Finished(id) } }
        val record = await { container.refundRecords.get(id)!! }
        assertThat(record.status).isEqualTo(RefundStatus.FAILED)
        assertThat(record.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.MANAGER_APPROVAL))
    }

    @Test
    fun `approved payments store card, receipts and token, and arm the automatic receipt once`() {
        useSimulatorWithAutoPrint()
        val saleId = start(tokenize = true, email = "a@b.co")
        val record = finished(saleId)
        val sale = record.sale
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.totalMinor).isEqualTo(1_200)
        assertThat(sale.taxMinor).isEqualTo(82)
        assertThat(sale.poiId).isEqualTo("SIMULATOR-000000001")
        assertThat(sale.serviceId).matches("[0-9A-Z]{10}")
        assertThat(sale.pspReference).isNotNull()
        assertThat(sale.storedPaymentMethodId).isNotNull()
        assertThat(sale.shopperReference).isEqualTo("CUST-1")
        assertThat(sale.message).isNull()
        assertThat(ReceiptLinesJson.decode(sale.customerReceiptJson).map { it.name }).contains("APPROVED")
        assertThat(record.sortedLines.map { it.name to it.quantity }).containsExactly("Latte" to 2, "Custom" to 1).inOrder()
        assertThat(await { container.receipts.automation(StoredTransaction.Sale(saleId)) }.print).isTrue()
        assertThat(await { container.receipts.automation(StoredTransaction.Sale(saleId)) }).isEqualTo(AutoDelivery())
        payments.acknowledge()
        assertThat(payments.state.value).isEqualTo(TransactionState.Idle)
    }

    @Test
    fun `shopper details go with every payment, and only saving the card adds the recurring model`() {
        env.useSimulator()
        val plain = finished(start(email = "a@b.co")).sale
        assertThat(plain.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(plain.shopperReference).isEqualTo("CUST-1")
        assertThat(plain.tokenizationRequested).isFalse()
        assertThat(plain.storedPaymentMethodId).isNull()
        payments.acknowledge()

        val book = SaleBook(container.sales)
        val totals = Cart().addProduct(CartProduct(1, "Latte", null, 450, gst)) { "a" }.totals(TaxMode.INCLUSIVE)
        val start = PaymentStart(totals, CurrencySpec("AUD", 2), "MP-3", "CUST-1", "a@b.co", null, shopperReference = "CUST-1")
        val params = (book.operation(start) as TerminalOperation.Pay).params
        assertThat(params.shopperReference).isEqualTo("CUST-1")
        assertThat(params.recurringProcessingModel).isNull()
        assertThat(params.shopperEmail).isEqualTo("a@b.co")
        assertThat(params.merchantReference).isEqualTo("MP-3")
        assertThat(params.metadata).containsEntry("customerReference", "CUST-1")
        assertThat(params.requestCardAlias).isFalse()
        val saving = TokenizationRequest(RecurringModel.CARD_ON_FILE)
        val saved = (book.operation(start.copy(tokenization = saving)) as TerminalOperation.Pay).params
        assertThat(saved.shopperReference).isEqualTo("CUST-1")
        assertThat(saved.recurringProcessingModel).isEqualTo(RecurringModel.CARD_ON_FILE)
        assertThat(saved.shopperEmail).isEqualTo("a@b.co")
        assertThat(saved.requestCardAlias).isTrue()
        assertThrows(IllegalArgumentException::class.java) { start.copy(tokenization = saving, shopperReference = null) }
    }

    @Test
    fun `declines and cancellations are recorded and deliver nothing automatically`() {
        useSimulatorWithAutoPrint(SimulatedOutcome.DECLINE)
        val declined = finished(start()).sale
        assertThat(declined.status).isEqualTo(SaleStatus.DECLINED)
        assertThat(declined.message).isEqualTo("Not enough balance")
        assertThat(declined.errorCondition).isEqualTo("Refusal")
        assertThat(declined.refusalReason).isEqualTo("Not enough balance")
        assertThat(await { container.receipts.automation(StoredTransaction.Sale(declined.id)) }.print).isFalse()
        assertThat(payments.busyServiceId(declined.id)).isNull()
        payments.acknowledge()

        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.CANCEL)) }
        assertThat(finished(start()).sale.status).isEqualTo(SaleStatus.CANCELLED)
    }

    @Test
    fun `a busy terminal's own transaction can be cancelled once`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.BUSY)) }
        val sale = finished(start()).sale
        assertThat(sale.status).isEqualTo(SaleStatus.DECLINED)
        assertThat(sale.errorCondition).isEqualTo("Busy")
        assertThat(payments.busyServiceId(sale.id)).isEqualTo(TerminalSimulator.BUSY_SERVICE_ID)
        assertThat(await { payments.abortBusyTransaction(sale.id) }).isTrue()
        assertThat(payments.busyServiceId(sale.id)).isNull()
        assertThat(await { payments.abortBusyTransaction(sale.id) }).isFalse()
    }

    @Test
    fun `payments can be aborted while waiting`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(delayMillis = 20_000)) }
        payments.cancel()
        val saleId = start()
        await { payments.state.first { (it as? TransactionState.Processing)?.serviceId != null } }
        assertThrows(IllegalStateException::class.java) { start() }
        payments.cancel()
        assertThat((payments.state.value as? TransactionState.Processing)?.cancelling).isTrue()
        assertThat(finished(saleId).sale.status).isEqualTo(SaleStatus.CANCELLED)
    }

    @Test
    fun `missing terminal configuration fails without charging`() {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, poiIdOverride = "S1F2-000158213605014", host = "10.0.0.2"))
        }
        val sale = finished(start()).sale
        assertThat(sale.status).isEqualTo(SaleStatus.FAILED)
        assertThat(sale.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.KEY_IDENTIFIER))
        assertThat(sale.poiId).isNull()
        await { assertThat(payments.recheck(sale.id)).isFalse() }
        await { assertThat(payments.recheck("missing")).isFalse() }
    }

    @Test
    fun `unknown payment outcomes can be rechecked`() {
        env.useSimulator()
        val saleId = start()
        val record = finished(saleId)
        await { container.database.saleDao().update(record.sale.copy(status = SaleStatus.UNKNOWN, pspReference = null)) }
        await { assertThat(payments.recheck(saleId)).isTrue() }
        val settled = await { container.sales.get(saleId)!!.sale }
        assertThat(settled.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(settled.pspReference).isNotNull()
        // Only unknown outcomes are checked again.
        await { assertThat(payments.recheck(saleId)).isFalse() }

        // Without complete setup the terminal cannot be asked, so the outcome stays unknown.
        await { container.database.saleDao().update(settled.copy(status = SaleStatus.UNKNOWN)) }
        env.useSimulator { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, poiIdOverride = "X-000000001")) }
        await { assertThat(payments.recheck(saleId)).isFalse() }
        assertThat(
            await {
                container.sales
                    .get(saleId)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.UNKNOWN)
    }

    private fun refundStart(
        saleId: String?,
        amount: Long,
        full: Boolean,
        lines: List<RefundedLine> = emptyList(),
        timestamp: String = "2026-01-01T00:00:00.000Z",
    ) = RefundStart(saleId, "T.PSP", timestamp, "MP-1", "AUD", amount, full, "MP-R-1", lines)

    private fun refundFinished(id: String) =
        await {
            refunds.state.first { it == TransactionState.Finished(id) }
            container.refundRecords.get(id)!!
        }

    @Test
    fun `refunds are requested and recorded against the sale`() {
        useSimulatorWithAutoPrint()
        val saleId = start()
        val record = finished(saleId)
        payments.acknowledge()
        val line = record.sortedLines.first()
        val partial =
            refundFinished(
                refunds.start(refundStart(saleId, 450, full = false, lines = listOf(RefundedLine(line.id, "Latte", 1, 450, 450)))),
            )
        assertThat(partial.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(partial.pspReference).isNotNull()
        assertThat(partial.serviceId).matches("[0-9A-Z]{10}")
        assertThat(ReceiptLinesJson.decode(partial.customerReceiptJson).map { it.name }).contains("REFUND REQUESTED")
        assertThat(await { container.receipts.automation(StoredTransaction.Refund(partial.id)) }.print).isTrue()
        assertThat(await { container.receipts.automation(StoredTransaction.Refund(partial.id)) }.print).isFalse()
        refunds.acknowledge()
        assertThat(refunds.state.value).isEqualTo(TransactionState.Idle)
        val sale = await { container.sales.get(saleId)!! }
        assertThat(sale.sale.refundedMinor).isEqualTo(450)
        assertThat(sale.sortedLines.first().refundedQuantity).isEqualTo(1)

        val full = refundFinished(refunds.start(refundStart(null, 1_200, full = true)))
        assertThat(full.status).isEqualTo(RefundStatus.REQUESTED)
    }

    @Test
    fun `unknown refund outcomes can be rechecked, and count once they are settled`() {
        env.useSimulator()
        val saleId = start()
        finished(saleId)
        payments.acknowledge()
        // The terminal handled this reversal, but its answer never reached the app.
        await { container.gateway.refund(RefundParams("T.PSP", "2026-01-01T00:00:00.000Z", "MP-R-1"), "LOST1") }
        val refund =
            RefundEntity(
                id = "r1",
                saleId = saleId,
                createdAt = 0,
                merchantReference = "MP-R-1",
                originalTransactionId = "T.PSP",
                originalTimestamp = "2026-01-01T00:00:00.000Z",
                originalReference = "MP-1",
                currency = "AUD",
                amountMinor = 1_200,
                full = true,
                status = RefundStatus.UNKNOWN,
                serviceId = "LOST1",
            )
        await { container.refundRecords.create(refund) }
        assertThat(await { refunds.recheck("r1") }).isTrue()
        val settled = await { container.refundRecords.get("r1")!! }
        assertThat(settled.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(settled.pspReference).isNotNull()
        assertThat(
            await {
                container.sales
                    .get(saleId)!!
                    .sale.refundedMinor
            },
        ).isEqualTo(1_200)
        // Settled refunds are not checked again, so they are never counted twice.
        assertThat(await { refunds.recheck("r1") }).isFalse()

        await { container.refundRecords.create(refund.copy(id = "r2", serviceId = "NEVER")) }
        assertThat(await { refunds.recheck("r2") }).isTrue()
        assertThat(await { container.refundRecords.get("r2")!!.status }).isEqualTo(RefundStatus.FAILED)
    }

    @Test
    fun `refunds without configuration fail, and unexpected errors leave them unknown`() {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.TERMINAL,
                        poiIdOverride = "S1F2-000158213605014",
                        host = "10.0.0.2",
                        keyIdentifier = "k",
                    ),
            )
        }
        val refund = refundFinished(refunds.start(refundStart(null, 100, full = false)))
        assertThat(refund.status).isEqualTo(RefundStatus.FAILED)
        assertThat(refund.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.PASSPHRASE))
        assertThat(await { container.receipts.automation(StoredTransaction.Refund(refund.id)) }).isEqualTo(AutoDelivery())
        refunds.acknowledge()

        // A time stamp the terminal client cannot send makes the request fail inside the job.
        env.useSimulator()
        val broken = refundFinished(refunds.start(refundStart(null, 100, full = false, timestamp = "not a time")))
        assertThat(broken.status).isEqualTo(RefundStatus.UNKNOWN)
        assertThat(broken.message).isNotEmpty()
        assertThat(broken.reason).isEqualTo(StoredReason.OutcomeUnknown)
        assertThrows(IllegalArgumentException::class.java) { refunds.start(refundStart(null, 0, full = true)) }
    }

    @Test
    fun `pre-authorisations are stored as such and can be cancelled through the refund lifecycle`() {
        useSimulatorWithAutoPrint()
        val cart = Cart().addProduct(CartProduct(7, "Catering deposit", null, 20_000, gst)) { "a" }
        val saleId =
            payments.start(
                PaymentStart(
                    totals = cart.totals(TaxMode.INCLUSIVE),
                    currency = CurrencySpec("AUD", 2),
                    merchantReference = "MP-2",
                    customerReference = null,
                    shopperEmail = null,
                    tokenization = null,
                    kind = SaleKind.PRE_AUTHORISATION,
                ),
            )
        val record = finished(saleId)
        assertThat(record.sale.kind).isEqualTo(SaleKind.PRE_AUTHORISATION)
        assertThat(record.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(ReceiptLinesJson.decode(record.sale.customerReceiptJson).map { it.name }).contains("Pre-authorization")
        payments.acknowledge()

        val cancellation = refundFinished(refunds.start(RefundablePayment.cancellation(record, "", Instant.now(), ZoneOffset.UTC)!!))
        assertThat(cancellation.cancellation).isTrue()
        assertThat(cancellation.full).isTrue()
        assertThat(cancellation.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(ReceiptLinesJson.decode(cancellation.customerReceiptJson).map { it.name }).contains("CANCELLATION REQUESTED")
        // A cancellation refunds nothing: the sale records the cancelled hold instead.
        val cancelled = await { container.sales.get(saleId)!! }
        assertThat(cancelled.sale.refundedMinor).isEqualTo(0)
        assertThat(cancelled.sale.standing).isEqualTo(PaymentStanding.HOLD_CANCELLED)
        assertThat(cancelled.actions).isEmpty()
    }

    @Test
    fun `a single-item session holds one item at a time`() {
        var keys = 0
        val session = SaleSession(SaleKind.PRE_AUTHORISATION) { "k${++keys}" }
        val tax = TaxRateEntity(1, "GST", 10_000)
        session.addProduct(ProductEntity(5, "Room", 15_000, 1), tax)
        session.addProduct(ProductEntity(5, "Room", 15_000, 1), tax)
        assertThat(
            session.cart.value.lines
                .map { it.name to it.quantity },
        ).containsExactly("Room" to 1)
        session.addProduct(ProductEntity(6, "Hire bond", 5_000, 1), tax)
        assertThat(
            session.cart.value.lines
                .map { it.name },
        ).containsExactly("Hire bond")
        session.setQuantity(
            session.cart.value.lines
                .single()
                .key,
            3,
        )
        assertThat(
            session.cart.value.lines
                .single()
                .quantity,
        ).isEqualTo(1)
        session.addCustom("Deposit", 2_500, tax)
        assertThat(
            session.cart.value.lines
                .map { it.name to it.unitPrice },
        ).containsExactly("Deposit" to 2_500L)
        assertThat(SaleSession().kind.singleItem).isFalse()
    }

    @Test
    fun `sale session builds the cart and checkout form`() {
        val session = SaleSession { "key" }
        val tax = TaxRateEntity(1, "GST", 10_000)
        session.addProduct(ProductEntity(5, "Tea", 400, 1), tax)
        session.addCustom("Gift", 1_000, tax)
        session.setQuantity("key", 3)
        session.updateForm { it.copy(email = "a@b.co") }
        assertThat(
            session.cart.value.lines
                .first()
                .quantity,
        ).isEqualTo(3)
        session.remove("key")
        assertThat(session.cart.value.lines).isEmpty()
        assertThat(session.form.value.email).isEqualTo("a@b.co")
        session.clear()
        assertThat(session.form.value).isEqualTo(CheckoutForm())
    }
}

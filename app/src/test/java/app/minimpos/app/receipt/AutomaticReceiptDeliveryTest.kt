package app.minimpos.app.receipt

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.payment.AutoDelivery
import app.minimpos.app.payment.StoredTransaction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticReceiptDeliveryTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val env = TestEnvironment(dispatcher = dispatcher)
    private val container = env.container
    private val transaction = StoredTransaction.Sale("s1")
    private val receipts = container.receipts

    @Before
    fun ready() {
        env.useSimulator {
            it.copy(
                receipt = it.receipt.copy(autoPrint = true),
                payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = true),
                email = it.email.copy(host = "smtp", fromAddress = "shop@example.com"),
            )
        }
        await { container.terminalStatus.state.first { it.printerAvailable } }
        await {
            container.sales.createPending(
                SaleEntity(
                    id = "s1",
                    createdAt = 1_790_679_835_000,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1_200,
                    taxMinor = 0,
                    totalMinor = 1_200,
                    status = SaleStatus.APPROVED,
                    merchantReference = "MP-1",
                    shopperEmail = "a@b.co",
                ),
                emptyList(),
            )
        }
    }

    @Test
    fun `automatic delivery runs once per armed transaction as the settings say`() =
        runTest(dispatcher) {
            assertThat(automatic(transaction)).isEqualTo(AutoDelivery(complete = true))
            receipts.arm("s1")
            val delivered = automatic(transaction)
            assertThat(delivered.printed?.result).isEqualTo(ActionResult.Success)
            assertThat(delivered.emailTo).isEqualTo("a@b.co")
            assertThat(delivered.emailed).isEqualTo(ActionResult.Success)
            assertThat(automatic(transaction)).isEqualTo(AutoDelivery(complete = true))
            assertThat(env.mail.sent).hasSize(1)

            container.settings.update { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.AFTER_PAYMENT)) }
            receipts.arm("s1")
            val printOnly = automatic(transaction)
            assertThat(printOnly.printed?.result).isEqualTo(ActionResult.Success)
            assertThat(printOnly.emailTo).isNull()
            assertThat(printOnly.emailed).isNull()
            container.settings.update {
                it.copy(
                    payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = false),
                    receipt = it.receipt.copy(autoPrint = false),
                )
            }
            receipts.arm("s1")
            assertThat(automatic(transaction)).isEqualTo(AutoDelivery(complete = true))

            container.settings.update {
                it.copy(receipt = it.receipt.copy(autoPrint = true), simulator = it.simulator.copy(hasPrinter = false))
            }
            container.terminalStatus.state.first { !it.printerAvailable }
            receipts.arm("r1")
            assertThat(automatic(StoredTransaction.Refund("r1"))).isEqualTo(AutoDelivery(complete = true))
            receipts.arm("missing")
            assertThat(automatic(StoredTransaction.Sale("missing"))).isEqualTo(AutoDelivery(complete = true))
        }

    @Test
    fun `claimed delivery finishes after its screen closes and competing screens cannot dispatch again`() =
        runTest(dispatcher) {
            receipts.arm("s1")
            lateinit var progress: StateFlow<AutoDelivery>
            val screen = CoroutineScope(Job() + dispatcher)
            screen.launch(start = CoroutineStart.UNDISPATCHED) {
                progress = receipts.automation(transaction)
                progress.collect {}
            }
            screen.cancel()
            val competing = receipts.automation(transaction)
            val delivered = progress.first { it.complete }
            assertThat(delivered.printed?.result).isEqualTo(ActionResult.Success)
            assertThat(delivered.emailed).isEqualTo(ActionResult.Success)
            assertThat(competing.value).isEqualTo(AutoDelivery(complete = true))
            assertThat(env.mail.sent).hasSize(1)
            assertThat(
                container.sales
                    .get("s1")
                    ?.sale
                    ?.emailedTo,
            ).isEqualTo("a@b.co")
        }

    private suspend fun automatic(transaction: StoredTransaction) = receipts.automation(transaction).first { it.complete }
}

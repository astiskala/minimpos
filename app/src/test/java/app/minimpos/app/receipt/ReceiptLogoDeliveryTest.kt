package app.minimpos.app.receipt

import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.closeViewModels
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.settings.SettingsChecks
import app.minimpos.app.feature.settings.SettingsViewModel
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.core.logo.MonoBitmap
import app.minimpos.core.logo.MonoPng
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.PrintResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.mail.internet.MimeMultipart

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptLogoDeliveryTest {
    private val terminal =
        FakeTerminal(passphrase = "passphrase", reply = { request ->
            val print = request.saleToPOIRequest.printRequest
            if (print?.printOutput?.outputContent?.outputFormat == OutputFormatType.XHTML) {
                Delivery.Answered(
                    TerminalAPIResponse().apply {
                        saleToPOIResponse =
                            SaleToPOIResponse().apply {
                                printResponse =
                                    PrintResponse().apply {
                                        response =
                                            Response().apply {
                                                result = ResultType.FAILURE
                                                errorCondition = ErrorConditionType.REFUSAL
                                                additionalResponse = "message=Unsupported+image"
                                            }
                                    }
                            }
                    },
                )
            } else {
                null
            }
        })

    @get:Rule
    val env = TestEnvironment(terminal = terminal)
    private val container = env.container
    private val transaction = StoredTransaction.Sale("logo-sale")
    private val logo = MonoBitmap(3, 2, byteArrayOf(0xA0.toByte(), 0x40))

    private val viewModels = mutableListOf<SettingsViewModel>()

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        Dispatchers.resetMain()
    }

    @Before
    fun ready() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env.useSimulator {
            it.copy(email = it.email.copy(host = "smtp.example.com", fromAddress = "shop@example.com"))
        }
        await {
            container.sales.createPending(
                SaleEntity(
                    id = "logo-sale",
                    createdAt = 1_790_679_835_000,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 100,
                    taxMinor = 0,
                    totalMinor = 100,
                    status = SaleStatus.APPROVED,
                    merchantReference = "LOGO-1",
                ),
                emptyList(),
            )
            container.receiptLogo.replace(logo)
        }
    }

    @Test
    fun `printed jobs start with the logo image`() {
        val printed = await { container.receipts.print(transaction) }
        assertThat(printed.result).isEqualTo(ActionResult.Success)
        val image =
            container.virtualPrinter.jobs.value
                .first() as PrintJob.Image
        assertThat(MonoPng.decode(image.png)).isEqualTo(logo)
    }

    @Test
    fun `emailed receipt attaches cid logo png`() {
        assertThat(await { container.receipts.email(transaction, "shopper@example.com") }).isEqualTo(ActionResult.Success)
        val related =
            env.mail.sent
                .single()
                .content as MimeMultipart
        val image = related.getBodyPart(1)
        assertThat(image.getHeader("Content-ID").single()).isEqualTo("<logo>")
        assertThat(MonoPng.decode(image.inputStream.use { it.readBytes() })).isEqualTo(logo)
    }

    @Test
    fun `logo change applies to reprints of older sales and receipt offers`() {
        val replacement = MonoBitmap(1, 1, byteArrayOf(0x80.toByte()))
        await { container.receiptLogo.replace(replacement) }
        await { container.receipts.print(transaction) }
        assertThat(
            MonoPng.decode(
                (
                    container.virtualPrinter.jobs.value
                        .first() as PrintJob.Image
                ).png,
            ),
        ).isEqualTo(replacement)
        assertThat(
            await {
                container.receipts
                    .offer(transaction)
                    .first()
                    .receipt
                    ?.logo
            },
        ).isEqualTo(replacement)
    }

    @Test
    fun `no logo prints exactly the existing text jobs`() {
        await { container.receiptLogo.replace(null) }
        val offer = await { container.receipts.offer(transaction).first() }
        val expected =
            PrintRenderer.jobs(
                checkNotNull(offer.receipt),
                await {
                    container.settings
                        .current()
                        .receipt.charsPerLine
                },
            )
        await { container.receipts.print(transaction) }
        assertThat(container.virtualPrinter.jobs.value).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `test print reports the first skipped logo and later receipt prints succeed quietly`() {
        env.useLinks()
        val vm =
            SettingsViewModel(
                container.pricingChanges,
                container.secrets,
                container.pinManager,
                container.sessionLock,
                SettingsChecks(container.terminalStatus, container.receipts, container.api, container.receiptBusinessDetails),
                container.history,
                container.catalog,
                container::sampleReceipt,
                logos = container.receiptLogo.logo,
            ).also(viewModels::add)
        await { vm.state.first { it.sampleReceipt?.logo == logo } }
        vm.printTest()
        val first = await { vm.actions.first { it.print.done } }.print
        assertThat(first.isError).isFalse()
        assertThat(first.outcome).isEqualTo(
            ActionOutcome.PrintedWithoutLogo(Fault.TerminalRejected(ExternalText("Unsupported image"))),
        )
        val second = await { container.receipts.print(transaction) }
        assertThat(second.result).isEqualTo(ActionResult.Success)
        assertThat(second.logoSkipped).isFalse()
        assertThat(second.logoFault).isEqualTo(Fault.TerminalRejected(ExternalText("Unsupported image")))
    }
}

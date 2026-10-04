package io.github.astiskala.minimpos.app.feature

import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeLinkApi
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.closeViewModels
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.settings.EmailCapture
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.sale.CheckoutViewModel
import io.github.astiskala.minimpos.app.feature.sale.PaymentLinkViewModel
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkResult
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentLinkViewModelsTest {
    private val api = FakeLinkApi()
    private val env = TestEnvironment(links = api)
    private val container = env.container
    private val viewModels = mutableListOf<ViewModel>()

    /** The main dispatcher, whose virtual clock the polls wait on. */
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
    }

    private fun checkout() =
        CheckoutViewModel(
            container.session(SaleKind.SALE),
            container.payments,
            container.settingsState,
            container.terminalStatus.state,
            container::currency,
            container.links,
        ).also(viewModels::add)

    /** Rings up a coffee and sends it as a payment link from checkout; returns the sale's ID once the link exists. */
    private fun sendLink(): String {
        container.session(SaleKind.SALE).addProduct(ProductEntity(1, "Latte", 450, 1), TaxRateEntity(1, "GST", 10_000))
        val vm = checkout()
        await { vm.state.first { it.canSendLink } }
        val id = checkNotNull(vm.sendLink())
        await { container.sales.observe(id).first { it?.sale?.status == SaleStatus.AWAITING_PAYMENT } }
        return id
    }

    private fun linkViewModel(
        id: String,
        fresh: Boolean = false,
        poll: Duration = 1.hours,
    ) = PaymentLinkViewModel(id, container.storedPayments, container.receipts, container.links, fresh, poll).also(viewModels::add)

    @Test
    fun `checkout offers a link only while links are set up, and sends the cart as one`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        container.session(SaleKind.SALE).addProduct(ProductEntity(1, "Latte", 450, 1), TaxRateEntity(1, "GST", 10_000))
        val offline = checkout()
        await { offline.state.first { !it.totals.isEmpty } }
        assertThat(offline.state.value.canSendLink).isFalse()
        assertThat(offline.sendLink()).isNull()
        val noLinks =
            CheckoutViewModel(
                container.session(SaleKind.SALE),
                container.payments,
                container.settingsState,
                container.terminalStatus.state,
                container::currency,
            ).also(viewModels::add)
        env.useLinks()
        await { noLinks.state.first { !it.totals.isEmpty } }
        assertThat(noLinks.state.value.canSendLink).isFalse()

        // The latte rung up above, and the one sendLink adds.
        val id = sendLink()
        assertThat(
            api.created
                .single()
                .first.amount.value,
        ).isEqualTo(900)
        // The cart is cleared once the link exists, just after the sale is stored as awaiting payment.
        assertThat(
            await {
                container
                    .session(SaleKind.SALE)
                    .cart
                    .first { it.lines.isEmpty() }
                    .lines
            },
        ).isEmpty()
        assertThat(
            await {
                container.sales
                    .get(id)!!
                    .sale.paymentLink
            },
        ).isTrue()
    }

    @Test
    fun `the link screen shows the open link, says when it is not paid yet, and follows it once paid`() {
        env.useLinks()
        val id = sendLink()
        val vm = linkViewModel(id)
        // The receipt is read separately from the link, so it may arrive just after it.
        val open = await { vm.state.first { it.openLink != null && it.transaction.receipt != null } }
        assertThat(open.openLink).isEqualTo(FakeLinkApi.URL)
        assertThat(open.creating).isFalse()
        assertThat(
            open.transaction.receipt!!
                .qrCodes
                .single()
                .content,
        ).isEqualTo(FakeLinkApi.URL)
        assertThat(open.transaction.canShare).isTrue()

        vm.check()
        assertThat(await { vm.state.first { it.check.done } }.check.outcome).isEqualTo(ActionOutcome.LinkNotPaid)
        api.getResult = PaymentLinkResult.Unknown("timeout")
        vm.check()
        val failed = await { vm.state.first { it.check.isError } }.check
        assertThat(failed.outcome).isEqualTo(ActionOutcome.Failed("timeout"))
        api.getResult = null
        api.status = PaymentLinkStatus.COMPLETED
        vm.check()
        val paid = await { vm.state.first { it.sale?.status == SaleStatus.APPROVED } }
        assertThat(paid.openLink).isNull()
        assertThat(paid.check.outcome).isNull()
    }

    @Test
    fun `an open link is checked on its own until it is paid`() {
        env.useLinks()
        val id = sendLink()
        val vm = linkViewModel(id, poll = 20.milliseconds)
        await { vm.state.first { it.openLink != null } }
        await {
            while (api.asked.size < 2) {
                main.scheduler.advanceTimeBy(20.milliseconds)
                delay(10)
            }
        }
        api.status = PaymentLinkStatus.COMPLETED
        await {
            while (vm.state.value.sale
                    ?.status != SaleStatus.APPROVED
            ) {
                main.scheduler.advanceTimeBy(20.milliseconds)
                delay(10)
            }
        }
        // Paid, it is no longer asked about.
        val asked = api.asked.size
        main.scheduler.advanceTimeBy(1.hours)
        assertThat(api.asked.size).isEqualTo(asked)
    }

    @Test
    fun `cancelling expires the link, and a link just made is emailed automatically when the address was asked for`() {
        env.useLinks()
        env.updateSettings {
            it.copy(
                payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = true),
                email = it.email.copy(host = "smtp.example.com", fromAddress = "shop@example.com"),
            )
        }
        container.session(SaleKind.SALE).updateForm { it.copy(email = "sam@example.com") }
        val id = sendLink()
        val vm = linkViewModel(id, fresh = true)
        await { vm.state.first { it.transaction.email.done } }
        assertThat(
            env.mail.sent
                .single()
                .subject,
        ).contains("Payment request")

        api.expireResult = PaymentLinkResult.NotProcessed("Not allowed (HTTP 403)")
        vm.cancel()
        assertThat(await { vm.state.first { it.cancel.isError } }.cancel.outcome).isEqualTo(ActionOutcome.Failed("Not allowed (HTTP 403)"))
        api.expireResult = null
        vm.cancel()
        val cancelled = await { vm.state.first { it.sale?.status == SaleStatus.CANCELLED } }
        assertThat(cancelled.cancel.done).isTrue()
        assertThat(cancelled.cancel.outcome).isNull()
        assertThat(cancelled.openLink).isNull()

        vm.transaction.share()
        val shared = await { vm.state.first { it.transaction.share != null } }.transaction.share!!
        assertThat(shared.paymentLink).isNull()
        vm.transaction.shared()
        assertThat(vm.state.value.transaction.share).isNull()
    }
}

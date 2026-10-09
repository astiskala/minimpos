package app.minimpos.app.payment

import app.minimpos.app.FakeLinkApi
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.refund.PaymentAction
import app.minimpos.app.refund.PaymentStanding
import app.minimpos.app.refund.ReceiptStanding
import app.minimpos.app.refund.StoredPayment
import app.minimpos.app.refund.TotalsShare
import app.minimpos.app.refund.standing
import app.minimpos.app.refund.totalsShare
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.PaymentLinkResult
import app.minimpos.terminal.checkout.PaymentLinkStatus
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import javax.mail.Multipart
import javax.mail.Part

@RunWith(RobolectricTestRunner::class)
class PaymentLinksTest {
    private val api = FakeLinkApi()
    private val env = TestEnvironment(links = api)
    private val container = env.container
    private val links = container.links
    private val session = container.session(SaleKind.SALE)
    private val now = Instant.parse("2026-10-02T09:30:00Z")

    @After
    fun tearDown() = env.close()

    /** A sale of two flat whites rung up and checked out with links available, as the checkout screen makes it. */
    private fun linkStart(
        customerReference: String = "CUST-1",
        tokenize: Boolean = false,
        instant: Instant = now,
    ): PaymentLinkStart {
        session.addProduct(ProductEntity(1, "Flat white", 450, 1), TaxRateEntity(1, "GST", 10_000))
        session.addProduct(ProductEntity(1, "Flat white", 450, 1), TaxRateEntity(1, "GST", 10_000))
        session.updateForm { it.copy(customerReference = customerReference, email = "sam@example.com", tokenize = tokenize) }
        val checkout =
            await {
                session.checkout(container.settingsState, flowOf(false), flowOf(true)) { CurrencySpec.of("AUD") }.first()
            }
        return checkNotNull(session.linkStart(checkout, instant, ZoneOffset.UTC))
    }

    private fun savingCardsUnderCustomerReference() =
        env.updateSettings { it.copy(payment = it.payment.copy(shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE)) }

    private fun saleWhen(
        id: String,
        condition: (SaleEntity) -> Boolean,
    ): SaleEntity =
        await {
            container.sales
                .observe(id)
                .first { it != null && condition(it.sale) }!!
                .sale
        }

    @Test
    fun `a link is stored before Adyen is called, then awaits its payment and clears the cart`() {
        env.useLinks()
        savingCardsUnderCustomerReference()
        val start = linkStart()
        val id = links.start(start)
        val sale = saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(sale.paymentLink).isTrue()
        assertThat(sale.paymentLinkId).isEqualTo(FakeLinkApi.LINK_ID)
        assertThat(sale.paymentLinkUrl).isEqualTo(FakeLinkApi.URL)
        assertThat(sale.paymentLinkExpiresAt).isEqualTo(FakeLinkApi.EXPIRES.toEpochMilli())
        assertThat(sale.shopperReference).isEqualTo("CUST-1")
        assertThat(sale.serviceId).isNull()
        val (request, key) = api.created.single()
        assertThat(key).isEqualTo("link-$id")
        assertThat(request.reference).isEqualTo(start.payment.merchantReference)
        assertThat(request.amount).isEqualTo(ModificationAmount("AUD", 900))
        assertThat(request.expiresAt).isEqualTo(now.plusSeconds(24 * 3600))
        assertThat(request.lineItems.single().quantity).isEqualTo(2)
        assertThat(request.shopperEmail).isEqualTo("sam@example.com")
        assertThat(request.recurringProcessingModel).isNull()
        assertThat(request.metadata).containsExactly("customerReference", "CUST-1")
        await { session.cart.first { it.lines.isEmpty() } }
        // Unpaid, it counts nowhere and can be neither refunded nor cancelled.
        assertThat(sale.standing).isEqualTo(PaymentStanding.NOT_APPROVED)
        assertThat(sale.totalsShare).isEqualTo(TotalsShare.NONE)
        assertThat(ReceiptStanding.of(sale).unpaidLink).isEqualTo(FakeLinkApi.URL)
    }

    @Test
    fun `link card-saving facts come from checkout even after settings change`() {
        env.useLinks()
        savingCardsUnderCustomerReference()
        env.updateSettings { it.copy(payment = it.payment.copy(recurringProcessingModel = "CardOnFile")) }
        val start = linkStart(tokenize = true)
        env.updateSettings { it.copy(payment = it.payment.copy(recurringProcessingModel = "Subscription")) }
        val id = links.start(start)
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(
            api.created
                .single()
                .first.recurringProcessingModel,
        ).isEqualTo("CardOnFile")
    }

    @Test
    fun `link card-saving facts use the normalized checkout model`() {
        env.useLinks()
        savingCardsUnderCustomerReference()
        env.updateSettings { it.copy(payment = it.payment.copy(recurringProcessingModel = "Unknown")) }
        val id = links.start(linkStart(tokenize = true))
        val sale = saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(sale.linkRecurringModel).isEqualTo("UnscheduledCardOnFile")
        assertThat(
            api.created
                .single()
                .first.recurringProcessingModel,
        ).isEqualTo("UnscheduledCardOnFile")
    }

    @Test
    fun `checking finds a link open, then paid, which is charged but refunded in the Customer Area`() {
        env.useLinks()
        savingCardsUnderCustomerReference()
        val id = links.start(linkStart(tokenize = true))
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(
            api.created
                .single()
                .first.recurringProcessingModel,
        ).isEqualTo("UnscheduledCardOnFile")
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.StillOpen)
        api.status = PaymentLinkStatus.COMPLETED
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.Settled)
        val paid = await { container.sales.get(id)!! }
        assertThat(paid.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(paid.sale.standing).isEqualTo(PaymentStanding.CHARGED)
        assertThat(paid.sale.totalsShare).isEqualTo(TotalsShare.SALE)
        assertThat(ReceiptStanding.of(paid.sale).paidOnline).isTrue()
        assertThat(ReceiptStanding.of(paid.sale).unpaidLink).isNull()
        assertThat(StoredPayment(paid).actions).doesNotContain(PaymentAction.REFUND)
        // A settled link is not asked about again.
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.Settled)
        assertThat(api.asked).hasSize(2)
        assertThat(await { links.check("missing") }).isEqualTo(LinkUpdate.Settled)
    }

    @Test
    fun `a link expires on its own or is cancelled, and a failed answer changes nothing`() {
        env.useLinks()
        val first = links.start(linkStart())
        saleWhen(first) { it.status == SaleStatus.AWAITING_PAYMENT }
        api.getResult = PaymentLinkResult.Failed(Fault.TimedOut)
        assertThat(await { links.check(first) }).isEqualTo(LinkUpdate.Failed(Failure.Remote(Fault.TimedOut)))
        api.getResult = PaymentLinkResult.Failed(Fault.AdyenRejected(422, null, ExternalText("Not allowed (HTTP 403)")))
        assertThat(
            await {
                links.check(first)
            },
        ).isEqualTo(LinkUpdate.Failed(Failure.Remote(Fault.AdyenRejected(422, null, ExternalText("Not allowed (HTTP 403)")))))
        assertThat(
            await {
                container.sales
                    .get(first)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.AWAITING_PAYMENT)
        api.getResult = null
        api.status = PaymentLinkStatus.EXPIRED
        assertThat(await { links.check(first) }).isEqualTo(LinkUpdate.Settled)
        assertThat(
            await {
                container.sales
                    .get(first)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.EXPIRED)

        api.status = PaymentLinkStatus.ACTIVE
        val second = links.start(linkStart())
        saleWhen(second) { it.status == SaleStatus.AWAITING_PAYMENT }
        api.expireResult = PaymentLinkResult.Failed(Fault.TimedOut)
        assertThat(await { links.cancel(second) }).isEqualTo(LinkUpdate.Failed(Failure.Remote(Fault.TimedOut)))
        api.expireResult = null
        assertThat(await { links.cancel(second) }).isEqualTo(LinkUpdate.Settled)
        assertThat(
            await {
                container.sales
                    .get(second)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.CANCELLED)
        assertThat(api.expired).containsExactly(FakeLinkApi.LINK_ID, FakeLinkApi.LINK_ID)
        // Only a link awaiting its payment can be cancelled.
        assertThat(await { links.cancel(second) }).isEqualTo(LinkUpdate.Settled)
        assertThat(api.expired).hasSize(2)

        // Paid just before the cancellation arrived: it is stored as paid.
        val third = links.start(linkStart())
        saleWhen(third) { it.status == SaleStatus.AWAITING_PAYMENT }
        api.expireResult = PaymentLinkResult.Answered(api.link(PaymentLinkStatus.COMPLETED))
        assertThat(await { links.cancel(third) }).isEqualTo(LinkUpdate.Settled)
        assertThat(
            await {
                container.sales
                    .get(third)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.APPROVED)
    }

    @Test
    fun `a refused link fails and keeps the cart, and an unknown one is created again with the same key`() {
        env.useLinks()
        api.createResult = PaymentLinkResult.Failed(Fault.AdyenRejected(422, null, ExternalText("Expiry too far ahead (HTTP 422, code 1)")))
        val refused = links.start(linkStart())
        val failed = saleWhen(refused) { it.status == SaleStatus.FAILED }
        assertThat(failed.reason)
            .isEqualTo(
                StoredReason.NotDone(
                    Failure.Remote(Fault.AdyenRejected(422, null, ExternalText("Expiry too far ahead (HTTP 422, code 1)"))),
                ),
            )
        assertThat(session.cart.value.lines).isNotEmpty()
        assertThat(await { links.check(refused) }).isEqualTo(LinkUpdate.Settled)

        session.clear()
        api.createResult = PaymentLinkResult.Failed(Fault.TimedOut)
        val unknown = links.start(linkStart())
        val pending = saleWhen(unknown) { it.status == SaleStatus.UNKNOWN }
        assertThat(pending.reason).isEqualTo(StoredReason.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        assertThat(pending.message).isNull()
        api.createResult = null
        assertThat(await { links.check(unknown) }).isEqualTo(LinkUpdate.StillOpen)
        assertThat(
            await {
                container.sales
                    .get(unknown)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.AWAITING_PAYMENT)
        val (first, second) = api.created.takeLast(2)
        assertThat(second.second).isEqualTo("link-$unknown")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `recovering an unknown link clears its original session but preserves newer work`() {
        env.useLinks()
        repeat(2) { attempt ->
            api.createResult = PaymentLinkResult.Failed(Fault.TimedOut)
            val start = linkStart()
            val id = links.start(start)
            saleWhen(id) { it.status == SaleStatus.UNKNOWN }
            if (attempt == 1) session.updateForm { it.copy(email = "new@example.com") }
            api.createResult = null
            assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.StillOpen)
            assertThat(
                session.cart.value.lines
                    .isEmpty(),
            ).isEqualTo(attempt == 0)
        }
    }

    @Test
    fun `missing setup cannot turn an unknown link creation into a known failure`() {
        env.useLinks()
        api.createResult = PaymentLinkResult.Failed(Fault.TimedOut)
        val id = links.start(linkStart())
        saleWhen(id) { it.status == SaleStatus.UNKNOWN }
        env.useSimulator()
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT))
        assertThat(
            await {
                container.sales
                    .get(id)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.UNKNOWN)
    }

    @Test
    fun `configured credentials for another merchant cannot check or cancel an existing link`() {
        env.useLinks()
        val id = links.start(linkStart())
        val original = saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "OtherMerchant")) }
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT))
        assertThat(await { links.cancel(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT))
        assertThat(api.asked).isEmpty()
        assertThat(api.expired).isEmpty()
        assertThat(await { container.sales.get(id)!!.sale }).isEqualTo(original)
    }

    @Test
    fun `without the Checkout API a link fails with what to enter, and checks change nothing`() {
        env.useLinks()
        val id = links.start(linkStart())
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, merchantAccount = "")) }
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        assertThat(await { links.cancel(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        val other = links.start(linkStart())
        assertThat(
            saleWhen(other) { it.status == SaleStatus.FAILED }.reason,
        ).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MERCHANT_ACCOUNT)))
        assertThat(api.created).hasSize(1)
    }

    @Test
    fun `demo links stay open until explicitly paid and retain their outcome in a fresh service`() {
        env.useSimulator()
        val id = links.start(linkStart(instant = Instant.now()))
        val open = saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(open.context?.simulated).isTrue()
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.StillOpen)
        assertThat(api.created).isEmpty()
        val restarted = PaymentLinks(env.scope, container.sales, container.api::target)
        assertThat(await { restarted.check(id) }).isEqualTo(LinkUpdate.StillOpen)
        assertThat(await { restarted.simulate(id) }).isEqualTo(LinkUpdate.Settled)
        val paid = saleWhen(id) { it.status == SaleStatus.APPROVED }
        assertThat(await { restarted.cancel(id) }).isEqualTo(LinkUpdate.Settled)
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.Settled)
        assertThat(await { container.sales.get(id)!!.sale }).isEqualTo(paid)
        val receipt = container.receiptFactory.sale(await { container.sales.get(id)!! }, await { container.settings.current() }.receipt)
        assertThat(PlainTextReceiptRenderer().render(receipt)).contains("Simulation only.")
    }

    @Test
    fun `demo cancellation and expiry persist and simulation cannot complete real or mismatched links`() {
        env.useSimulator()
        val id = links.start(linkStart(instant = Instant.now()))
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(await { links.cancel(id) }).isEqualTo(LinkUpdate.Settled)
        assertThat(saleWhen(id) { it.status == SaleStatus.CANCELLED }.context?.simulated).isTrue()
        assertThat(await { links.simulate(id) }).isEqualTo(LinkUpdate.Settled)
        val expired = links.start(linkStart(instant = Instant.now().minusSeconds(25 * 3600)))
        saleWhen(expired) { it.status == SaleStatus.EXPIRED }
        assertThat(await { links.simulate(expired) }).isEqualTo(LinkUpdate.Settled)
        val open = links.start(linkStart(instant = Instant.now()))
        val original = saleWhen(open) { it.status == SaleStatus.AWAITING_PAYMENT }
        env.useLinks()
        assertThat(await { links.simulate(open) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT))
        assertThat(await { container.sales.get(open)!!.sale }).isEqualTo(original)
        val real = links.start(linkStart())
        saleWhen(real) { it.status == SaleStatus.AWAITING_PAYMENT }
        assertThat(await { links.simulate(real) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.PAYMENT_CONTEXT))
        assertThat(api.asked).isEmpty()
    }

    @Test
    fun `demo payment rechecks Manager approval without changing the link`() {
        env.useSimulator()
        val id = links.start(linkStart(instant = Instant.now()))
        val original = saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        await {
            container.pinManager.setPin("1234")
            container.managerPin.setPin("2468")
        }
        assertThat(await { links.simulate(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.MANAGER_APPROVAL))
        assertThat(await { container.sales.get(id)!!.sale }).isEqualTo(original)
    }

    @Test
    fun `unpaid and completed demo receipts and emails stay labeled after changing destination`() {
        env.useSimulator()
        env.updateSettings {
            it.copy(email = it.email.copy(host = "smtp.example.com", fromAddress = "shop@example.com"))
        }
        val id = links.start(linkStart(instant = Instant.now()))
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        val transaction = StoredTransaction.Sale(id)
        val open = await { container.receipts.offer(transaction).first { it.receipt != null } }
        assertThat(open.share?.simulatedLink).isTrue()
        assertThat(PlainTextReceiptRenderer().render(checkNotNull(open.receipt))).contains("Demo link")
        await { container.receipts.email(transaction, "sam@example.com") }
        assertThat(
            env.mail.sent
                .single()
                .subject,
        ).isEqualTo("Demo link")
        assertThat(
            env.mail.sent
                .single()
                .allText(),
        ).contains("Simulation only. No money moved.")
        assertThat(
            env.mail.sent
                .single()
                .allText(),
        ).doesNotContain("Pay securely")
        await { links.simulate(id) }
        env.useLinks()
        await { container.receipts.email(transaction, "sam@example.com") }
        assertThat(
            env.mail.sent
                .last()
                .subject,
        ).isEqualTo("Demo link")
        assertThat(
            env.mail.sent
                .last()
                .allText(),
        ).contains("Simulation only. No money moved.")
    }

    private fun Part.allText(): String =
        when (val content = content) {
            is String -> content
            is Multipart -> (0 until content.count).joinToString("\n") { content.getBodyPart(it).allText() }
            else -> ""
        }
}

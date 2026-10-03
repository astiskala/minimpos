package io.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.FakeLinkApi
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.db.StoredReason
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.refund.PaymentAction
import io.minimpos.app.refund.PaymentStanding
import io.minimpos.app.refund.ReceiptStanding
import io.minimpos.app.refund.StoredPayment
import io.minimpos.app.refund.TotalsShare
import io.minimpos.app.refund.standing
import io.minimpos.app.refund.totalsShare
import io.minimpos.core.money.CurrencySpec
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.PaymentLinkResult
import io.minimpos.terminal.checkout.PaymentLinkStatus
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

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
    ): PaymentLinkStart {
        session.addProduct(ProductEntity(1, "Flat white", 450, 1), TaxRateEntity(1, "GST", 10_000))
        session.addProduct(ProductEntity(1, "Flat white", 450, 1), TaxRateEntity(1, "GST", 10_000))
        session.updateForm { it.copy(customerReference = customerReference, email = "sam@example.com", tokenize = tokenize) }
        val settings = container.settingsState.value
        val checkout =
            Checkout(
                session.form.value,
                settings.payment,
                session.cart.value.totals(settings.payment.taxMode),
                CurrencySpec.of("AUD"),
                linksAvailable = true,
            )
        return checkNotNull(checkout.linkStart(now, ZoneOffset.UTC))
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
        api.getResult = PaymentLinkResult.Unknown("timeout")
        assertThat(await { links.check(first) }).isEqualTo(LinkUpdate.Failed("timeout"))
        api.getResult = PaymentLinkResult.NotProcessed("Not allowed (HTTP 403)")
        assertThat(await { links.check(first) }).isEqualTo(LinkUpdate.Failed("Not allowed (HTTP 403)"))
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
        api.expireResult = PaymentLinkResult.Unknown("timeout")
        assertThat(await { links.cancel(second) }).isEqualTo(LinkUpdate.Failed("timeout"))
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
        api.createResult = PaymentLinkResult.NotProcessed("Expiry too far ahead (HTTP 422, code 1)")
        val refused = links.start(linkStart())
        val failed = saleWhen(refused) { it.status == SaleStatus.FAILED }
        assertThat(failed.message).isEqualTo("Expiry too far ahead (HTTP 422, code 1)")
        assertThat(session.cart.value.lines).isNotEmpty()
        assertThat(await { links.check(refused) }).isEqualTo(LinkUpdate.Settled)

        session.clear()
        api.createResult = PaymentLinkResult.Unknown("timeout")
        val unknown = links.start(linkStart())
        val pending = saleWhen(unknown) { it.status == SaleStatus.UNKNOWN }
        assertThat(pending.reason).isEqualTo(StoredReason.OutcomeUnknown)
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
            api.createResult = PaymentLinkResult.Unknown("timeout")
            val initial = linkStart()
            val start = initial.copy(payment = initial.payment.copy(sessionRevision = session.revision))
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
        api.createResult = PaymentLinkResult.Unknown("timeout")
        val id = links.start(linkStart())
        saleWhen(id) { it.status == SaleStatus.UNKNOWN }
        env.useSimulator()
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.API_REQUIRED))
        assertThat(
            await {
                container.sales
                    .get(id)!!
                    .sale.status
            },
        ).isEqualTo(SaleStatus.UNKNOWN)
    }

    @Test
    fun `without the Checkout API a link fails with what to enter, and checks change nothing`() {
        env.useLinks()
        val id = links.start(linkStart())
        saleWhen(id) { it.status == SaleStatus.AWAITING_PAYMENT }
        env.useSimulator()
        assertThat(await { links.check(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.API_REQUIRED))
        assertThat(await { links.cancel(id) }).isEqualTo(LinkUpdate.NotSetUp(SetupProblem.API_REQUIRED))
        val other = links.start(linkStart())
        assertThat(saleWhen(other) { it.status == SaleStatus.FAILED }.reason).isEqualTo(StoredReason.NotSetUp(SetupProblem.API_REQUIRED))
        assertThat(api.created).hasSize(1)
    }
}

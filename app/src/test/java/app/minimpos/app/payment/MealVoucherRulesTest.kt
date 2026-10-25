package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.TenderBalance
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.split.AllocatableLine
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.voucher.MealVoucherScheme
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure meal-voucher readings: discovery, stored voucher amounts and the starts Checkout allows. */
class MealVoucherRulesTest {
    private val context = PaymentContext("TERMINAL", "S1F2-1", "POS", "Merchant", "TEST")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun tender(
        status: SaleStatus,
        requested: Long,
        authorised: Long? = null,
        surcharge: Long? = null,
        tip: Long? = null,
        voucher: MealVoucherScheme? = MealVoucherScheme.EUROPE_CARD_SCHEME,
    ) = SaleTenderEntity(
        id = "s-$requested-$status",
        saleId = "s",
        sequence = 1,
        requestedMinor = requested,
        authorisedMinor = authorised,
        status = status,
        surchargeMinor = surcharge,
        tipMinor = tip,
        mealVoucher = voucher?.name,
    )

    @Test
    fun `discovery offers only a verified store market on a supporting destination`() {
        val ready =
            WalletAvailability(
                context = context,
                identity = "id",
                storeCountry = "BE",
                mealVoucherDestination = true,
                checked = true,
            )
        assertThat(ready.mealVoucherSchemes("EUR")).containsExactly(MealVoucherScheme.EUROPE_CARD_SCHEME)
        assertThat(ready.mealVoucherSchemes("BRL")).isEmpty()
        assertThat(ready.copy(storeCountry = null).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(mealVoucherDestination = false).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(checked = false).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(problem = SetupProblem.API_KEY).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(context = null).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(identity = null).mealVoucherSchemes("EUR")).isEmpty()
        assertThat(ready.copy(simulated = true, storeCountry = null).mealVoucherSchemes("BRL"))
            .containsExactly(MealVoucherScheme.BRAZIL_PAT)
    }

    @Test
    fun `stored voucher amounts count approved purchase and whole unresolved requests only`() {
        val balance =
            TenderBalance(
                totalMinor = 5_000,
                paidMinor = 1_500,
                lines = listOf(AllocatableLine(0, 1, 0, 5_000)),
                tenders =
                    listOf(
                        tender(SaleStatus.APPROVED, 1_000, authorised = 1_200, surcharge = 100, tip = 100),
                        tender(SaleStatus.APPROVED, 500, authorised = 500),
                        tender(SaleStatus.APPROVED, 900, authorised = null),
                        tender(SaleStatus.DECLINED, 700),
                        tender(SaleStatus.APPROVED, 400, authorised = 400, voucher = null),
                    ),
                eligibleMinor = 3_000,
            )
        assertThat(balance.voucherMinor).isEqualTo(1_500)
        assertThat(balance.voucherLimitMinor(MealVoucherScheme.EUROPE_CARD_SCHEME)).isEqualTo(1_500)
        assertThat(balance.acceptsVoucher(1_500, MealVoucherScheme.EUROPE_CARD_SCHEME)).isTrue()
        assertThat(balance.acceptsVoucher(1_501, MealVoucherScheme.EUROPE_CARD_SCHEME)).isFalse()
        val unresolved = TenderBalance(5_000, 0, emptyList(), listOf(tender(SaleStatus.UNKNOWN, 800)), eligibleMinor = 3_000)
        assertThat(unresolved.voucherMinor).isEqualTo(800)
        assertThat(unresolved.voucherLimitMinor(MealVoucherScheme.EUROPE_CARD_SCHEME)).isEqualTo(0)
        assertThat(unresolved.acceptsVoucher(100, MealVoucherScheme.EUROPE_CARD_SCHEME)).isFalse()
    }

    @Test
    fun `a voucher start needs a usable reading, verified setup and a plain amount tender in the market currency`() {
        val totals =
            Cart()
                .addProduct(CartProduct(1, "Meal", null, 2_000, AppliedTax("Zero", 0), mealVoucherEligible = true)) { "m" }
                .totals(TaxMode.INCLUSIVE)
        val available = MealVoucherAvailability(true, listOf(MealVoucherScheme.EUROPE_CARD_SCHEME), context, "id")
        val checkout = Checkout(totals = totals, currency = CurrencySpec.of("EUR"))
        val base = checkout.splitStart(Instant.EPOCH, ZoneOffset.UTC, 1_000, null)
        val reading = Checkout.mealVoucher(available, null, SaleBook.splitBalance(totals, false), monday, null)
        assertThat(reading.limitMinor).isEqualTo(2_000)
        assertThat(Checkout.mealVoucherStart(base, reading, available, 1_000)?.mealVoucher)
            .isEqualTo(MealVoucherScheme.EUROPE_CARD_SCHEME)
        assertThat(Checkout.mealVoucherStart(null, reading, available, 1_000)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading, available, 999)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading, available, 0)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading.copy(scheme = null), available, 1_000)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading, available.copy(context = null), 1_000)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading, available.copy(identity = null), 1_000)).isNull()
        assertThat(Checkout.mealVoucherStart(base, reading.copy(blocked = MealVoucherBlock.UNRESOLVED), available, 1_000)).isNull()
        val share = checkNotNull(base).copy(allocation = TenderAllocation.Share(0, 2, 2_000))
        assertThat(Checkout.mealVoucherStart(share, reading, available, 1_000)).isNull()
        val brazil = reading.copy(scheme = MealVoucherScheme.BRAZIL_PAT)
        assertThat(Checkout.mealVoucherStart(base, brazil, available, 1_000)).isNull()
        val demo = available.copy(schemes = MealVoucherScheme.demonstrable("EUR"), simulated = true)
        val france =
            Checkout.mealVoucher(
                demo,
                MealVoucherScheme.FRANCE_TITRES_RESTAURANT,
                SaleBook.splitBalance(totals, false),
                monday,
                null,
            )
        assertThat(france.scheme).isEqualTo(MealVoucherScheme.FRANCE_TITRES_RESTAURANT)
        assertThat(france.schemes).hasSize(2)
        assertThat(
            Checkout.mealVoucher(demo, MealVoucherScheme.BRAZIL_PAT, SaleBook.splitBalance(totals, false), monday, context).scheme,
        ).isEqualTo(MealVoucherScheme.EUROPE_CARD_SCHEME)
    }
}

package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SaleWithLines
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.repo.SampleData
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PaymentSettings
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.app.refund.StoredPayment
import io.github.astiskala.minimpos.app.refund.TotalsShare
import io.github.astiskala.minimpos.app.refund.totalsShare
import io.github.astiskala.minimpos.core.receipt.PlainTextReceiptRenderer
import io.github.astiskala.minimpos.core.tax.TaxMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SampleDataTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container

    @Test
    fun `population is atomic offline and idempotent even for concurrent requests`() =
        await {
            val settings = container.settings.current()
            val results = coroutineScope { List(2) { async { container.sampleData.populate(settings) } }.awaitAll() }
            assertThat(results.count { it }).isEqualTo(1)
            val products = container.catalog.products.first()
            assertThat(products).hasSize(4)
            assertThat(products.all { it.sample && it.sku == null }).isTrue()
            assertThat(products.count { it.kind == SaleKind.PRE_AUTHORISATION }).isEqualTo(1)
            val sales =
                container.database
                    .saleDao()
                    .sales()
                    .first()
            assertThat(sales.map { it.status }).containsExactly(SaleStatus.APPROVED, SaleStatus.DECLINED, SaleStatus.CANCELLED)
            sales.forEach {
                assertThat(it.sample).isTrue()
                assertThat(it.context!!.simulated).isTrue()
                assertThat(it.currency).isEqualTo("AUD")
                assertThat(it.totalsShare).isEqualTo(if (it.status == SaleStatus.APPROVED) TotalsShare.SALE else TotalsShare.NONE)
                val record = container.sales.get(it.id)!!
                assertThat(record.lines).hasSize(1)
                assertThat(StoredPayment(record).actions).isEmpty()
                assertThat(RefundablePayment.qrCode(record)).isNull()
                val receipt = container.receiptFactory.sale(record, settings.receipt)
                assertThat(PlainTextReceiptRenderer(48).render(receipt)).contains("No money moved")
            }
            assertThat(container.settings.current()).isEqualTo(settings)
            assertThat(container.secrets.configured.first()).isEmpty()
        }

    @Test
    fun `purge follows markers after edits and preserves merchant rows and shared categories`() =
        await {
            container.sampleData.populate(container.settings.current())
            val sample =
                container.catalog.products
                    .first()
                    .first()
            container.catalog.saveProduct(sample.copy(name = "Edited sample", priceMinor = 900, sample = false))
            assertThat(container.catalog.product(sample.id)!!.sample).isTrue()
            val category =
                container.catalog.categories
                    .first()
                    .single()
            container.catalog.saveCategory(category.copy(name = "Renamed sample category", sample = false))
            assertThat(
                container.catalog.categories
                    .first()
                    .single()
                    .sample,
            ).isTrue()
            val own =
                ProductEntity(name = sample.name, priceMinor = 100, taxRateId = sample.taxRateId, categoryId = sample.categoryId)
            val ownId = container.catalog.saveProduct(own)
            container.catalog.saveCategory(CategoryEntity(name = "Own category"))
            val ownSale =
                SaleEntity(
                    id = "own",
                    createdAt = 0,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 100,
                    taxMinor = 0,
                    totalMinor = 100,
                    status = SaleStatus.APPROVED,
                    merchantReference = "DEMO-1",
                )
            container.sales.createPending(ownSale, emptyList())
            val rates = container.catalog.taxRates.first()
            container.sampleData.purge()
            container.sampleData.purge()
            assertThat(container.catalog.products.first()).containsExactly(own.copy(id = ownId))
            assertThat(container.catalog.categories.first()).hasSize(2)
            assertThat(container.catalog.taxRates.first()).isEqualTo(rates)
            assertThat(
                container.database
                    .saleDao()
                    .sales()
                    .first(),
            ).containsExactly(ownSale)
            assertThat(
                container.catalog.categories
                    .first()
                    .none { it.sample },
            ).isTrue()
            assertThat(container.sampleData.populate(container.settings.current())).isTrue()
            Unit
        }

    @Test
    fun `purging all samples permits reseeding and catalogue transfers lose sample identity`() =
        await {
            val settings = container.settings.current()
            container.sampleData.populate(settings)
            val exported = container.catalog.export("AUD")
            container.sampleData.purge()
            assertThat(container.catalog.categories.first()).isEmpty()
            assertThat(
                container.database
                    .saleDao()
                    .sales()
                    .first(),
            ).isEmpty()
            assertThat(container.sampleData.populate(settings)).isTrue()
            container.catalog.import(exported, ImportMode.REPLACE)
            assertThat(
                container.catalog.products
                    .first()
                    .none { it.sample },
            ).isTrue()
            assertThat(
                container.catalog.categories
                    .first()
                    .none { it.sample },
            ).isTrue()
            container.sampleData.purge()
            assertThat(container.catalog.products.first()).hasSize(4)
        }

    @Test
    fun `sample amounts use Adyen currency decimals and current tax settings`() =
        await {
            val rateId = container.catalog.saveTaxRate(TaxRateEntity(name = "Tax", rateMilliPercent = 10_000))
            val settings =
                AppSettings(
                    payment = PaymentSettings(currencyCode = "JPY", taxMode = TaxMode.EXCLUSIVE, defaultTaxRateId = rateId),
                )
            container.sampleData.populate(settings)
            val coffee =
                container.catalog.products
                    .first()
                    .first { it.name.contains("coffee") }
            assertThat(coffee.priceMinor).isEqualTo(5)
            val sale =
                container.database
                    .saleDao()
                    .sales()
                    .first()
                    .first { it.status == SaleStatus.APPROVED }
            assertThat(sale.totalMinor).isEqualTo(6)
            assertThat(sale.taxMinor).isEqualTo(1)
            container.sampleData.purge()
            container.sampleData.populate(settings.copy(payment = settings.payment.copy(chargeTax = false)))
            assertThat(
                container.database
                    .saleDao()
                    .sales()
                    .first()
                    .all { it.taxMinor == 0L },
            ).isTrue()
        }

    @Test
    fun `failed population rolls back catalog and history rather than leaving partial samples`() =
        await {
            val invalid =
                SampleData(
                    container.database,
                    container.catalog,
                    container.sales,
                    container.history,
                    texts = { listOf("Coffee", "Tea", "Cake", "Deposit", "Extra", "Category") },
                    starterRates = { listOf(TaxRateEntity(name = "Zero", rateMilliPercent = 0)) },
                    country = "AU",
                )
            try {
                invalid.populate(AppSettings())
                fail("Expected malformed sample names to fail")
            } catch (_: IndexOutOfBoundsException) {
                // The database transaction must roll back all writes made before the invalid price lookup.
            }
            assertThat(container.catalog.products.first()).isEmpty()
            assertThat(container.catalog.categories.first()).isEmpty()
            assertThat(container.catalog.taxRates.first()).isEmpty()
            assertThat(
                container.database
                    .saleDao()
                    .sales()
                    .first(),
            ).isEmpty()
            assertThat(container.sampleData.populate(AppSettings())).isTrue()
        }

    @Test
    fun `sample marker blocks financial actions even if transaction details are present`() {
        val sale =
            SaleEntity(
                id = "demo",
                sample = true,
                createdAt = 0,
                currency = "AUD",
                taxMode = "INCLUSIVE",
                netMinor = 100,
                taxMinor = 0,
                totalMinor = 100,
                status = SaleStatus.APPROVED,
                merchantReference = "DEMO",
                poiTransactionId = "tender.psp",
                poiTimestamp = "2026-10-05T09:00:00Z",
                pspReference = "psp",
                kind = SaleKind.PRE_AUTHORISATION,
            )
        assertThat(StoredPayment(SaleWithLines(sale, emptyList())).actions).isEmpty()
        assertThat(RefundablePayment.qrCode(SaleWithLines(sale.copy(kind = SaleKind.SALE), emptyList()))).isNull()
    }
}

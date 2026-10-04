package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleLineEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.DeleteResult
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.repo.ReceiptLinesJson
import io.github.astiskala.minimpos.app.data.repo.RefundedLine
import io.github.astiskala.minimpos.app.data.repo.SaleEvent
import io.github.astiskala.minimpos.core.catalogue.Catalogue
import io.github.astiskala.minimpos.core.catalogue.CatalogueCategory
import io.github.astiskala.minimpos.core.catalogue.CatalogueProduct
import io.github.astiskala.minimpos.core.catalogue.CatalogueTaxRate
import io.github.astiskala.minimpos.core.receipt.CardReceiptLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {
    private val env = TestEnvironment()
    private val catalog = env.container.catalog
    private val sales = env.container.sales
    private val refunds = env.container.refundRecords
    private val history = env.container.history

    @After
    fun tearDown() = env.close()

    @Test
    fun `seeds default tax rates once`() =
        await {
            catalog.seedDefaults(STARTER)
            catalog.seedDefaults(listOf(TaxRateEntity(name = "Other", rateMilliPercent = 5_000)))
            assertThat(
                catalog.taxRates.first().map {
                    it.name to it.rateMilliPercent
                },
            ).containsExactly("Standard" to 10_000, "Zero" to 0).inOrder()
            assertThat(catalog.taxRates.first().map { it.sortOrder }).containsExactly(0, 1).inOrder()
        }

    @Test
    fun `seeds the starter tax rates of the device's country`() {
        val rates = { country: String ->
            val other = TestEnvironment(device = FakeDevice(country = country))
            try {
                other.container.starterTaxRates().map { it.name to it.rateMilliPercent }
            } finally {
                other.close()
            }
        }
        assertThat(rates("DE")).containsExactly("VAT" to 19_000, "No tax" to 0).inOrder()
        assertThat(rates("JP")).containsExactly("Standard" to 10_000, "Reduced" to 8_000, "No tax" to 0).inOrder()
        // No national rate, or an unknown country: no tax until the merchant sets a rate.
        assertThat(rates("US")).containsExactly("No tax" to 0)
        assertThat(rates("")).containsExactly("No tax" to 0)
    }

    @Test
    fun `tax-rate deletion retains the last rate without caller prechecks`() =
        await {
            catalog.seedDefaults(STARTER)
            val rates = catalog.taxRates.first()
            rates.forEach { catalog.deleteTaxRate(it) }
            assertThat(catalog.taxRates.first()).hasSize(1)
        }

    @Test
    fun `concurrent tax-rate deletions cannot remove every rate`() =
        await {
            catalog.seedDefaults(STARTER)
            val rates = catalog.taxRates.first()
            val results = coroutineScope { rates.map { async { catalog.deleteTaxRate(it) } }.awaitAll() }
            assertThat(results).containsExactly(DeleteResult.Deleted, DeleteResult.LastRate)
            assertThat(catalog.taxRates.first()).hasSize(1)
        }

    @Test
    fun `saves, updates and deletes catalogue entries`() =
        await {
            val taxId = catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val categoryId = catalog.saveCategory(CategoryEntity(name = "Coffee"))
            val productId =
                catalog.saveProduct(
                    ProductEntity(name = "Latte", priceMinor = 450, taxRateId = taxId, categoryId = categoryId, sku = "123"),
                )
            catalog.saveProduct(catalog.product(productId)!!.copy(priceMinor = 500))
            catalog.saveTaxRate(TaxRateEntity(taxId, "GST ", 10_000))
            catalog.saveCategory(CategoryEntity(categoryId, "Hot drinks"))
            assertThat(catalog.productBySku(" 123 ")!!.priceMinor).isEqualTo(500)
            assertThat(
                catalog.categories
                    .first()
                    .single()
                    .name,
            ).isEqualTo("Hot drinks")

            val tax = catalog.taxRates.first().single()
            assertThat(catalog.deleteTaxRate(tax)).isEqualTo(DeleteResult.InUse(1))
            catalog.deleteCategory(catalog.categories.first().single())
            assertThat(catalog.product(productId)!!.categoryId).isNull()
            catalog.deleteProduct(catalog.product(productId)!!)
            assertThat(catalog.deleteTaxRate(tax)).isEqualTo(DeleteResult.LastRate)
            assertThat(catalog.taxRates.first()).containsExactly(tax)
            assertThat(catalog.products.first()).isEmpty()
        }

    private val incoming =
        Catalogue(
            currencyCode = "AUD",
            taxRates = listOf(CatalogueTaxRate("GST", 10_000), CatalogueTaxRate("Free", 0)),
            categories = listOf(CatalogueCategory("Coffee")),
            products =
                listOf(
                    CatalogueProduct("Latte", 500, 0, 0, "L1"),
                    CatalogueProduct("Water", 300, 1, null, null),
                    CatalogueProduct("Catering deposit", 20_000, 0, null, "CD", preAuthorisation = true),
                ),
        )

    @Test
    fun `an empty replacement catalogue retains a zero rate for custom items`() =
        await {
            catalog.seedDefaults(STARTER)
            val summary = catalog.import(Catalogue("AUD", emptyList(), emptyList(), emptyList()), ImportMode.REPLACE)
            assertThat(catalog.taxRates.first().map { it.rateMilliPercent }).containsExactly(0)
            assertThat(summary.taxRatesAdded).isEqualTo(1)
            assertThat(catalog.products.first()).isEmpty()
        }

    @Test
    fun `exports and replaces catalogues`() =
        await {
            catalog.seedDefaults(STARTER)
            val standard = catalog.taxRates.first().first()
            catalog.saveProduct(ProductEntity(name = "Old", priceMinor = 1, taxRateId = standard.id))
            val summary = catalog.import(incoming, ImportMode.REPLACE)
            assertThat(summary.productsAdded).isEqualTo(3)
            assertThat(summary.taxRatesAdded).isEqualTo(2)
            assertThat(summary.categoriesAdded).isEqualTo(1)
            assertThat(catalog.export("AUD")).isEqualTo(incoming)
        }

    @Test
    fun `barcodes find products of the kind being sold only`() =
        await {
            catalog.import(incoming, ImportMode.REPLACE)
            assertThat(catalog.productBySku("CD", SaleKind.SALE)).isNull()
            assertThat(catalog.productBySku("CD", SaleKind.PRE_AUTHORISATION)!!.name).isEqualTo("Catering deposit")
            assertThat(catalog.productBySku("L1", SaleKind.SALE)!!.kind).isEqualTo(SaleKind.SALE)
            assertThat(catalog.productBySku("L1", SaleKind.PRE_AUTHORISATION)).isNull()
        }

    @Test
    fun `merges by SKU or name`() =
        await {
            val gst = catalog.saveTaxRate(TaxRateEntity(name = "gst", rateMilliPercent = 10_000))
            catalog.saveCategory(CategoryEntity(name = "COFFEE"))
            catalog.saveProduct(ProductEntity(name = "Latte (old)", priceMinor = 400, taxRateId = gst, sku = "L1"))
            catalog.saveProduct(ProductEntity(name = "water", priceMinor = 250, taxRateId = gst))
            catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 350, taxRateId = gst))
            val summary = catalog.import(incoming, ImportMode.MERGE)
            assertThat(summary.productsUpdated).isEqualTo(2)
            assertThat(summary.productsAdded).isEqualTo(1)
            assertThat(summary.taxRatesAdded).isEqualTo(1)
            assertThat(summary.categoriesAdded).isEqualTo(0)
            val products = catalog.products.first().associateBy { it.name }
            assertThat(products.keys).containsExactly("Latte", "Water", "Tea", "Catering deposit")
            assertThat(products.getValue("Latte").priceMinor).isEqualTo(500)
            assertThat(products.getValue("Water").priceMinor).isEqualTo(300)
        }

    @Test
    fun `untaxed products from older catalogues get a zero rate`() =
        await {
            val older =
                Catalogue(
                    currencyCode = "AUD",
                    taxRates = listOf(CatalogueTaxRate("GST", 10_000)),
                    categories = emptyList(),
                    products = listOf(CatalogueProduct("Stamp", 120, null, null, "S1"), CatalogueProduct("Card", 300, null, null, "C1")),
                )
            // Without a 0% rate one is created, once.
            val summary = catalog.import(older, ImportMode.REPLACE)
            assertThat(summary.taxRatesAdded).isEqualTo(2)
            val zero = catalog.taxRates.first().single { it.rateMilliPercent == 0 }
            assertThat(zero.name).isEqualTo("No tax")
            assertThat(catalog.products.first().map { it.taxRateId }).containsExactly(zero.id, zero.id)
            // An existing 0% rate is used, whatever its name.
            catalog.saveTaxRate(zero.copy(name = "GST-free"))
            catalog.import(older, ImportMode.MERGE)
            assertThat(
                catalog.taxRates
                    .first()
                    .filter { it.rateMilliPercent == 0 }
                    .map { it.name },
            ).containsExactly("GST-free")
            assertThat(catalog.export("AUD").products.map { it.taxRateIndex }).isEqualTo(listOf(1, 1))
        }

    private fun sale(
        id: String,
        createdAt: Long = 1_000,
        status: SaleStatus = SaleStatus.APPROVED,
    ) = SaleEntity(
        id = id,
        createdAt = createdAt,
        currency = "AUD",
        taxMode = "INCLUSIVE",
        netMinor = 1_000,
        taxMinor = 100,
        totalMinor = 1_100,
        status = status,
        merchantReference = "MP-$id",
        poiTransactionId = "T.$id",
        poiTimestamp = "2026-01-01T00:00:00.000Z",
    )

    private fun lines(id: String) =
        listOf(
            SaleLineEntity(
                saleId = id,
                position = 0,
                productId = 1,
                name = "A",
                sku = null,
                unitPriceMinor = 300,
                quantity = 2,
                taxName = "GST",
                taxRateMilliPercent = 10_000,
                netMinor = 545,
                taxMinor = 55,
                grossMinor = 600,
            ),
            SaleLineEntity(
                saleId = id,
                position = 1,
                productId = null,
                name = "B",
                sku = null,
                unitPriceMinor = 500,
                quantity = 1,
                taxName = "GST",
                taxRateMilliPercent = 10_000,
                netMinor = 455,
                taxMinor = 45,
                grossMinor = 500,
            ),
        )

    private fun refund(
        saleId: String?,
        full: Boolean,
        amount: Long,
        lines: List<RefundedLine> = emptyList(),
        status: RefundStatus = RefundStatus.REQUESTED,
        createdAt: Long = 2_000,
    ) = RefundEntity(
        id = "R$amount$full$createdAt",
        saleId = saleId,
        createdAt = createdAt,
        merchantReference = "MP-R",
        originalTransactionId = "T.${saleId ?: "unlinked"}",
        originalTimestamp = "t",
        originalReference = "MP-${saleId ?: "unlinked"}",
        currency = "AUD",
        amountMinor = amount,
        full = full,
        status = status,
        linesJson = ReceiptLinesJson.encodeRefunded(lines),
    )

    @Test
    fun `settling an accepted partial refund twice applies it only once`() =
        await {
            sales.createPending(sale("s1"), lines("s1"))
            val line = sales.get("s1")!!.sortedLines.first()
            val partial = refund("s1", false, 300, listOf(RefundedLine(line.id, "A", 1, 300, 300)))
            refunds.create(partial.copy(status = RefundStatus.UNKNOWN))
            repeat(2) { refunds.settle(partial.id, RefundStatus.REQUESTED, null, null) }
            val stored = sales.get("s1")!!
            assertThat(stored.sale.refundedMinor).isEqualTo(300)
            assertThat(stored.sortedLines.first().refundedQuantity).isEqualTo(1)
            refunds.settle(partial.id, RefundStatus.FAILED, "late failure", null)
            assertThat(refunds.get(partial.id)!!.status).isEqualTo(RefundStatus.REQUESTED)
        }

    @Test
    fun `stores sales and records refunded items`() =
        await {
            sales.createPending(sale("s1"), lines("s1"))
            val stored = sales.get("s1")!!
            assertThat(sales.findByTransactionId("T.s1")!!.sale.id).isEqualTo("s1")
            val lineA = stored.sortedLines.first()

            val partial = refund("s1", full = false, amount = 300, lines = listOf(RefundedLine(lineA.id, "A", 1, 300, 300)))
            refunds.create(partial.copy(status = RefundStatus.PENDING))
            refunds.settle(partial.id, RefundStatus.REQUESTED, null, null)
            val afterPartial = sales.get("s1")!!
            assertThat(afterPartial.sale.refundedMinor).isEqualTo(300)
            assertThat(afterPartial.sortedLines.map { it.refundedQuantity }).containsExactly(1, 0).inOrder()
            assertThat(refunds.forSale("s1").first()).hasSize(1)

            val failed = refund("s1", full = false, amount = 100, status = RefundStatus.FAILED, createdAt = 3_000)
            refunds.create(failed.copy(status = RefundStatus.PENDING))
            refunds.settle(failed.id, RefundStatus.FAILED, "Declined", null)
            assertThat(refunds.get(failed.id)!!.message).isEqualTo("Declined")
            assertThat(sales.get("s1")!!.sale.refundedMinor).isEqualTo(300)

            val full = refund("s1", full = true, amount = 1_100, createdAt = 4_000)
            refunds.create(full.copy(status = RefundStatus.PENDING))
            refunds.settle(full.id, RefundStatus.REQUESTED, null, null)
            val afterFull = sales.get("s1")!!
            assertThat(afterFull.sale.refundedMinor).isEqualTo(1_100)
            assertThat(afterFull.sortedLines.map { it.refundedQuantity }).containsExactly(2, 1).inOrder()
            assertThat(refunds.get(full.id)!!.status).isEqualTo(RefundStatus.REQUESTED)
        }

    @Test
    fun `an accepted cancellation marks the hold cancelled and refunds nothing`() =
        await {
            sales.createPending(sale("h1").copy(kind = SaleKind.PRE_AUTHORISATION), lines("h1"))
            val cancellation = refund("h1", full = true, amount = 1_100, status = RefundStatus.PENDING).copy(cancellation = true)
            refunds.create(cancellation)
            refunds.settle(cancellation.id, RefundStatus.REQUESTED, null, null)
            val cancelled = sales.get("h1")!!
            assertThat(cancelled.sale.holdCancelled).isTrue()
            assertThat(cancelled.sale.refundedMinor).isEqualTo(0)
            assertThat(cancelled.sortedLines.map { it.refundedQuantity }).containsExactly(0, 0).inOrder()
            assertThat(refunds.get(cancellation.id)!!.status).isEqualTo(RefundStatus.REQUESTED)
            // A refund that no longer exists is not settled.
            refunds.settle("gone", RefundStatus.REQUESTED, null, null)
            assertThat(refunds.get("gone")).isNull()
        }

    @Test
    fun `foreign refunds do not touch local sales`() =
        await {
            val foreign = refund(null, full = false, amount = 50)
            refunds.create(foreign)
            refunds.settle(foreign.id, RefundStatus.REQUESTED, null, null)
            val missing = refund("gone", full = false, amount = 50, createdAt = 9)
            refunds.create(missing)
            refunds.settle(missing.id, RefundStatus.REQUESTED, null, null)
            assertThat(refunds.observe(foreign.id).first()!!.amountMinor).isEqualTo(50)
        }

    @Test
    fun `retention and clearing preserve unresolved work and recent refund references`() =
        await {
            sales.createPending(sale("unknown", createdAt = 0, status = SaleStatus.UNKNOWN), emptyList())
            sales.createPending(sale("held", createdAt = 0).copy(kind = SaleKind.PRE_AUTHORISATION), emptyList())
            sales.createPending(sale("recent-refund", createdAt = 0), emptyList())
            refunds.create(refund("recent-refund", false, 10, createdAt = 100_000_000))
            assertThat(history.prune(1, 100_000_000)).isEqualTo(0)
            assertThat(sales.get("recent-refund")).isNotNull()
            history.clear()
            assertThat(sales.get("unknown")).isNotNull()
            assertThat(sales.get("held")).isNotNull()
            assertThat(sales.get("recent-refund")).isNull()
        }

    @Test
    fun `history merges sales and refunds newest first, and prunes`() =
        await {
            sales.createPending(sale("old", createdAt = 0), emptyList())
            sales.createPending(sale("new", createdAt = 5_000), emptyList())
            refunds.create(refund("new", full = false, amount = 10, createdAt = 6_000))
            val items = history.items().first()
            assertThat(items.map { it.id }).containsExactly("R10false6000", "new", "old").inOrder()
            assertThat(items.first()).isInstanceOf(HistoryItem.Refund::class.java)
            assertThat(history.prune(retentionDays = 0, now = 0)).isEqualTo(0)
            assertThat(history.prune(retentionDays = 1, now = 24L * 60 * 60 * 1000 + 1)).isEqualTo(1)
            assertThat(history.items().first()).hasSize(2)
            history.clear()
            assertThat(history.items().first()).isEmpty()
        }

    @Test
    fun `interrupted transactions become unknown and emails are recorded`() =
        await {
            sales.createPending(sale("p", status = SaleStatus.PENDING), emptyList())
            refunds.create(refund("p", full = false, amount = 5, status = RefundStatus.PENDING))
            history.settleInterrupted()
            assertThat(
                sales
                    .observe("p")
                    .first()!!
                    .sale.status,
            ).isEqualTo(SaleStatus.UNKNOWN)
            assertThat(refunds.get("R5false2000")!!.status).isEqualTo(RefundStatus.UNKNOWN)
            sales.record("p", SaleEvent.Emailed("a@b.co"))
            sales.record("missing", SaleEvent.Emailed("a@b.co"))
            assertThat(sales.get("p")!!.sale.emailedTo).isEqualTo("a@b.co")
        }

    @Test
    fun `receipt lines serialise to JSON`() {
        val lines = listOf(CardReceiptLine("k", "n", "v", true))
        assertThat(ReceiptLinesJson.decode(ReceiptLinesJson.encode(lines))).isEqualTo(lines)
        assertThat(ReceiptLinesJson.encode(emptyList())).isNull()
        assertThat(ReceiptLinesJson.decode(null)).isEmpty()
        assertThat(ReceiptLinesJson.decode("not json")).isEmpty()
        assertThat(ReceiptLinesJson.decodeRefunded("[")).isEmpty()
        assertThat(ReceiptLinesJson.encodeRefunded(emptyList())).isNull()
    }

    private companion object {
        val STARTER =
            listOf(TaxRateEntity(name = "Standard", rateMilliPercent = 10_000), TaxRateEntity(name = "Zero", rateMilliPercent = 0))
    }
}

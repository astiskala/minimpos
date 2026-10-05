package io.github.astiskala.minimpos.app.data.repo

import androidx.room.withTransaction
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleLineEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.core.tax.TaxRates
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.util.UUID

/**
 * Optional catalog examples and read-only simulated history. Creation and removal are atomic, main-safe and offline.
 * Existing catalog rows, history, tax rates and settings are never overwritten. Repeated creation does nothing while
 * any tracked samples remain; edits retain their sample identity. Exported catalog data has no sample identity.
 *
 * @param db Database transaction boundary shared by the repositories.
 * @param catalog Owns sample catalog writes.
 * @param sales Owns creation and settlement of demo sales.
 * @param history Owns scoped history removal.
 * @param texts Localized names stored only when samples are first added, in product order followed by the category.
 * @param starterRates Initial tax rates if setup runs before startup seeding.
 * @param country Device country used when the merchant has not selected a currency.
 * @param now Epoch milliseconds used for the sample history.
 */
class SampleData(
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
    private val sales: SaleRepository,
    private val history: HistoryRepository,
    private val texts: () -> List<String>,
    private val starterRates: () -> List<TaxRateEntity>,
    private val country: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Adds samples priced in the current currency/tax style; returns false if samples already exist. */
    suspend fun populate(settings: AppSettings): Boolean =
        db.withTransaction {
            if (catalog.samples.exists() || history.hasSamples()) return@withTransaction false
            catalog.seedDefaults(starterRates())
            val tax = checkNotNull(settings.payment.defaultTaxRate(catalog.taxRates.first()))
            val names = texts()
            val currency = CurrencySpec.of(settings.payment.resolvedCurrency(country))
            val prices = listOf("4.50", "3.00", "6.00", "50.00").map { currency.toMinor(BigDecimal(it)) }
            val products = catalog.samples.add(names.dropLast(1), names.last(), prices, tax.id)
            products.filter { it.kind == SaleKind.SALE }.forEachIndexed { index, product ->
                createSale(settings, currency, tax, product, index)
            }
            true
        }

    /** Purges only tracked samples. Merchant records and sample categories they use remain. */
    suspend fun purge() {
        db.withTransaction {
            history.removeSamples()
            catalog.samples.remove()
        }
    }

    private suspend fun createSale(
        settings: AppSettings,
        currency: CurrencySpec,
        tax: TaxRateEntity,
        product: ProductEntity,
        index: Int,
    ) {
        val id = UUID.randomUUID().toString()
        val rate = if (settings.payment.chargeTax) tax.rateMilliPercent else 0
        val amounts = TaxRates.apply(product.priceMinor, rate, settings.payment.taxMode)
        sales.createPending(
            SaleEntity(
                id = id,
                sample = true,
                createdAt = now() - index * HOUR_MILLIS,
                currency = currency.code,
                taxMode = settings.payment.taxMode.name,
                netMinor = amounts.net,
                taxMinor = amounts.tax,
                totalMinor = amounts.gross,
                status = SaleStatus.PENDING,
                merchantReference = "DEMO-${index + 1}",
                context = PaymentContext("SIMULATOR", "SAMPLE", "SAMPLE", "", null, simulated = true),
            ),
            listOf(
                SaleLineEntity(
                    saleId = id,
                    position = 0,
                    productId = product.id,
                    name = product.name,
                    sku = null,
                    unitPriceMinor = product.priceMinor,
                    quantity = 1,
                    taxName = if (settings.payment.chargeTax) tax.name else "",
                    taxRateMilliPercent = rate,
                    netMinor = amounts.net,
                    taxMinor = amounts.tax,
                    grossMinor = amounts.gross,
                ),
            ),
        )
        val status = listOf(SaleStatus.APPROVED, SaleStatus.DECLINED, SaleStatus.CANCELLED)[index]
        sales.record(id, SaleEvent.Settled(status, null, null))
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}

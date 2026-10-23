package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxRates
import kotlin.uuid.Uuid

/**
 * Optional catalog examples and read-only simulated history. Creation and removal are atomic, main-safe and offline.
 * Existing catalog rows, history and tax rates are never overwritten. Repeated creation adds no rows while any tracked
 * samples remain; edits retain their sample identity. The container applies fictional receipt business details after
 * population; onboarding preserves any merchant business fields already entered. Exported catalog data has no sample identity.
 *
 * @param db Database transaction boundary shared by the repositories.
 * @param catalog Owns sample catalog writes.
 * @param sales Owns creation and settlement of demo sales.
 * @param history Owns scoped history removal.
 * @param fixture Shared localized screenshot fixtures, read only when samples are added.
 * @param starterRates Initial tax rates if setup runs before startup seeding.
 * @param country Device country used when the merchant has not selected a currency.
 * @param now Epoch milliseconds used for the sample history.
 * @param withSettings Receives the receipt replacement policy and runs population with stable settings while excluding
 *   concurrent pricing transitions.
 */
class SampleData(
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
    private val sales: SaleRepository,
    private val history: HistoryRepository,
    private val fixture: () -> SampleDataFixture,
    private val starterRates: () -> List<TaxRateEntity>,
    private val country: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val withSettings: suspend (Boolean, suspend (AppSettings) -> Boolean) -> Boolean = { _, write -> write(AppSettings()) },
) {
    /**
     * Adds samples in the current stable currency/tax style; returns false if samples already exist.
     * @param replaceReceiptDetails Whether to replace merchant business fields; false fills only an empty business profile.
     */
    suspend fun populate(replaceReceiptDetails: Boolean = true): Boolean = withSettings(replaceReceiptDetails, ::populate)

    /** Adds samples priced in [settings]'s currency/tax style; returns false if samples already exist. */
    suspend fun populate(settings: AppSettings): Boolean =
        db.withTransaction {
            if (catalog.samples.exists() || history.hasSamples()) return@withTransaction false
            catalog.seedDefaults(starterRates())
            val tax = checkNotNull(settings.payment.defaultTaxRate(catalog.reader.taxRates()))
            val currency = CurrencySpec.of(settings.payment.resolvedCurrency(country))
            val products = catalog.samples.add(fixture().products(currency, tax.id), tax.id)
            products.filter { it.kind == SaleKind.SALE }.take(SAMPLE_STATUSES.size).forEachIndexed { index, product ->
                createSale(settings, currency, tax, product, index)
            }
            true
        }

    /** Purges only tracked samples. Merchant records and sample categories they use remain. */
    suspend fun purge() {
        history.removeSamples { catalog.samples.remove() }
    }

    private suspend fun createSale(
        settings: AppSettings,
        currency: CurrencySpec,
        tax: TaxRateEntity,
        product: ProductEntity,
        index: Int,
    ) {
        val id = Uuid.random().toString()
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
        val status = SAMPLE_STATUSES[index]
        sales.record(id, SaleEvent.Settled(status, null, null, processedAt = now() - index * HOUR_MILLIS))
    }

    private companion object {
        val SAMPLE_STATUSES = listOf(SaleStatus.APPROVED, SaleStatus.DECLINED, SaleStatus.CANCELLED)
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}

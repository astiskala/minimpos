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
import app.minimpos.terminal.client.OfflineProcessingMode
import app.minimpos.terminal.client.Tokenization
import app.minimpos.terminal.client.TransactionDetails
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Optional catalog examples and read-only simulated history. Creation and removal are atomic, main-safe and offline.
 * Existing catalog rows, history and tax rates are never overwritten. Repeated creation adds no rows while any tracked
 * samples remain; edits retain their sample identity. The container applies fictional receipt business details after
 * population; onboarding preserves any merchant business fields already entered. Exported catalog data has no sample identity.
 * Each population request also adds the sample [logo] while no receipt logo is saved; purging removes it only while unchanged.
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
 * @param logo The receipt logo samples demonstrate; null adds none.
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
    private val logo: SampleLogo? = null,
) {
    /**
     * Adds samples in the current stable currency/tax style and the sample logo when none is saved; returns false if
     * samples already exist.
     * @param replaceReceiptDetails Whether to replace merchant business fields; false fills only an empty business profile.
     */
    suspend fun populate(replaceReceiptDetails: Boolean = true): Boolean =
        withSettings(replaceReceiptDetails, ::populate).also { logo?.add() }

    /**
     * Adds samples priced in [settings]'s currency/tax style; returns false if samples already exist. Besides an approved,
     * a declined and a cancelled sale, the history shows completed approvals with a terminal tip and shopper rating, an
     * Adyen Giving donation, an offline store-and-forward approval and a card saved for [SAMPLE_CUSTOMER], which Tokens
     * finds. Nothing in it awaits an action, since sample history offers none.
     */
    suspend fun populate(settings: AppSettings): Boolean =
        db.withTransaction {
            if (catalog.samples.exists() || history.hasSamples()) return@withTransaction false
            catalog.seedDefaults(starterRates())
            val tax = checkNotNull(settings.payment.defaultTaxRate(catalog.reader.taxRates()))
            val currency = CurrencySpec.of(settings.payment.resolvedCurrency(country))
            val products = catalog.samples.add(fixture().products(currency, tax.id), tax.id)
            products.filter { it.kind == SaleKind.SALE }.take(SampleSale.entries.size).forEachIndexed { index, product ->
                createSale(settings, currency, tax, product, index)
            }
            true
        }

    /** Purges only tracked samples and the unchanged sample logo. Merchant records and sample categories they use remain. */
    suspend fun purge() {
        history.removeSamples { catalog.samples.remove() }
        logo?.remove()
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
        val sample = SampleSale.entries[index]
        val customer = SAMPLE_CUSTOMER.takeIf { sample == SampleSale.SAVED_CARD }
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
                customerReference = customer,
                shopperReference = customer,
                tokenizationRequested = customer != null,
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
        sales.record(
            id,
            SaleEvent.Settled(
                sample.status,
                null,
                sample.details(amounts.gross, currency),
                processedAt =
                    now() - index * HOUR_MILLIS,
            ),
        )
    }

    /** The sample sales, newest first, each showing one outcome or completed Adyen feature. */
    private enum class SampleSale(
        val status: SaleStatus = SaleStatus.APPROVED,
    ) {
        APPROVED,
        DECLINED(SaleStatus.DECLINED),
        CANCELLED(SaleStatus.CANCELLED),
        TIP,
        GIVING,
        OFFLINE,
        SAVED_CARD,
        ;

        /** What a simulated terminal would have answered for [totalMinor] in [currency]; null for the plain outcomes. */
        fun details(
            totalMinor: Long,
            currency: CurrencySpec,
        ): TransactionDetails? =
            when (this) {
                APPROVED, DECLINED, CANCELLED -> {
                    null
                }

                TIP -> {
                    val tip = ((totalMinor * TIP_PERCENT + PERCENT / 2) / PERCENT).coerceAtLeast(1)
                    card(Math.addExact(totalMinor, tip), currency).copy(tipMinor = tip, shopperRating = RATING)
                }

                GIVING -> {
                    card(totalMinor, currency).copy(donationMinor = currency.toMinor(BigDecimal.ONE))
                }

                OFFLINE -> {
                    card(totalMinor, currency).copy(offlineMode = OfflineProcessingMode.STORE_AND_FORWARD, unconfirmedBatchCount = 1)
                }

                SAVED_CARD -> {
                    card(totalMinor, currency).copy(tokenization = Tokenization(SAMPLE_TOKEN))
                }
            }

        private fun card(
            amountMinor: Long,
            currency: CurrencySpec,
        ) = TransactionDetails(
            success = true,
            errorCondition = null,
            message = null,
            refusalReason = null,
            poiTransactionId = null,
            poiTimestamp = null,
            pspReference = null,
            merchantReference = null,
            amountMinor = amountMinor,
            surchargeMinor = 0,
            currency = currency.code,
            paymentBrand = "mc",
            maskedPan = "541333 **** 9999",
            entryMode = "Contactless",
            approvalCode = null,
            customerReceipt = emptyList(),
            cashierReceipt = emptyList(),
            signatureRequired = false,
            additionalData = emptyMap(),
            tokenization = null,
        )
    }

    /** The sample shopper whose saved card Tokens finds. */
    companion object {
        /** Customer reference, and so shopper reference, of the sample sale that saved a card. */
        const val SAMPLE_CUSTOMER = "CUST-1042"

        private const val SAMPLE_TOKEN = "DEMOTOKEN1042"
        private const val HOUR_MILLIS = 60L * 60 * 1000
        private const val TIP_PERCENT = 10L
        private const val PERCENT = 100L
        private const val RATING = 9
    }
}

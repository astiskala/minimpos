package app.minimpos.app.terminal

import app.minimpos.core.payment.ScanWallet
import app.minimpos.terminal.transport.WalletMethod
import java.util.Locale

/**
 * One eligible POS wallet's non-secret currency restrictions, not a guarantee that a payment will be approved.
 * @property wallet Explicitly configured wallet; Alipay+ never implies individually onboarded wallets.
 * @property currencies Allowed currencies; empty means no additional restriction was supplied.
 */
data class WalletOffer(
    val wallet: ScanWallet,
    val currencies: Set<String>,
)

/** Pure configuration and hardware reading; callers must not infer wallet or scanner support independently. */
internal object WalletOffers {
    private val SCANNER_MODELS = setOf("S1EL", "S1EBARCODE", "S1E2L", "S1F2L", "S1F2BARCODE", "S1U2")

    fun configured(
        methods: List<WalletMethod>,
        storeId: String,
        country: String,
    ): List<WalletOffer> =
        methods
            .filter { it.enabled && it.allowed && it.channel.equals("pos", ignoreCase = true) }
            .filter { it.storeIds.isEmpty() || storeId in it.storeIds }
            .filter { it.countries.isEmpty() || country in it.countries || "ANY" in it.countries }
            .mapNotNull { method -> ScanWallet.configured(method.type)?.let { WalletOffer(it, method.currencies) } }
            .groupBy { it.wallet }
            .map { (wallet, configurations) ->
                WalletOffer(
                    wallet,
                    if (configurations.any { it.currencies.isEmpty() }) emptySet() else configurations.flatMap { it.currencies }.toSet(),
                )
            }

    fun forCurrency(
        offers: List<WalletOffer>,
        currency: String,
    ): List<ScanWallet> =
        offers
            .filter { it.currencies.isEmpty() || currency in it.currencies }
            .map { it.wallet }
            .sortedBy { it.displayName }

    fun nativeScanner(
        model: String,
        poiId: String,
    ): Boolean =
        model
            .ifBlank { poiId.substringBefore('-') }
            .uppercase(Locale.ROOT)
            .replace(" ", "") in SCANNER_MODELS
}

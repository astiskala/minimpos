package app.minimpos.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import app.minimpos.app.data.db.SaleKind
import kotlinx.serialization.Serializable

/**
 * The app's screens, as Navigation 3 keys. They are serialisable so the back stack survives process death, which is
 * why they carry IDs rather than loaded objects.
 */
@Serializable
sealed interface Route : NavKey {
    /** Protected routes need the admin PIN (when one is set). */
    val isProtected: Boolean get() = false

    /** Whether this route exposes money-moving actions gated by the optional Manager PIN. */
    val requiresManager: Boolean get() = false

    /** The start screen with a tile per area; always at the bottom of the back stack. */
    @Serializable data object Home : Route

    /** First-run setup choices, kept below import and terminal settings so Back can choose a different path. */
    @Serializable data object Onboarding : Route

    /** Ringing up a sale: product tiles, custom items and the cart. */
    @Serializable data object Sale : Route

    /** Choosing the one pre-authorisation product, or custom amount, to hold on a card. */
    @Serializable data object PreAuth : Route

    /**
     * References, email and saving the card, before paying.
     *
     * @property kind A sale, or a pre-authorisation whose amount is only held.
     */
    @Serializable data class Checkout(
        val kind: SaleKind = SaleKind.SALE,
    ) : Route

    /** Transient wallet selection and scanning; contains no code and restores without automatically restarting a scan. */
    @Serializable data object WalletScan : Route

    /**
     * Waiting for the terminal while the shopper pays.
     *
     * @property kind What is being paid.
     */
    @Serializable data class Payment(
        val kind: SaleKind = SaleKind.SALE,
    ) : Route

    /**
     * The outcome of a payment just taken.
     *
     * @property saleId The sale that was paid.
     */
    @Serializable data class SaleResult(
        val saleId: String,
    ) : Route

    /**
     * The payment link of a sale: its QR code and address to share, email or print, whether it was paid, and cancelling
     * it.
     *
     * @property saleId The sale paid through the link.
     * @property fresh Whether the link was just created at checkout, so its automatic delivery is due.
     */
    @Serializable data class PaymentLink(
        val saleId: String,
        val fresh: Boolean = false,
    ) : Route

    /**
     * Entering the tip written on the receipt of a sale taken for tipping on the receipt, which captures it.
     *
     * @property saleId The sale awaiting its tip.
     */
    @Serializable data class Tip(
        val saleId: String,
    ) : Route {
        override val requiresManager get() = true
    }

    /**
     * Capturing a pre-authorisation, or adjusting what it holds.
     *
     * @property saleId The pre-authorisation.
     * @property adjustOnly Adjust the amount held without capturing.
     */
    @Serializable data class Capture(
        val saleId: String,
        val adjustOnly: Boolean = false,
    ) : Route {
        override val requiresManager get() = true
    }

    /** Scanning the refund QR code on a receipt. */
    @Serializable data object RefundScan : Route {
        override val requiresManager get() = true
    }

    /**
     * Choosing what to refund; exactly one of the two is set.
     *
     * @property payload The scanned refund QR code.
     * @property saleId A sale from history.
     */
    @Serializable data class Refund(
        val payload: String? = null,
        val saleId: String? = null,
    ) : Route {
        override val requiresManager get() = true
    }

    /** Waiting for the terminal to accept the refund. */
    @Serializable data object RefundProcessing : Route {
        override val requiresManager get() = true
    }

    /**
     * The outcome of a refund just made.
     *
     * @property refundId The refund that was made.
     */
    @Serializable data class RefundResult(
        val refundId: String,
    ) : Route {
        override val requiresManager get() = true
    }

    /** Sales and refunds by day. */
    @Serializable data object History : Route

    /**
     * A sale from history.
     *
     * @property saleId The sale shown.
     */
    @Serializable data class SaleDetail(
        val saleId: String,
    ) : Route

    /**
     * A refund from history.
     *
     * @property refundId The refund shown.
     */
    @Serializable data class RefundDetail(
        val refundId: String,
    ) : Route

    /** The product and category list. */
    @Serializable data object Products : Route {
        override val isProtected get() = true
    }

    /**
     * Adding or editing a product.
     *
     * @property productId The product to edit, or null to add one.
     * @property sku A scanned barcode to start a new product with, or null.
     */
    @Serializable data class ProductEdit(
        val productId: Long? = null,
        val sku: String? = null,
    ) : Route {
        override val isProtected get() = true
    }

    /** Sharing the catalogue, settings and secrets as QR codes with another terminal. */
    @Serializable data object TransferExport : Route {
        override val isProtected get() = true
    }

    /** Scanning another terminal's transfer codes to set this one up the same way. */
    @Serializable data object TransferImport : Route {
        override val isProtected get() = true
    }

    /** The list of settings sections. */
    @Serializable data object Settings : Route {
        override val isProtected get() = true
    }

    /**
     * One settings section.
     *
     * @property section One of the [app.minimpos.app.feature.settings.SettingsSections] keys.
     */
    @Serializable data class SettingsSection(
        val section: String,
    ) : Route {
        override val isProtected get() = true
    }

    /** Routes shared by sales and pre-authorisations. */
    companion object {
        /** Where a payment of [kind] is rung up: [PreAuth] for a pre-authorisation, else [Sale]. */
        fun ringUp(kind: SaleKind): Route =
            when (kind) {
                SaleKind.SALE -> Sale
                SaleKind.PRE_AUTHORISATION -> PreAuth
            }
    }
}

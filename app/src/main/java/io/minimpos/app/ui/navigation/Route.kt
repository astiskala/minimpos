package io.minimpos.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The app's screens, as Navigation 3 keys. They are serialisable so the back stack survives process death, which is
 * why they carry IDs rather than loaded objects.
 */
@Serializable
sealed interface Route : NavKey {
    /** Protected routes need the admin PIN (when one is set). */
    val isProtected: Boolean get() = false

    /** The start screen with a tile per area; always at the bottom of the back stack. */
    @Serializable data object Home : Route

    /** Ringing up a sale: product tiles, custom items and the cart. */
    @Serializable data object Sale : Route

    /** References, email and saving the card, before paying. */
    @Serializable data object Checkout : Route

    /** Waiting for the terminal while the shopper pays. */
    @Serializable data object Payment : Route

    /**
     * The outcome of a payment just taken.
     *
     * @property saleId The sale that was paid.
     */
    @Serializable data class SaleResult(
        val saleId: String,
    ) : Route

    /** Scanning the refund QR code on a receipt. */
    @Serializable data object RefundScan : Route

    /**
     * Choosing what to refund; exactly one of the two is set.
     *
     * @property payload The scanned refund QR code.
     * @property saleId A sale from history.
     */
    @Serializable data class Refund(
        val payload: String? = null,
        val saleId: String? = null,
    ) : Route

    /** Waiting for the terminal to accept the refund. */
    @Serializable data object RefundProcessing : Route

    /**
     * The outcome of a refund just made.
     *
     * @property refundId The refund that was made.
     */
    @Serializable data class RefundResult(
        val refundId: String,
    ) : Route

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

    /** Showing the catalogue as QR codes for another terminal. */
    @Serializable data object CatalogueExport : Route {
        override val isProtected get() = true
    }

    /** Scanning another terminal's catalogue codes. */
    @Serializable data object CatalogueImport : Route {
        override val isProtected get() = true
    }

    /** The list of settings sections. */
    @Serializable data object Settings : Route {
        override val isProtected get() = true
    }

    /**
     * One settings section.
     *
     * @property section One of the [io.minimpos.app.feature.settings.SettingsSections] keys.
     */
    @Serializable data class SettingsSection(
        val section: String,
    ) : Route {
        override val isProtected get() = true
    }
}

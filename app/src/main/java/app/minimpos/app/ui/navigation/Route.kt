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

    /**
     * Whether this screen's actions reach Adyen or a terminal, so Home's tile for it asks for the setup to be finished
     * first; false for the local-only screens, which open straight away.
     */
    val requiresSetup: Boolean get() = true

    /** The start screen with a tile per area; the root after the first-run choice. */
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
     * Splitting a sale across tenders: the sale being rung up, or the next tender of a stored partially paid sale.
     * Cash tenders stay local, so it opens before payments are set up; card tenders pass the setup gate.
     *
     * @property saleId The stored sale to collect, or null for the sale being rung up.
     * @property cash Open cash entry for the full balance (or the next share) immediately.
     */
    @Serializable data class Split(
        val saleId: String? = null,
        val cash: Boolean = false,
    ) : Route {
        override val requiresSetup get() = false
    }

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

    /** How to find what to refund: scan a receipt, choose a payment in History, or refund without a receipt. */
    @Serializable data object RefundStart : Route {
        override val requiresManager get() = true
    }

    /** Scanning the refund QR code on a receipt. */
    @Serializable data object RefundScan : Route {
        override val requiresManager get() = true
    }

    /** An unreferenced card refund: an amount and reason, linked to no original payment. */
    @Serializable data object UnreferencedRefund : Route {
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

    /** A return split across original tenders: each leg as it is sent, then their outcomes. */
    @Serializable data object RefundLegs : Route {
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

    /** Tokens: finding a shopper’s saved cards and choosing one to charge or delete. */
    @Serializable data object SavedCard : Route {
        override val requiresManager get() = true
    }

    /**
     * Ringing up the sale to charge to a saved card, in its own cart.
     *
     * @property card The chosen card.
     */
    @Serializable data class SavedCardSale(
        val card: ChosenCard,
    ) : Route {
        override val requiresManager get() = true
    }

    /**
     * Reviewing and charging the saved card, then following the charge to its result.
     *
     * @property card The chosen card.
     */
    @Serializable data class SavedCardReview(
        val card: ChosenCard,
    ) : Route {
        override val requiresManager get() = true
    }

    /**
     * Gift cards: balance, activation, load, undoing a load, cash-out, deactivation and refunds onto a card, with the
     * activities that still need a check, a confirmation or a repair. Value changes ask for the Manager PIN themselves,
     * so balance inquiries stay an ordinary workflow.
     */
    @Serializable data object GiftCards : Route

    /** Standalone card cash-out, with separately verified support and explicit cash handover. */
    @Serializable data object Cashout : Route

    /** Records the approved cash of [saleId] handed to the shopper once. */
    @Serializable data class CashbackHandover(
        val saleId: String,
    ) : Route {
        override val requiresSetup get() = false
    }

    /**
     * Waiting for a gift-card activity's terminal requests, then its result.
     *
     * @property groupId The activity.
     */
    @Serializable data class GiftCardActivity(
        val groupId: String,
    ) : Route

    /**
     * A stored-value activity from history.
     *
     * @property groupId The activity shown.
     */
    @Serializable data class GiftCardDetail(
        val groupId: String,
    ) : Route

    /** Terminal totals compared with Mini mPOS, closing the terminal's period and today's report. */
    @Serializable data object Reconciliation : Route

    /** Sales and refunds by day; local, so it opens before payments are set up. */
    @Serializable data object History : Route {
        override val requiresSetup get() = false
    }

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

    /** The product and category list; local, so it opens before payments are set up. */
    @Serializable data object Products : Route {
        override val isProtected get() = true

        override val requiresSetup get() = false
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

    /** The list of settings sections; local, so it opens before payments are set up. */
    @Serializable data object Settings : Route {
        override val isProtected get() = true

        override val requiresSetup get() = false
    }

    /**
     * One settings section.
     *
     * @property section One of the [app.minimpos.app.feature.settings.SettingsSections] keys.
     * @property troubleshooting Opens Help with its troubleshooting table expanded and in view.
     */
    @Serializable data class SettingsSection(
        val section: String,
        val troubleshooting: Boolean = false,
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

/**
 * The saved card chosen for a charge, as navigation keeps it across process death: Adyen's token ID and its summary,
 * never a card number.
 *
 * @property id Adyen's `storedPaymentMethodId`.
 * @property brand The card brand, such as `visa`; null when Adyen sent none.
 * @property lastFour The last four digits; null when Adyen sent none.
 */
@Serializable
data class ChosenCard(
    val id: String,
    val brand: String? = null,
    val lastFour: String? = null,
)

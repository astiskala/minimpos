package app.minimpos.app.feature.home

import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.settings.CapabilityCatalog
import app.minimpos.app.feature.settings.CapabilityFeature
import app.minimpos.app.feature.settings.CapabilityStatus

/** A tile Home can show under its large one. */
internal enum class HomeTile {
    NEW_SALE,
    PRE_AUTH,
    TOKENS,
    GIFT_CARDS,
    CASHOUT,
    REFUND,
    HISTORY,
    RECONCILIATION,
}

/**
 * The tiles Home shows under its large one, in order: those that start a payment, then those for afterwards. A tile
 * is left out when its feature is Off or Unsupported in the capability catalog; one that still needs setting up or
 * checking stays, and explains when opened. Tokens are also left out without a shopper reference to look cards up by.
 * Pure: it reads only what it is given.
 *
 * @property payment What starts a payment, besides the large New sale tile.
 * @property afterwards What follows a payment; Refund and History are always there.
 */
internal data class HomeTiles(
    val payment: List<HomeTile>,
    val afterwards: List<HomeTile>,
) {
    /** Reads the tiles from the settings and the capability catalog. */
    companion object {
        private val HIDDEN = setOf(CapabilityStatus.OFF, CapabilityStatus.UNSUPPORTED)

        /**
         * The tiles for [settings] as [catalog] reads them. While payments still need setting up ([needsSetup]) the
         * large tile is the way to finish it, so New sale joins the payment tiles.
         */
        fun of(
            settings: AppSettings,
            catalog: CapabilityCatalog,
            needsSetup: Boolean,
        ): HomeTiles {
            val payment = settings.payment

            fun offered(feature: CapabilityFeature) = catalog.item(feature).status !in HIDDEN

            // Read local enablement first so switched-off tiles stay hidden before the catalog resolves their status.
            return HomeTiles(
                payment =
                    listOfNotNull(
                        HomeTile.NEW_SALE.takeIf { needsSetup },
                        HomeTile.PRE_AUTH,
                        HomeTile.TOKENS.takeIf {
                            payment.offerCardSaving && payment.shopperReferenceSource != ShopperReferenceSource.NONE
                        },
                        HomeTile.GIFT_CARDS.takeIf { catalog.giftCardsEnabled && offered(CapabilityFeature.GIFT_CARDS) },
                        HomeTile.CASHOUT.takeIf { catalog.cashoutEnabled && offered(CapabilityFeature.CASHOUT) },
                    ),
                afterwards =
                    listOfNotNull(
                        HomeTile.REFUND,
                        HomeTile.HISTORY,
                        HomeTile.RECONCILIATION.takeIf { offered(CapabilityFeature.RECONCILIATION) },
                    ),
            )
        }
    }
}

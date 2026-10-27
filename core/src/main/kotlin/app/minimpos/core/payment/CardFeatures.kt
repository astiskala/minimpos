package app.minimpos.core.payment

import app.minimpos.core.access.ManagerAction

/**
 * An optional capability of one card payment on the terminal (or of the tender it pays), as [CardFeatures] relates it
 * to the others.
 *
 * @property needsVerifiedStore Whether it is sent only with the verified store assignment and exact setup it was
 *   offered for.
 * @property configuredApproval Whether its Manager permission needs a configured Manager PIN, never just public access.
 * @property onlyOnTerminal Whether it is chosen at checkout for the terminal card payment and no other tender (payment
 *   link, scanned wallet, token or split) carries it, so those are not offered while it is chosen.
 */
enum class CardFeature(
    val needsVerifiedStore: Boolean = false,
    val configuredApproval: Boolean = false,
    val onlyOnTerminal: Boolean = false,
) {
    /** The amount is held for a later capture (a pre-authorization, or a sale taken for tipping on the receipt). */
    PRE_AUTHORISATION,

    /** The sale is taken for a tip written on the paper receipt. */
    TIP_ON_RECEIPT,

    /** The terminal asks for a tip. */
    TERMINAL_TIP,

    /** The terminal shows the basket and asks the shopper around the payment. */
    SHOPPER_DISPLAY,

    /** The terminal invites an Adyen Giving donation. */
    GIVING,

    /** The terminal guides the shopper by audio. */
    AUDIO_GUIDANCE(onlyOnTerminal = true),

    /** The issuer may approve less than requested. */
    PARTIAL_AUTHORISATION,

    /** The card is saved under the shopper reference. */
    TOKENIZATION,

    /** MOTO or manual key entry instead of presenting the card. */
    SPECIAL_ENTRY,

    /** A shopper-presented wallet code pays. */
    SCANNED_WALLET(needsVerifiedStore = true),

    /** A gift card pays. */
    GIFT_CARD,

    /** A meal voucher pays. */
    MEAL_VOUCHER(needsVerifiedStore = true),

    /** Mexican debit/credit routing, optionally with monthly payments. */
    MEXICAN_PLAN(needsVerifiedStore = true, onlyOnTerminal = true),

    /** Cash is requested with the card payment. */
    CASHBACK(needsVerifiedStore = true, configuredApproval = true, onlyOnTerminal = true),

    /** The tender pays a frozen share or the items allocated to it. */
    ALLOCATION,

    /** The tender pays the balance of an already stored sale. */
    FURTHER_TENDER,

    /** The payment request refunds a card with no original payment. */
    UNREFERENCED_REFUND(configuredApproval = true),
}

/**
 * The one table of how card features combine and what they require, read where a checkout offers them, where a payment
 * request is validated, where the Terminal API request is checked against its destination and where its Manager
 * permissions are decided, so none can disagree.
 */
object CardFeatures {
    // Each feature with what it cannot be combined with; excludes reads the table in both directions.
    private val excluded: Map<CardFeature, Set<CardFeature>> =
        mapOf(
            CardFeature.CASHBACK to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.TERMINAL_TIP,
                    CardFeature.GIVING,
                    CardFeature.AUDIO_GUIDANCE,
                    CardFeature.PARTIAL_AUTHORISATION,
                    CardFeature.TOKENIZATION,
                    CardFeature.SPECIAL_ENTRY,
                    CardFeature.SCANNED_WALLET,
                    CardFeature.GIFT_CARD,
                    CardFeature.MEAL_VOUCHER,
                    CardFeature.MEXICAN_PLAN,
                    CardFeature.ALLOCATION,
                    CardFeature.FURTHER_TENDER,
                ),
            CardFeature.MEAL_VOUCHER to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.TERMINAL_TIP,
                    CardFeature.SHOPPER_DISPLAY,
                    CardFeature.GIVING,
                    CardFeature.AUDIO_GUIDANCE,
                    CardFeature.TOKENIZATION,
                    CardFeature.SPECIAL_ENTRY,
                    CardFeature.SCANNED_WALLET,
                    CardFeature.GIFT_CARD,
                    CardFeature.MEXICAN_PLAN,
                    CardFeature.ALLOCATION,
                ),
            CardFeature.MEXICAN_PLAN to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.TERMINAL_TIP,
                    CardFeature.GIVING,
                    CardFeature.AUDIO_GUIDANCE,
                    CardFeature.PARTIAL_AUTHORISATION,
                    CardFeature.TOKENIZATION,
                    CardFeature.SPECIAL_ENTRY,
                    CardFeature.SCANNED_WALLET,
                    CardFeature.GIFT_CARD,
                    CardFeature.ALLOCATION,
                    CardFeature.FURTHER_TENDER,
                ),
            CardFeature.GIFT_CARD to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.TERMINAL_TIP,
                    CardFeature.SHOPPER_DISPLAY,
                    CardFeature.GIVING,
                    CardFeature.AUDIO_GUIDANCE,
                    CardFeature.TOKENIZATION,
                    CardFeature.SPECIAL_ENTRY,
                    CardFeature.SCANNED_WALLET,
                ),
            CardFeature.AUDIO_GUIDANCE to
                setOf(
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.TERMINAL_TIP,
                    CardFeature.SHOPPER_DISPLAY,
                    CardFeature.GIVING,
                    CardFeature.PARTIAL_AUTHORISATION,
                    CardFeature.SPECIAL_ENTRY,
                    CardFeature.SCANNED_WALLET,
                ),
            CardFeature.TERMINAL_TIP to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                    CardFeature.SCANNED_WALLET,
                ),
            CardFeature.SCANNED_WALLET to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                    CardFeature.TIP_ON_RECEIPT,
                ),
            CardFeature.TIP_ON_RECEIPT to
                setOf(
                    CardFeature.PRE_AUTHORISATION,
                ),
        )

    /** Whether [first] and [second] cannot be part of one payment, in either order. */
    fun excludes(
        first: CardFeature,
        second: CardFeature,
    ): Boolean = second in excluded[first].orEmpty() || first in excluded[second].orEmpty()

    /** Two of [features] that cannot be part of one payment; null when they all combine. */
    fun conflict(features: Set<CardFeature>): Pair<CardFeature, CardFeature>? =
        features.firstNotNullOfOrNull { first -> features.firstOrNull { excludes(first, it) }?.let { first to it } }

    /** Whether [feature] combines with every other feature in [others]. */
    fun allows(
        feature: CardFeature,
        others: Set<CardFeature>,
    ): Boolean = others.none { excludes(feature, it) }

    /**
     * The Manager permissions a payment with [features] needs: [collection] for what it charges (a sale or
     * pre-authorization; null when it charges no purchase, such as cash alone or an unreferenced refund), plus the
     * permission of each feature that has one.
     */
    fun managerActions(
        features: Set<CardFeature>,
        collection: ManagerAction?,
    ): Set<ManagerAction> =
        buildSet {
            collection?.let(::add)
            if (CardFeature.CASHBACK in features) add(ManagerAction.CASHOUT)
            if (CardFeature.UNREFERENCED_REFUND in features) add(ManagerAction.UNREFERENCED_REFUND)
            if (CardFeature.SPECIAL_ENTRY in features) add(ManagerAction.MANUAL_ENTRY)
        }
}

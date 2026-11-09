package app.minimpos.app.data.settings

import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardProvider
import kotlinx.serialization.Serializable

/** A checkout option the first-run demo turns on, so its flow can be tried with the simulator; stored by name. */
@Serializable
enum class DemoFeature {
    /** Terminal tips offered with their checkout switch off, with tip suggestions sent with each payment. */
    TERMINAL_TIPPING,

    /** Adyen Giving offered with its checkout switch off. */
    GIVING,

    /** An SVS gift-card provider whose cards are swiped or keyed on the terminal. */
    SVS_GIFT_CARDS,
}

/**
 * What the first-run demo turns on and how leaving the demo turns it off again; pure. A feature is turned on only while
 * its settings are still at their new-installation values, and turned off only while they are still at their demo
 * values, so the merchant's own choices are never replaced.
 */
object DemoFeatures {
    /** The gift-card provider the demo turns on; the simulator answers for its cards. */
    val SVS: GiftCardSetting = GiftCardSetting(GiftCardProvider.SVS, entries = setOf(GiftCardEntry.SWIPED, GiftCardEntry.KEYED))

    /** [settings] with every demo feature still at its new-installation values turned on and recorded. */
    fun applyTo(settings: AppSettings): AppSettings {
        val applied = DemoFeature.entries.filter { it.untouched(settings.payment) }
        val payment = applied.fold(settings.payment) { payment, feature -> feature.on(payment) }
        return settings.copy(payment = payment, demoFeatures = settings.demoFeatures + applied)
    }

    /** The recorded demo features of [settings] that are still at their demo values, in declaration order. */
    fun active(settings: AppSettings): List<DemoFeature> =
        DemoFeature.entries.filter {
            it in settings.demoFeatures &&
                it.isOn(settings.payment)
        }

    /** [settings] with its [active] demo features back at their new-installation values, and none recorded. */
    fun turnOff(settings: AppSettings): AppSettings =
        settings.copy(
            payment = active(settings).fold(settings.payment) { payment, feature -> feature.off(payment) },
            demoFeatures = emptySet(),
        )

    /** [settings] keeping the demo features as the merchant's own choices, so none are recorded any more. */
    fun keep(settings: AppSettings): AppSettings = settings.copy(demoFeatures = emptySet())

    private fun DemoFeature.untouched(payment: PaymentSettings): Boolean =
        when (this) {
            DemoFeature.TERMINAL_TIPPING -> payment.terminalTipping == ReceiptTipping.DISABLED && !payment.dynamicTipOverride
            DemoFeature.GIVING -> payment.askGiving == ReceiptTipping.DISABLED
            DemoFeature.SVS_GIFT_CARDS -> payment.giftCards.none { it.provider == GiftCardProvider.SVS }
        }

    private fun DemoFeature.isOn(payment: PaymentSettings): Boolean =
        when (this) {
            DemoFeature.TERMINAL_TIPPING -> payment.terminalTipping == ReceiptTipping.DEFAULT_OFF && payment.dynamicTipOverride
            DemoFeature.GIVING -> payment.askGiving == ReceiptTipping.DEFAULT_OFF
            DemoFeature.SVS_GIFT_CARDS -> SVS in payment.giftCards
        }

    private fun DemoFeature.on(payment: PaymentSettings): PaymentSettings =
        when (this) {
            DemoFeature.TERMINAL_TIPPING -> payment.copy(terminalTipping = ReceiptTipping.DEFAULT_OFF, dynamicTipOverride = true)
            DemoFeature.GIVING -> payment.copy(askGiving = ReceiptTipping.DEFAULT_OFF)
            DemoFeature.SVS_GIFT_CARDS -> payment.copy(giftCards = payment.giftCards + SVS)
        }

    private fun DemoFeature.off(payment: PaymentSettings): PaymentSettings =
        when (this) {
            DemoFeature.TERMINAL_TIPPING -> payment.copy(terminalTipping = ReceiptTipping.DISABLED, dynamicTipOverride = false)
            DemoFeature.GIVING -> payment.copy(askGiving = ReceiptTipping.DISABLED)
            DemoFeature.SVS_GIFT_CARDS -> payment.copy(giftCards = payment.giftCards - SVS)
        }
}

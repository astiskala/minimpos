package app.minimpos.app.data

import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.DemoFeature
import app.minimpos.app.data.settings.DemoFeatures
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.core.giftcard.GiftCardProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [DemoFeatures]: the first-run demo's checkout options, turned on and off without replacing merchant choices. */
class DemoFeaturesTest {
    @Test
    fun `the demo turns on only untouched options and leaving it turns off only unchanged ones`() {
        val demo = DemoFeatures.applyTo(AppSettings())
        assertThat(demo.demoFeatures).containsExactlyElementsIn(DemoFeature.entries)
        assertThat(demo.payment.terminalTipping).isEqualTo(ReceiptTipping.DEFAULT_OFF)
        assertThat(demo.payment.dynamicTipOverride).isTrue()
        assertThat(demo.payment.askGiving).isEqualTo(ReceiptTipping.DEFAULT_OFF)
        assertThat(demo.payment.offeredGiftCards).containsExactly(DemoFeatures.SVS)
        assertThat(DemoFeatures.active(demo)).containsExactlyElementsIn(DemoFeature.entries).inOrder()
        assertThat(DemoFeatures.turnOff(demo)).isEqualTo(AppSettings())

        // The merchant's own choices are neither replaced nor recorded as the demo's.
        val merchant =
            AppSettings(
                payment = PaymentSettings(askGiving = ReceiptTipping.DEFAULT_ON, giftCards = listOf(GiftCardSetting(GiftCardProvider.SVS))),
            )
        val applied = DemoFeatures.applyTo(merchant)
        assertThat(applied.demoFeatures).containsExactly(DemoFeature.TERMINAL_TIPPING)
        assertThat(applied.payment.copy(terminalTipping = ReceiptTipping.DISABLED, dynamicTipOverride = false)).isEqualTo(merchant.payment)

        // Options changed since the demo stay as the merchant left them.
        val edited = demo.copy(payment = demo.payment.copy(askGiving = ReceiptTipping.DEFAULT_ON, dynamicTipOverride = false))
        assertThat(DemoFeatures.active(edited)).containsExactly(DemoFeature.SVS_GIFT_CARDS)
        val off = DemoFeatures.turnOff(edited)
        assertThat(off.payment.askGiving).isEqualTo(ReceiptTipping.DEFAULT_ON)
        assertThat(off.payment.terminalTipping).isEqualTo(ReceiptTipping.DEFAULT_OFF)
        assertThat(off.payment.giftCards).isEmpty()
        assertThat(off.demoFeatures).isEmpty()
    }

    @Test
    fun `kept options become the merchant's and the record never leaves this device`() {
        val demo = DemoFeatures.applyTo(AppSettings())
        val kept = DemoFeatures.keep(demo)
        assertThat(kept.payment).isEqualTo(demo.payment)
        assertThat(DemoFeatures.active(kept)).isEmpty()
        assertThat(demo.shared().demoFeatures).isEmpty()
        assertThat(demo.shared().payment).isEqualTo(demo.payment)
        assertThat(demo.shared().withDeviceFieldsOf(demo).demoFeatures).isEqualTo(demo.demoFeatures)
        // Settings imported from another device are the merchant's own, so nothing is offered for turning off.
        assertThat(demo.takingOver(demo.shared(), null).demoFeatures).isEmpty()
        assertThat(DemoFeatures.active(AppSettings().takingOver(demo.shared(), null))).isEmpty()
    }
}

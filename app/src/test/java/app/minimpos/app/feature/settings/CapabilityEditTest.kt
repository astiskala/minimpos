package app.minimpos.app.feature.settings

import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.MotoCapability
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test

class CapabilityEditTest {
    private val baseline = CapabilityDraft()
    private val incomplete = CapabilityDraft(motoLimit = CapabilityLimit(false, "2147483648"))

    @Test
    fun `refresh adopts confirmed remote values when an uncertain draft was reverted to clean`() {
        val uncertain = CapabilityEdit(baseline, unconfirmed = true)
        val current = baseline.copy(enabled = true)
        assertThat(uncertain.read(current, 0).refreshed(current)).isEqualTo(CapabilityEdit(current))
    }

    @Test
    fun `discard clears local text but never clears remote uncertainty`() {
        val edit = CapabilityEdit(baseline, incomplete)
        assertThat(edit.discard(baseline)).isEqualTo(CapabilityEdit(baseline))
        val unknown = edit.copy(unconfirmed = true).discard(baseline)
        assertThat(unknown.dirty).isFalse()
        assertThat(unknown.unconfirmed).isTrue()
    }

    @Test
    fun `MOTO currency changes conflict with pending amounts even if numeric remote values are unchanged`() {
        val reading = AdyenCapabilitiesState(moto = MotoCapability(true, 100), motoCurrency = "EUR")
        val initial = CapabilityDraft.from(CapabilityFeature.MOTO, reading, "EUR")
        val edit = CapabilityEdit(initial, initial.copy(motoLimit = CapabilityLimit(false, "150")))
        val current = CapabilityDraft.from(CapabilityFeature.MOTO, reading.copy(motoCurrency = "JPY"), "EUR")
        assertThat(current).isNotEqualTo(edit.baseline)
        assertThat(
            edit
                .read(current, 0)
                .refreshed(current)
                .draft.motoCurrency,
        ).isEqualTo("EUR")
    }

    @Test
    fun `refresh and another feature's save retain invalid dirty text`() {
        val edit = CapabilityEdit(baseline, incomplete)
        assertThat(edit.read(baseline, 0)).isEqualTo(edit)
        val changedAtAdyen = baseline.copy(enabled = true)
        val refreshed = edit.read(changedAtAdyen, 0).refreshed(changedAtAdyen)
        assertThat(refreshed.draft).isEqualTo(incomplete)
        assertThat(refreshed.baseline).isEqualTo(baseline)
        assertThat(refreshed.dirty).isTrue()
    }

    @Test
    fun `only this feature's confirmed save adopts its readback`() {
        val edit = CapabilityEdit(baseline, baseline.copy(enabled = true))
        val readback = baseline.copy(enabled = true)
        assertThat(edit.read(readback, 0).dirty).isTrue()
        assertThat(edit.read(readback, 1)).isEqualTo(CapabilityEdit(readback, savedRevision = 1))
        assertThat(CapabilityEdit(baseline).read(readback, 0).draft).isEqualTo(readback)
    }

    @Test
    fun `unknown save survives read and recreation until explicit successful refresh`() {
        val edit = CapabilityEdit(baseline, baseline.copy(enabled = true), unconfirmed = true)
        val restored = Json.decodeFromString(serializer<CapabilityEdit>(), Json.encodeToString(serializer<CapabilityEdit>(), edit))
        val saved = baseline.copy(enabled = true)
        assertThat(restored.read(saved, 0)).isEqualTo(edit)
        assertThat(restored.refreshed(saved)).isEqualTo(CapabilityEdit(saved))
        assertThat(restored.refreshed(baseline).draft).isEqualTo(saved)
        assertThat(restored.refreshed(baseline).dirty).isTrue()
    }

    @Test
    fun `a restored draft waits for a settled reading and only another setup starts again`() {
        val restored = CapabilityEdit(baseline, incomplete, identity = "setup")
        val current = baseline.copy(enabled = true)
        assertThat(restored.follow(AdyenCapabilitiesState(), baseline, 0)).isEqualTo(restored)
        val checking = AdyenCapabilitiesState(identity = "setup", checked = true, checking = true)
        assertThat(restored.follow(checking, current, 0)).isEqualTo(restored)
        assertThat(restored.follow(checking.copy(checking = false), current, 0)).isEqualTo(restored)
        val other = AdyenCapabilitiesState(identity = "other", checked = true)
        assertThat(restored.follow(other, current, 0)).isEqualTo(CapabilityEdit(current, identity = "other"))
    }

    @Test
    fun `recreated action counter cannot discard newer unsaved text`() {
        val restored = CapabilityEdit(baseline, incomplete, savedRevision = 2)
        val read = restored.read(baseline, 0)
        assertThat(read.draft).isEqualTo(incomplete)
        assertThat(read.dirty).isTrue()
        assertThat(read.read(incomplete, 1).dirty).isFalse()
    }
}

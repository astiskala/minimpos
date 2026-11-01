package app.minimpos.app.feature.settings

import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.LimitUpdate
import app.minimpos.app.terminal.OfflineCapability
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test

class CapabilityDraftTest {
    @Test
    fun `invalid or overflowing MOTO text never becomes an inherited limit`() {
        listOf("", "not an amount", "-1", "0", "1.50", "2147483648").forEach { text ->
            val draft = CapabilityDraft(enabled = true, motoLimit = CapabilityLimit(false, text))
            assertThat(draft.valid(CapabilityFeature.MOTO, "EUR")).isFalse()
        }
        val inherited = CapabilityDraft(enabled = true, motoLimit = CapabilityLimit(true, "invalid"))
        assertThat(inherited.valid(CapabilityFeature.MOTO, "EUR")).isTrue()
        assertThat(CapabilityLimit(false, "2147483647.00").moto()).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `only a changed limit is sent, so an inherited effective value is never pinned`() {
        val read = CapabilityLimit(false, "500")
        assertThat(read.update(read, CapabilityLimit::moto)).isEqualTo(LimitUpdate.Keep)
        assertThat(CapabilityLimit(false, "500.00").update(read, CapabilityLimit::moto)).isEqualTo(LimitUpdate.Keep)
        assertThat(CapabilityLimit(true, "500").update(read, CapabilityLimit::moto)).isEqualTo(LimitUpdate.Inherit)
        assertThat(CapabilityLimit(false, "600").update(read, CapabilityLimit::moto)).isEqualTo(LimitUpdate.Own(600))
        val inherited = CapabilityLimit(true)
        assertThat(CapabilityLimit(true, "1").update(inherited, CapabilityLimit::integer)).isEqualTo(LimitUpdate.Keep)
        assertThat(CapabilityLimit(false, "0").update(inherited, CapabilityLimit::integer)).isEqualTo(LimitUpdate.Own(0))
    }

    @Test
    fun `offline fields round trip familiar currency amounts without rounding or overflow`() {
        val reading =
            AdyenCapabilitiesState(
                offline = OfflineCapability(chipFloorLimit = 1250, maxStoreAndForwardMinor = mapOf("EUR" to 12_345), maxPayments = 7),
            )
        val draft = CapabilityDraft.from(CapabilityFeature.OFFLINE, reading, "EUR")
        assertThat(draft.chip.text).isEqualTo("12.50")
        assertThat(draft.chip.amount("EUR")).isEqualTo(1250)
        assertThat(draft.storeAndForward.text).isEqualTo("123.45")
        assertThat(draft.valid(CapabilityFeature.OFFLINE, "EUR")).isTrue()
        listOf("", "1.001", "21474836.48", "-1", "1,000.00").forEach { value ->
            assertThat(draft.copy(chip = CapabilityLimit(false, value)).valid(CapabilityFeature.OFFLINE, "EUR")).isFalse()
        }
        assertThat(CapabilityLimit(false, "1.5").amount("JPY")).isNull()
        assertThat(CapabilityLimit(false, "1.234").amount("BHD")).isEqualTo(1234)
        assertThat(CapabilityLimit(false, "1.23").amount("ISK")).isEqualTo(123)
        assertThat(CapabilityLimit(false, "0").integer()).isEqualTo(0)
        assertThat(CapabilityLimit(false, "1.5").integer()).isNull()
        assertThat(CapabilityLimit(false, "2147483648").integer()).isNull()
    }

    @Test
    fun `every tip option is validated and a fourth option is never silently truncated`() {
        val four = CapabilityDraft(enabled = true, tips = listOf("0", "10", "15", "20").map { CapabilityTip(text = it) })
        assertThat(four.valid(CapabilityFeature.TIPPING, "EUR")).isTrue()
        assertThat(four.copy(custom = true).valid(CapabilityFeature.TIPPING, "EUR")).isFalse()
        assertThat(four.gratuity("EUR")?.predefinedEntries).containsExactly("0%", "10%", "15%", "20%").inOrder()
        assertThat(four.copy(tips = four.tips.take(3), custom = true).valid(CapabilityFeature.TIPPING, "EUR")).isTrue()
        assertThat(four.copy(tips = emptyList()).valid(CapabilityFeature.TIPPING, "EUR")).isFalse()
        assertThat(
            four.copy(tips = listOf(CapabilityTip(text = "10"), CapabilityTip(text = "010"))).valid(CapabilityFeature.TIPPING, "EUR"),
        ).isFalse()
        listOf("", "-1", "101", "10.5").forEach { value -> assertThat(CapabilityTip(text = value).entry("EUR")).isNull() }
        assertThat(CapabilityTip(false, "12,34").entry("EUR")).isEqualTo("1234")
        assertThat(CapabilityTip(false, "1.234").entry("EUR")).isNull()
    }

    @Test
    fun `draft serialization preserves incomplete text and explicit inheritance across recreation`() {
        val draft =
            CapabilityDraft(
                enabled = true,
                motoLimit = CapabilityLimit(false, "2147483648"),
                tips = listOf(CapabilityTip(text = ""), CapabilityTip(false, "1,")),
            )
        val restored = Json.decodeFromString(serializer<CapabilityDraft>(), Json.encodeToString(serializer<CapabilityDraft>(), draft))
        assertThat(restored).isEqualTo(draft)
        assertThat(restored.valid(CapabilityFeature.MOTO, "EUR")).isFalse()
    }

    @Test
    fun `only the four existing editors can produce a valid remote draft`() {
        val draft = CapabilityDraft()
        CapabilityFeature.entries.filterNot { it.editableAtAdyen }.forEach {
            assertThat(draft.valid(it, "EUR")).isFalse()
            assertThat(it.editorResult).isNull()
        }
        assertThat(draft.valid(CapabilityFeature.DCC, "EUR")).isTrue()
        assertThat(draft.valid(CapabilityFeature.OFFLINE, "EUR")).isTrue()
        assertThat(draft.gratuity("EUR")).isNull()
    }
}

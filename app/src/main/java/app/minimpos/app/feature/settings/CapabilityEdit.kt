package app.minimpos.app.feature.settings

import app.minimpos.app.terminal.AdyenCapabilitiesState
import kotlinx.serialization.Serializable

/**
 * Saveable state for one editor. Reads and other editors' saves preserve dirty input, including invalid text.
 * @property baseline Last accepted reading.
 * @property draft Current unsaved fields.
 * @property unconfirmed A write may have taken effect; only an explicit successful refresh clears this.
 * @property savedRevision Latest successful save observed for this feature.
 * @property identity Setup identity of the reading [baseline] came from, which a save is bound to; null before one.
 */
@Serializable
internal data class CapabilityEdit(
    val baseline: CapabilityDraft,
    val draft: CapabilityDraft = baseline,
    val unconfirmed: Boolean = false,
    val savedRevision: Int = 0,
    val identity: String? = null,
) {
    /** Whether leaving would lose entered text. */
    val dirty: Boolean get() = draft != baseline

    /**
     * Follows [reading], whose draft is [current]: a restored edit waits for a settled reading, then starts again only
     * when that reading is for another setup, so recreation never discards this setup's draft.
     */
    fun follow(
        reading: AdyenCapabilitiesState,
        current: CapabilityDraft,
        revision: Int,
    ): CapabilityEdit =
        when {
            !reading.checked || reading.checking || reading.identity == null -> this
            reading.identity != identity -> CapabilityEdit(current, savedRevision = revision, identity = reading.identity)
            else -> read(current, revision)
        }

    /** Adopts only clean reads or this feature's confirmed save, never a different feature's save. */
    fun read(
        current: CapabilityDraft,
        revision: Int,
    ): CapabilityEdit =
        when {
            revision > savedRevision -> CapabilityEdit(current, savedRevision = revision, identity = identity)
            revision < savedRevision -> copy(savedRevision = revision)
            !dirty && !unconfirmed -> copy(baseline = current, draft = current)
            else -> this
        }

    /** Starts again from [current], keeping the save counter and setup identity. */
    fun reload(current: CapabilityDraft): CapabilityEdit = CapabilityEdit(current, savedRevision = savedRevision, identity = identity)

    /** Resolves uncertainty without discarding edits; differing remote values still require conflict review. */
    fun refreshed(current: CapabilityDraft): CapabilityEdit = if (!dirty || draft == current) reload(current) else copy(unconfirmed = false)

    /** Discards local input without pretending an uncertain remote write was resolved. */
    fun discard(current: CapabilityDraft): CapabilityEdit = if (unconfirmed) copy(draft = baseline) else reload(current)
}

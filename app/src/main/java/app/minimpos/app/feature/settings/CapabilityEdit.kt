package app.minimpos.app.feature.settings

import kotlinx.serialization.Serializable

/**
 * Saveable state for one editor. Reads and other editors' saves preserve dirty input, including invalid text.
 * @property baseline Last accepted reading.
 * @property draft Current unsaved fields.
 * @property unconfirmed A write may have taken effect; only an explicit successful refresh clears this.
 * @property savedRevision Latest successful save observed for this feature.
 */
@Serializable
internal data class CapabilityEdit(
    val baseline: CapabilityDraft,
    val draft: CapabilityDraft = baseline,
    val unconfirmed: Boolean = false,
    val savedRevision: Int = 0,
) {
    /** Whether leaving would lose entered text. */
    val dirty: Boolean get() = draft != baseline

    /** Adopts only clean reads or this feature's confirmed save, never a different feature's save. */
    fun read(
        current: CapabilityDraft,
        revision: Int,
    ): CapabilityEdit =
        when {
            revision > savedRevision -> CapabilityEdit(current, savedRevision = revision)
            revision < savedRevision -> copy(savedRevision = revision)
            !dirty && !unconfirmed -> copy(baseline = current, draft = current)
            else -> this
        }

    /** Resolves uncertainty without discarding edits; differing remote values still require conflict review. */
    fun refreshed(current: CapabilityDraft): CapabilityEdit =
        if (!dirty || draft == current) CapabilityEdit(current, savedRevision = savedRevision) else copy(unconfirmed = false)

    /** Discards local input without pretending an uncertain remote write was resolved. */
    fun discard(current: CapabilityDraft): CapabilityEdit =
        if (unconfirmed) copy(draft = baseline) else CapabilityEdit(current, savedRevision = savedRevision)
}

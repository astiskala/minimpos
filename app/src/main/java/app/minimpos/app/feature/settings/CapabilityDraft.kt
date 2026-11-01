package app.minimpos.app.feature.settings

import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability
import app.minimpos.core.money.CurrencySpec
import kotlinx.serialization.Serializable

/** An explicit inheritance choice; malformed text is never a request to clear a remote limit. */
@Serializable
internal data class CapabilityLimit(
    val inherited: Boolean = true,
    val text: String = "",
) {
    /** Parses a nonnegative integer; null is invalid text unless [inherited] was explicitly selected. */
    fun integer(): Int? = text.trim().toIntOrNull()?.takeIf { it >= 0 }

    /** Parses familiar currency text to bounded Management minor units without rounding. */
    fun amount(currency: String): Int? = managementMinor(text, currency)

    /** MOTO's existing Management contract uses whole major units, unlike offline limits. */
    fun moto(): Int? =
        text.trim().takeIf { it.matches(Regex("[0-9]+([.,][0-9]+)?")) }?.replace(',', '.')?.toBigDecimalOrNull()?.let { amount ->
            runCatching { amount.intValueExact() }.getOrNull()?.takeIf { it > 0 }
        }
}

/** One terminal tip button, edited as a percentage or a familiar currency amount, never raw minor units. */
@Serializable
internal data class CapabilityTip(
    val percentage: Boolean = true,
    val text: String = "",
) {
    /** Valid wire entry, or null for incomplete, out-of-range or over-precise input. */
    fun entry(currency: String): String? =
        if (percentage) {
            text
                .trim()
                .toIntOrNull()
                ?.takeIf { it in 0..MAX_PERCENT }
                ?.let { "$it%" }
        } else {
            managementMinor(text, currency)?.toString()
        }

    private companion object {
        const val MAX_PERCENT = 100
    }
}

/** Converts exact currency input to nonnegative Management minor units, or null for invalid text or Int overflow. */
internal fun managementMinor(
    text: String,
    currency: String,
): Int? =
    CurrencySpec
        .of(currency)
        .parseMinor(text)
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()

/**
 * A single feature's unsaved fields. Readings never mutate this draft; only an explicit reload or its own confirmed
 * save replaces it. Unused fields retain their values when their controls are switched off.
 */
@Serializable
internal data class CapabilityDraft(
    val enabled: Boolean = false,
    val predefined: Boolean = true,
    val tips: List<CapabilityTip> = emptyList(),
    val custom: Boolean = false,
    val motoLimit: CapabilityLimit = CapabilityLimit(),
    val motoCurrency: String = "",
    val chip: CapabilityLimit = CapabilityLimit(),
    val storeAndForward: CapabilityLimit = CapabilityLimit(),
    val count: CapabilityLimit = CapabilityLimit(),
) {
    /** Whether every value this feature would send is valid, including overflow and tip-button count. */
    fun valid(
        feature: CapabilityFeature,
        currency: String,
    ): Boolean =
        when (feature.editorResult) {
            CapabilityResult.GRATUITY -> {
                validTips(currency)
            }

            CapabilityResult.MOTO -> {
                motoLimit.inherited || motoLimit.moto() != null
            }

            CapabilityResult.OFFLINE -> {
                (chip.inherited || chip.amount(currency) != null) &&
                    (storeAndForward.inherited || storeAndForward.amount(currency) != null) &&
                    (count.inherited || count.integer() != null)
            }

            CapabilityResult.DCC -> {
                true
            }

            null -> {
                false
            }
        }

    /** Available physical buttons, reserving one for a custom amount when selected. */
    val maxTips: Int get() = if (custom) MAX_TIPS - 1 else MAX_TIPS

    private fun validTips(currency: String): Boolean =
        !enabled || !predefined ||
            (
                tips.size in 1..maxTips && tips.all { it.entry(currency) != null } &&
                    tips.map { it.entry(currency) }.distinct().size == tips.size
            )

    /** Target gratuity settings, only after [valid] has succeeded; no entries are dropped or truncated. */
    fun gratuity(currency: String): GratuityCapability? =
        if (!enabled) {
            null
        } else {
            GratuityCapability(
                currency,
                predefined,
                if (predefined) tips.map { checkNotNull(it.entry(currency)) } else emptyList(),
                predefined && custom,
            )
        }

    /** Target MOTO settings, only after [valid] has succeeded. */
    fun moto(): MotoCapability = MotoCapability(enabled, if (motoLimit.inherited) null else checkNotNull(motoLimit.moto()))

    companion object {
        private const val MAX_TIPS = 4

        /** Starts from a successful reading; only this feature's fields participate in its baseline. */
        fun from(
            feature: CapabilityFeature,
            reading: AdyenCapabilitiesState,
            currency: String,
        ): CapabilityDraft =
            when (feature.editorResult) {
                CapabilityResult.GRATUITY -> {
                    val gratuity = reading.gratuityFor(currency)
                    CapabilityDraft(
                        enabled = gratuity != null,
                        predefined = gratuity?.usePredefinedEntries != false,
                        custom = gratuity?.allowCustomAmount == true,
                        tips = gratuity?.predefinedEntries.orEmpty().map { tip(it, currency) },
                    )
                }

                CapabilityResult.DCC -> {
                    CapabilityDraft(enabled = reading.dccEnabled)
                }

                CapabilityResult.MOTO -> {
                    CapabilityDraft(
                        enabled = reading.moto.enabled,
                        motoCurrency = reading.motoCurrency,
                        motoLimit =
                            CapabilityLimit(
                                reading.moto.maxAmount == null,
                                reading.moto.maxAmount
                                    ?.toString()
                                    .orEmpty(),
                            ),
                    )
                }

                CapabilityResult.OFFLINE -> {
                    CapabilityDraft(
                        chip = limit(reading.offline.chipFloorLimit?.toLong(), currency),
                        storeAndForward = limit(reading.offline.maxStoreAndForwardMinor[currency], currency),
                        count =
                            CapabilityLimit(
                                reading.offline.maxPayments == null,
                                reading.offline.maxPayments
                                    ?.toString()
                                    .orEmpty(),
                            ),
                    )
                }

                null -> {
                    CapabilityDraft()
                }
            }

        private fun limit(
            minor: Long?,
            currency: String,
        ): CapabilityLimit = CapabilityLimit(minor == null, minor?.let { CurrencySpec.of(currency).toMajor(it).toPlainString() }.orEmpty())

        private fun tip(
            entry: String,
            currency: String,
        ): CapabilityTip =
            if (entry.endsWith('%')) {
                CapabilityTip(true, entry.dropLast(1))
            } else {
                CapabilityTip(false, entry.toLongOrNull()?.let { CurrencySpec.of(currency).toMajor(it).toPlainString() } ?: entry)
            }
    }
}

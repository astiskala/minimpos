package app.minimpos.terminal.client

/** Numeric Adyen terminal software eligibility; never guesses support from an unknown diagnosis version. */
object TerminalSoftware {
    private val versionPattern = Regex("^(\\d+)\\.(\\d+)(?:\\.\\d+)?(?:[-_][A-Za-z0-9.-]+)?$")

    /**
     * Whether installed [version] meets [minimum] (numeric major/minor, with optional patch and build suffix).
     * Missing, malformed or overflowing major/minor versions never establish support.
     */
    fun atLeast(
        version: String?,
        minimum: String,
    ): Boolean {
        fun parts(value: String?): List<Int>? {
            val matched = value?.trim()?.let(versionPattern::matchEntire) ?: return null
            return matched.groupValues
                .drop(1)
                .take(2)
                .map { it.toIntOrNull() ?: return null }
        }
        val installed = parts(version) ?: return false
        val required = parts(minimum) ?: return false
        return installed[0] > required[0] || (installed[0] == required[0] && installed[1] >= required[1])
    }
}

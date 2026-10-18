package app.minimpos.app.data.settings

import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.serializer

/**
 * The one owner of what the verified-setup fingerprint ([AppSettings.verifiedSetup]) covers. The fingerprint is a
 * digest of the [connectionKey] of the setup a connection check verified, with the facts it resolved, and of the
 * encrypted payment secrets it uses ([app.minimpos.app.data.security.SecretStore] computes it). A settings write that
 * changes the covered connection fields clears it ([invalidatedBy]); a changed secret or resolved fact no longer
 * matches it. Pure.
 */
internal object VerifiedSetup {
    /**
     * The connection facts of [terminal] the fingerprint covers: every stored field except the learned ones
     * ([TerminalSettings.withoutLearnedFacts]), with the resolved [poiId], [environment] and [destination] (null or
     * blank when not resolved).
     */
    fun connectionKey(
        terminal: TerminalSettings,
        poiId: String?,
        environment: TerminalEnvironment?,
        destination: TerminalMode,
    ): String =
        StorageJson.encodeToString(serializer<TerminalSettings>(), terminal.withoutLearnedFacts()) +
            ":terminal-settings-write:${destination.name}:${poiId.orEmpty()}:${environment?.name.orEmpty()}"

    /** Whether writing [after] over [before] changes connection fields the fingerprint covers, so it must be cleared. */
    fun invalidatedBy(
        before: TerminalSettings,
        after: TerminalSettings,
    ): Boolean = connectionKey(before, null, null, before.mode) != connectionKey(after, null, null, after.mode)
}

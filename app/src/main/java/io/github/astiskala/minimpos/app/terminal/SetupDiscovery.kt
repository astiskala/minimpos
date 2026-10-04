package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional setup discovery through Management, independent of payment readiness.
 * @param setups Unlocks the API key through the existing setup boundary.
 * @param settings Saves selected connection fields.
 * @param savePassphrase Encrypts a retrieved passphrase through the container's secret-store writer.
 * @param connect Builds read-only Management access; tests inject a fake.
 */
class SetupDiscovery(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val savePassphrase: suspend (String) -> Unit,
    private val connect: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
) {
    private var selection: Selection? = null
    private val mutex = Mutex()

    /** Lists terminal IDs without changing saved connection fields; null means unavailable and manual setup can continue. */
    suspend fun find(): List<String>? =
        mutex.withLock {
            selection = null
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock null
            val setup = unlocked.setup
            if (!setup.destination.discoversTerminals) return@withLock null
            val api = connect(key)
            val found = api.terminals() as? TerminalListing.Listed ?: return@withLock null
            val terminals =
                if (setup.onTerminal &&
                    setup.mode == TerminalMode.TERMINAL
                ) {
                    found.terminals.filter { it.id == setup.poiId }
                } else {
                    found.terminals
                }
            selection = Selection(key, setup, api, found.copy(terminals = terminals))
            terminals.map { it.id }
        }

    /** Applies only a currently offered ID; null dismisses the list or rejects a stale selection.
     * False means optional details still need manual entry. Missing fields preserve saved values, and unavailable
     * encryption or storage never blocks importing the account and address. Local devices keep their detected identity.
     */
    suspend fun choose(id: String?): Boolean? =
        mutex.withLock {
            val selected = selection ?: return@withLock null
            selection = null
            val terminal = selected.list.terminals.firstOrNull { it.id == id } ?: return@withLock null
            val local = selected.setup.mode == TerminalMode.TERMINAL
            val foundKey = if (local) selected.api.sharedKey(terminal.id, selected.list.environment) else null
            val current = setups.unlocked()
            if (current.apiKey != selected.key || current.setup.settings.terminal != selected.setup.settings.terminal) return@withLock null
            val key = foundKey?.takeIf { store(it) }
            apply(selected, terminal, key)
            terminal.merchantAccount.isNotBlank() && (!local || (key != null && (selected.setup.onTerminal || terminal.host.isNotBlank())))
        }

    private suspend fun store(key: DiscoveredKey): Boolean =
        try {
            savePassphrase(key.passphrase)
            true
        } catch (_: SecretStoreException) {
            false
        }

    private suspend fun apply(
        selected: Selection,
        terminal: TerminalDetails,
        key: DiscoveredKey?,
    ) {
        settings.update {
            if (it.terminal != selected.setup.settings.terminal) {
                it
            } else {
                it.copy(
                    terminal =
                        it.terminal.copy(
                            poiIdOverride =
                                if (selected.setup.onTerminal &&
                                    selected.setup.mode == TerminalMode.TERMINAL
                                ) {
                                    it.terminal.poiIdOverride
                                } else {
                                    terminal.id
                                },
                            merchantAccount = terminal.merchantAccount.ifBlank { it.terminal.merchantAccount },
                            host =
                                if (selected.setup.mode == TerminalMode.TERMINAL &&
                                    !selected.setup.onTerminal
                                ) {
                                    terminal.host.ifBlank { it.terminal.host }
                                } else {
                                    it.terminal.host
                                },
                            keyIdentifier = key?.identifier ?: it.terminal.keyIdentifier,
                            keyVersion = key?.version ?: it.terminal.keyVersion,
                        ),
                )
            }
        }
    }

    private class Selection(
        val key: String,
        val setup: TerminalSetup,
        val api: TerminalDetailsApi,
        val list: TerminalListing.Listed,
    )
}

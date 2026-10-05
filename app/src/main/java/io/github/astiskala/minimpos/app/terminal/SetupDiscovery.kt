package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Optional setup discovery through Management, independent of payment readiness.
 * @param setups Unlocks the API key through the existing setup boundary.
 * @param settings Saves selected connection fields.
 * @param savePassphrase Encrypts a retrieved passphrase through the container's secret-store writer.
 * @param connect Builds read-only Management access; tests inject a fake.
 * @param readEnvironment Reads this device's certificate before using Management.
 */
class SetupDiscovery(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val savePassphrase: suspend (String) -> Unit,
    private val connect: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
    private val readEnvironment: suspend () -> Unit = {},
) {
    private var selection: Selection? = null
    private val mutex = Mutex()

    /**
     * Reads the shared key for a boarded Payments app's merchant or store, using the Adyen API key in its installed
     * environment. [expected] rejects a boarding whose settings changed meanwhile. Null means not ready or stale;
     * false means unavailable lookup or storage. Unavailable lookup leaves saved manual fields intact.
     * Never lists or modifies terminals; storage I/O failures are nonfatal.
     */
    suspend fun findPaymentsAppKey(expected: TerminalSettings? = null): Boolean? =
        try {
            mutex.withLock {
                val unlocked = setups.unlocked()
                val setup = unlocked.setup
                val terminal = setup.settings.terminal
                if (!setup.discoversAccountKey || (expected != null && terminal != expected)) {
                    return@withLock null
                }
                val environment = setup.environment ?: return@withLock null
                val apiKey = unlocked.apiKey ?: return@withLock false
                val found =
                    connect(apiKey).accountSharedKey(
                        terminal.merchantAccount.trim(),
                        terminal.storeId.trim().ifEmpty { null },
                        environment,
                    )
                if (!sameOrigin(unlocked, setups.unlocked())) return@withLock null
                val key = found ?: return@withLock false
                var failed = false
                val applied =
                    settings.updateTerminal(terminal) {
                        val stored = store(key)
                        failed = !stored
                        if (stored) terminal.copy(keyIdentifier = key.identifier, keyVersion = key.version) else null
                    }
                if (applied) {
                    true
                } else if (failed) {
                    false
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            false
        }

    private fun sameOrigin(
        origin: UnlockedSetup,
        current: UnlockedSetup,
    ): Boolean {
        if (current.apiKey != origin.apiKey || current.setup.settings.terminal != origin.setup.settings.terminal) return false
        return current.setup.environment == origin.setup.environment &&
            current.setup.onTerminal == origin.setup.onTerminal &&
            current.terminalKey?.passphrase == origin.terminalKey?.passphrase
    }

    /** Lists terminal IDs without changing saved connection fields; null means unavailable and manual setup can continue. */
    suspend fun find(): List<String>? =
        mutex.withLock {
            selection = null
            readEnvironment()
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock null
            val setup = unlocked.setup
            if (!setup.discoversTerminals) return@withLock null
            val environment = setup.environment ?: return@withLock null
            val api = connect(key)
            val found = api.terminals(environment) as? TerminalListing.Listed ?: return@withLock null
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

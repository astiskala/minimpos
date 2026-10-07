package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.ManagementFailure
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
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
 * @param readEnvironment Reads this device's certificate before using Management.
 * @param sharedKeys Owns separately confirmed key creation and encrypted recovery.
 */
class SetupDiscovery(
    private val setups: TerminalSetupSource,
    private val settings: SettingsRepository,
    private val savePassphrase: suspend (String) -> Unit,
    private val connect: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
    private val readEnvironment: suspend () -> Unit = {},
    private val sharedKeys: SharedKeySetup,
) {
    internal var keyMissing = false
        private set
    private var selection: Selection? = null
    private val mutex = Mutex()
    internal var problem: SetupProblem? = null
        private set

    /** Lists terminal IDs without changing saved connection fields; null means unavailable and manual setup can continue. */
    suspend fun find(): List<String>? =
        mutex.withLock {
            selection = null
            problem = null
            keyMissing = false
            readEnvironment()
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock null
            val setup = unlocked.setup
            if (!setup.discoversTerminals) return@withLock null
            val environment = setup.environment ?: return@withLock null
            val api = connect(key)
            val credential = api.credential(environment)
            if (credential is CredentialLookup.Failed) {
                problem = credential.reason.setupProblem()
                return@withLock null
            }
            val target = setup.poiId.takeIf { setup.onTerminal && setup.mode == TerminalMode.TERMINAL }
            val found =
                when (val result = api.terminals(environment, target)) {
                    is TerminalListing.Listed -> {
                        result
                    }

                    is TerminalListing.Failed -> {
                        problem = result.reason.setupProblem()
                        return@withLock null
                    }
                }
            val terminals =
                if (setup.onTerminal &&
                    setup.mode == TerminalMode.TERMINAL
                ) {
                    found.terminals.filter { it.id == setup.poiId }
                } else {
                    found.terminals
                }
            if (terminals.isEmpty()) {
                problem = SetupProblem.TERMINAL_ACCESS
                return@withLock emptyList()
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
            val account =
                selected.setup.settings.terminal.merchantAccount
                    .trim()
            if (account.isNotBlank() && account != terminal.merchantAccount) {
                problem = SetupProblem.MERCHANT_MISMATCH
                return@withLock false
            }
            val local = selected.setup.mode == TerminalMode.TERMINAL
            val foundKey = if (local) discoveredKey(selected, terminal.id) else null
            val current = setups.unlocked()
            if (current.apiKey != selected.key || current.setup.settings.terminal != selected.setup.settings.terminal) return@withLock null
            val key = foundKey?.takeIf { store(it) }
            apply(selected, terminal, key)
            terminal.merchantAccount.isNotBlank() && (!local || (key != null && (selected.setup.onTerminal || terminal.host.isNotBlank())))
        }

    private suspend fun discoveredKey(
        selected: Selection,
        id: String,
    ): DiscoveredKey? =
        when (val found = selected.api.sharedKey(id, selected.list.environment)) {
            is SharedKeyLookup.Found -> {
                found.key
            }

            SharedKeyLookup.Missing -> {
                keyMissing = true
                null
            }

            is SharedKeyLookup.Failed -> {
                problem = found.reason.setupProblem()
                null
            }
        }

    internal suspend fun pendingKey(): SharedKeyOffer? = sharedKeys.pendingOffer(setups.unlocked(forValidation = true))

    internal suspend fun setupKey(confirmed: SharedKeyOffer? = null): SharedKeySetupOutcome =
        mutex.withLock {
            val unlocked = setups.unlocked(forValidation = true)

            suspend fun unchanged(): Boolean {
                val current = setups.unlocked(forValidation = true)
                return current.apiKey == unlocked.apiKey && current.setup.settings.terminal == unlocked.setup.settings.terminal
            }
            val result = sharedKeys.resolve(unlocked, confirmed, ::unchanged)
            if (result !is SharedKeySetupOutcome.Ready) return@withLock result
            if (!unchanged()) return@withLock SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
            savePassphrase(result.key.passphrase)
            settings.update {
                if (it.terminal !=
                    unlocked.setup.settings.terminal
                ) {
                    it
                } else {
                    it.copy(terminal = it.terminal.copy(keyIdentifier = result.key.identifier, keyVersion = result.key.version))
                }
            }
            keyMissing = false
            result
        }

    internal suspend fun completeKey() = sharedKeys.complete(setups.unlocked(forValidation = true))

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
                            storeId = terminal.storeId,
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

internal fun ManagementFailure.setupProblem(): SetupProblem =
    when (this) {
        ManagementFailure.AUTHENTICATION -> SetupProblem.MANAGEMENT_AUTHENTICATION
        ManagementFailure.PERMISSION -> SetupProblem.MANAGEMENT_PERMISSION
        ManagementFailure.UNAVAILABLE -> SetupProblem.MANAGEMENT_UNAVAILABLE
        ManagementFailure.UNREADABLE -> SetupProblem.MANAGEMENT_UNREADABLE
        ManagementFailure.SETTINGS_UNREADABLE -> SetupProblem.TERMINAL_SETTINGS_UNREADABLE
        ManagementFailure.KEY_INCOMPLETE -> SetupProblem.SHARED_KEY_INCOMPLETE
        ManagementFailure.KEY_INVALID -> SetupProblem.SHARED_KEY_INVALID
    }

internal class SetupAccess(
    private val connect: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
) {
    suspend fun verify(unlocked: UnlockedSetup): SetupProblem? {
        val setup = unlocked.setup
        val key = unlocked.apiKey
        val environment = setup.environment
        return when {
            setup.apiSetup == ApiSetup.Simulated -> {
                null
            }

            key == null -> {
                setup.apiSetup.problem ?: SetupProblem.API_KEY
            }

            environment == null -> {
                SetupProblem.ENVIRONMENT
            }

            else -> {
                val api = connect(key)
                when (val credential = api.credential(environment)) {
                    is CredentialLookup.Failed -> credential.reason.setupProblem()
                    CredentialLookup.Allowed -> if (setup.discoversTerminals && setup.poiId != null) assignment(api, setup) else null
                }
            }
        }
    }

    private suspend fun assignment(
        api: TerminalDetailsApi,
        setup: TerminalSetup,
    ): SetupProblem? =
        when (val listing = api.terminals(checkNotNull(setup.environment), setup.poiId)) {
            is TerminalListing.Failed -> {
                listing.reason.setupProblem()
            }

            is TerminalListing.Listed -> {
                val terminal = listing.terminals.firstOrNull { it.id == setup.poiId }
                when {
                    terminal == null -> SetupProblem.TERMINAL_ACCESS

                    terminal.merchantAccount.isBlank() -> SetupProblem.MERCHANT_ACCOUNT

                    terminal.merchantAccount !=
                        setup.settings.terminal.merchantAccount
                            .trim()
                    -> SetupProblem.MERCHANT_MISMATCH

                    else -> null
                }
            }
        }
}

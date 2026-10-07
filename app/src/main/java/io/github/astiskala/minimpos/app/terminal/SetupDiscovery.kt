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

/** One terminal search's immutable outcome, independent of later searches. */
sealed interface SetupDiscoverySearch {
    /** Discovery is not available for the current credentials, environment or destination; manual entry can continue. */
    data object Unavailable : SetupDiscoverySearch

    /** Offered terminal IDs, in Management order.
     * @property ids Nonempty POIID choices from this search.
     */
    data class Found(
        val ids: List<String>,
    ) : SetupDiscoverySearch

    /** Discovery was attempted but could not offer terminals.
     * @property problem Typed reason, including an empty permitted listing.
     */
    data class Failed(
        val problem: SetupProblem,
    ) : SetupDiscoverySearch
}

/** One terminal selection's immutable outcome; optional field imports may succeed despite a lookup failure. */
sealed interface SetupDiscoveryChoice {
    /** Lookup or assignment problem; null when no failure needs presentation. */
    val problem: SetupProblem? get() = null

    /** Whether missing optional details can be entered manually. */
    val manualDetails: Boolean get() = false

    /** Whether Management explicitly reported no shared key, allowing a separately confirmed key offer. */
    val keyMissing: Boolean get() = false

    /** Dismissed, unknown or stale selection; saved fields remain unchanged. */
    data object Ignored : SetupDiscoveryChoice {
        override val manualDetails: Boolean get() = true
    }

    /** Selected fields and required optional details were imported. */
    data object Complete : SetupDiscoveryChoice

    /** Fields were imported but optional details still need manual completion.
     * @property keyMissing Management explicitly reported that the terminal has no shared key.
     */
    data class Manual(
        override val keyMissing: Boolean,
    ) : SetupDiscoveryChoice {
        override val manualDetails: Boolean get() = true
    }

    /** Assignment or optional lookup failed; any safely imported fields are retained.
     * @property problem Typed reason to present rather than silently falling back to manual entry.
     */
    data class Failed(
        override val problem: SetupProblem,
    ) : SetupDiscoveryChoice
}

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
    private var selection: Selection? = null
    private val mutex = Mutex()

    /** Lists terminal IDs without changing saved connection fields; outcomes retain their meaning across later calls. */
    suspend fun find(): SetupDiscoverySearch =
        mutex.withLock {
            selection = null
            readEnvironment()
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock SetupDiscoverySearch.Unavailable
            val setup = unlocked.setup
            if (!setup.discoversTerminals) return@withLock SetupDiscoverySearch.Unavailable
            val environment = setup.environment ?: return@withLock SetupDiscoverySearch.Unavailable
            val api = connect(key)
            val credential = api.credential(environment)
            if (credential is CredentialLookup.Failed) return@withLock SetupDiscoverySearch.Failed(credential.reason.setupProblem())
            val onDevice = setup.onTerminal && setup.mode == TerminalMode.TERMINAL
            val target = setup.poiId.takeIf { onDevice }
            val found =
                when (val result = api.terminals(environment, target)) {
                    is TerminalListing.Listed -> result
                    is TerminalListing.Failed -> return@withLock SetupDiscoverySearch.Failed(result.reason.setupProblem())
                }
            val terminals = if (onDevice && target == null) emptyList() else TerminalAssignments.offered(found.terminals, target)
            if (terminals.isEmpty()) return@withLock SetupDiscoverySearch.Failed(SetupProblem.TERMINAL_ACCESS)
            selection = Selection(key, setup, api, found.copy(terminals = terminals))
            SetupDiscoverySearch.Found(terminals.map { it.id })
        }

    /** Applies only a currently offered ID; null dismisses the list, and stale selections are ignored.
     * The outcome distinguishes missing optional details from lookup failures. Missing fields preserve saved values,
     * and unavailable encryption or storage never blocks importing the account and address. Local devices keep their detected identity.
     */
    suspend fun choose(id: String?): SetupDiscoveryChoice =
        mutex.withLock {
            val selected = selection ?: return@withLock SetupDiscoveryChoice.Ignored
            selection = null
            if (id == null) return@withLock SetupDiscoveryChoice.Ignored
            val terminal =
                when (
                    val assignment =
                        TerminalAssignments.select(
                            selected.list.terminals,
                            id,
                            selected.setup.settings.terminal.merchantAccount,
                        )
                ) {
                    is TerminalAssignment.Assigned -> {
                        assignment.terminal
                    }

                    // An ID that this search did not offer is a stale or unknown selection, not an access failure.
                    is TerminalAssignment.Blocked -> {
                        val problem = assignment.problem
                        return@withLock if (problem == SetupProblem.TERMINAL_ACCESS) {
                            SetupDiscoveryChoice.Ignored
                        } else {
                            SetupDiscoveryChoice.Failed(problem)
                        }
                    }
                }
            val local = selected.setup.mode == TerminalMode.TERMINAL
            val lookup = if (local) selected.api.sharedKey(terminal.id, selected.list.environment) else null
            val current = setups.unlocked()
            if (current.apiKey != selected.key || current.setup.settings.terminal != selected.setup.settings.terminal) {
                return@withLock SetupDiscoveryChoice.Ignored
            }
            val key = (lookup as? SharedKeyLookup.Found)?.key?.takeIf { store(it) }
            apply(selected, terminal, key)
            choiceOutcome(selected, terminal, key, lookup)
        }

    private fun choiceOutcome(
        selected: Selection,
        terminal: TerminalDetails,
        key: DiscoveredKey?,
        lookup: SharedKeyLookup?,
    ): SetupDiscoveryChoice {
        if (lookup is SharedKeyLookup.Failed) return SetupDiscoveryChoice.Failed(lookup.reason.setupProblem())
        val localComplete = key != null && (selected.setup.onTerminal || terminal.host.isNotBlank())
        return if (terminal.merchantAccount.isNotBlank() && (selected.setup.mode != TerminalMode.TERMINAL || localComplete)) {
            SetupDiscoveryChoice.Complete
        } else {
            SetupDiscoveryChoice.Manual(keyMissing = lookup == SharedKeyLookup.Missing)
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
                TerminalAssignments.access(listing.terminals, checkNotNull(setup.poiId), setup.settings.terminal.merchantAccount)
            }
        }
}

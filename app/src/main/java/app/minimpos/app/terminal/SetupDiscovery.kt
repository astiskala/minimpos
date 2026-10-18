package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.SharedKeyApi
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListingApi
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
     * @property failure Why, including an empty permitted listing ([SetupProblem.TERMINAL_ACCESS]).
     */
    data class Failed(
        val failure: Failure,
    ) : SetupDiscoverySearch
}

/** One terminal selection's immutable outcome; optional field imports may succeed despite a lookup failure. */
sealed interface SetupDiscoveryChoice {
    /** Lookup or assignment failure; null when no failure needs presentation. */
    val failure: Failure? get() = null

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
     * @property failure Why, to present rather than silently falling back to manual entry.
     */
    data class Failed(
        override val failure: Failure,
    ) : SetupDiscoveryChoice
}

/** Completion of saved shared-key setup, including its fenced save and connection verification; never carries secrets. */
internal sealed interface SharedKeyConnection {
    /** Whether the exact saved key is now configured, including one whose communication check has not passed yet. */
    val configured: Boolean get() = false

    data class Confirmation(
        val offer: SharedKeyOffer,
    ) : SharedKeyConnection

    data object Connected : SharedKeyConnection {
        override val configured: Boolean = true
    }

    data class Failed(
        val failure: Failure,
        val offer: SharedKeyOffer? = null,
        val pending: Boolean = false,
        override val configured: Boolean = false,
    ) : SharedKeyConnection
}

/** Optional setup discovery through Management, independent of payment readiness.
 * @param setups Unlocks the API key through the setup source, and saves selected connection fields through
 *   its [TerminalSetupSource.SetupFence] only while the setup a search began on is still stored.
 * @param savePassphrase Encrypts a retrieved passphrase through the container's secret-store writer.
 * @param connect Builds read-only Management access; tests inject a fake.
 * @param readEnvironment Reads this device's certificate before using Management.
 * @param sharedKeys Owns separately confirmed key creation and encrypted recovery.
 * @param verifyConnection Checks saved setup and records its verified identity through TerminalStatus.
 */
class SetupDiscovery(
    private val setups: TerminalSetupSource,
    private val savePassphrase: suspend (String) -> Unit,
    private val connect: (String) -> SharedKeyApi = { AdyenTerminalDetails(it) },
    private val readEnvironment: suspend () -> Unit = {},
    private val sharedKeys: SharedKeySetup,
    private val verifyConnection: suspend () -> TerminalConnection,
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
            if (!setup.capabilities.discoversTerminals) return@withLock SetupDiscoverySearch.Unavailable
            val environment = setup.environment ?: return@withLock SetupDiscoverySearch.Unavailable
            val api = connect(key)
            val credential = api.credential(environment, setup.capabilities.usesCloudDeviceApi)
            if (credential is CredentialLookup.Failed) return@withLock SetupDiscoverySearch.Failed(Failure.Remote(credential.fault))
            val onDevice = setup.onTerminal && setup.mode == TerminalMode.TERMINAL
            val terminals =
                when (val found = TerminalAssignments.listOffered(api, environment, onDevice, setup.poiId)) {
                    is DiscoveredTerminals.Found -> found.terminals
                    is DiscoveredTerminals.Failed -> return@withLock SetupDiscoverySearch.Failed(found.failure)
                }
            selection = Selection(setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL), setup, api, environment, terminals)
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
                            selected.terminals,
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
                            SetupDiscoveryChoice.Failed(Failure.NotSetUp(problem))
                        }
                    }
                }
            val local = selected.setup.mode == TerminalMode.TERMINAL
            val lookup = if (local) selected.api.sharedKey(terminal.id, selected.environment) else null
            if (!selected.fence.stillCurrent()) return@withLock SetupDiscoveryChoice.Ignored
            val key = (lookup as? SharedKeyLookup.Found)?.key?.takeIf { store(it) }
            if (!apply(selected, terminal, key)) return@withLock SetupDiscoveryChoice.Ignored
            choiceOutcome(selected, terminal, key, lookup)
        }

    private fun choiceOutcome(
        selected: Selection,
        terminal: TerminalDetails,
        key: DiscoveredKey?,
        lookup: SharedKeyLookup?,
    ): SetupDiscoveryChoice {
        if (lookup is SharedKeyLookup.Failed) return SetupDiscoveryChoice.Failed(Failure.Remote(lookup.fault))
        val localComplete = key != null && (selected.setup.onTerminal || terminal.host.isNotBlank())
        return if (terminal.merchantAccount.isNotBlank() && (selected.setup.mode != TerminalMode.TERMINAL || localComplete)) {
            SetupDiscoveryChoice.Complete
        } else {
            SetupDiscoveryChoice.Manual(keyMissing = lookup == SharedKeyLookup.Missing)
        }
    }

    internal suspend fun pendingKey(): SharedKeyOffer? = sharedKeys.pendingOffer(setups.unlocked(forValidation = true))

    internal suspend fun setupKey(confirmed: SharedKeyOffer? = null): SharedKeyConnection =
        mutex.withLock {
            val unlocked = setups.unlocked(forValidation = true)
            // The passphrase saved below is part of this work, so only the connection fields and API key must stay.
            val fence = setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL)
            val result = sharedKeys.resolve(unlocked, confirmed, unchanged = fence::stillCurrent)
            when (result) {
                is SharedKeySetupOutcome.Confirmation -> {
                    SharedKeyConnection.Confirmation(result.offer)
                }

                is SharedKeySetupOutcome.Failed -> {
                    val offer = pendingKey()
                    SharedKeyConnection.Failed(result.failure, offer, pending = offer?.resume == true)
                }

                is SharedKeySetupOutcome.Ready -> {
                    connectKey(fence, result)
                }
            }
        }

    private suspend fun connectKey(
        fence: TerminalSetupSource.SetupFence,
        ready: SharedKeySetupOutcome.Ready,
    ): SharedKeyConnection {
        fence.changed()?.let { return SharedKeyConnection.Failed(Failure.NotSetUp(it)) }
        val committed = saveKey(fence, ready.key)
        return if (committed == null) {
            SharedKeyConnection.Failed(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
        } else {
            val connected = verifyConnection() is TerminalConnection.Connected
            val changed = committed.changed()
            when {
                changed != null -> SharedKeyConnection.Failed(Failure.NotSetUp(changed))
                connected -> SharedKeyConnection.Connected
                else -> SharedKeyConnection.Failed(Failure.NotSetUp(ready.unverifiedProblem), pending = ready.pending, configured = true)
            }
        }
    }

    private suspend fun saveKey(
        fence: TerminalSetupSource.SetupFence,
        key: DiscoveredKey,
    ): TerminalSetupSource.SetupFence? {
        savePassphrase(key.passphrase)
        val applied = fence.update { it.copy(terminal = it.terminal.copy(keyIdentifier = key.identifier, keyVersion = key.version)) }
        return if (applied) fence.withSharedKey(key) else null
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
    ): Boolean =
        selected.fence.update {
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

    private class Selection(
        val fence: TerminalSetupSource.SetupFence,
        val setup: TerminalSetup,
        val api: SharedKeyApi,
        val environment: TerminalEnvironment,
        val terminals: List<TerminalDetails>,
    )
}

internal class SetupAccess(
    private val connect: (String) -> TerminalListingApi = { AdyenTerminalDetails(it) },
) {
    /** Why the credential or terminal assignment of [unlocked] cannot be used; null when it can. */
    suspend fun verify(unlocked: UnlockedSetup): Failure? {
        val setup = unlocked.setup
        val key = unlocked.apiKey
        val environment = setup.environment
        return when {
            setup.apiSetup == ApiSetup.Simulated -> {
                null
            }

            key == null -> {
                Failure.NotSetUp(setup.apiSetup.problem ?: SetupProblem.API_KEY)
            }

            environment == null -> {
                Failure.NotSetUp(SetupProblem.ENVIRONMENT)
            }

            else -> {
                val api = connect(key)
                when (val credential = api.credential(environment, setup.capabilities.usesCloudDeviceApi)) {
                    is CredentialLookup.Failed -> {
                        Failure.Remote(credential.fault)
                    }

                    CredentialLookup.Allowed -> {
                        if (setup.capabilities.discoversTerminals &&
                            setup.poiId != null
                        ) {
                            assignment(api, setup)
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private suspend fun assignment(
        api: TerminalListingApi,
        setup: TerminalSetup,
    ): Failure? =
        TerminalAssignments.verifyAccess(
            api,
            checkNotNull(setup.environment),
            checkNotNull(setup.poiId),
            setup.settings.terminal.merchantAccount,
        )
}

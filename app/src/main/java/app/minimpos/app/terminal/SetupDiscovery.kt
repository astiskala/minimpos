package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.SharedKeyApi
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.StoreDetailsApi
import app.minimpos.terminal.transport.StoreListing
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Non-secret store choice offered by setup discovery.
 * @property id Adyen store ID used for boarding.
 * @property reference Merchant's store reference, blank when absent.
 * @property description Merchant's display description, blank when absent.
 */
data class SetupStoreChoice(
    val id: String,
    val reference: String,
    val description: String,
)

/**
 * A terminal assigned to another merchant account than the one entered, for the operator to accept or leave.
 * @property entered The account that was entered.
 * @property assigned The account the terminal is assigned to.
 */
data class AccountMismatch(
    val entered: String,
    val assigned: String,
)

/**
 * A terminal offered for an operator to choose, with what tells it apart across accounts.
 * @property id Terminal POIID.
 * @property merchantAccount Assigned merchant account, blank when unknown.
 * @property storeId Assigned store ID, blank when not assigned to a store.
 * @property lastActivity When it was last active; null when Adyen does not say.
 * @property inventory Whether it sits in inventory, where it cannot take payments.
 */
data class SetupTerminalChoice(
    val id: String,
    val merchantAccount: String = "",
    val storeId: String = "",
    val lastActivity: Instant? = null,
    val inventory: Boolean = false,
)

/**
 * A merchant account offered for an operator to choose.
 * @property id The merchant account's identifier.
 * @property name Its name, blank when absent.
 */
data class SetupMerchantChoice(
    val id: String,
    val name: String,
)

/** [terminals] as an operator chooses among them: the most recently active first, those without activity last. */
internal fun terminalChoices(terminals: List<TerminalDetails>): List<SetupTerminalChoice> =
    terminals
        .sortedWith(compareBy(nullsLast(reverseOrder<Instant>())) { it.lastActivity })
        .map { SetupTerminalChoice(it.id, it.merchantAccount, it.storeId, it.lastActivity, it.inventory) }

/** Store discovery for a saved Tap to Pay setup, without changing registration. */
sealed interface SetupStoreSearch {
    /**
     * Complete store choices for the current account.
     * @property stores Offered stores, empty when none exist.
     */
    data class Found(
        val stores: List<SetupStoreChoice>,
    ) : SetupStoreSearch

    /**
     * Setup or remote failure; no partial choices are offered.
     * @property failure Why listing could not finish.
     */
    data class Failed(
        val failure: Failure,
    ) : SetupStoreSearch
}

/** One terminal search's immutable outcome, independent of later searches. */
sealed interface SetupDiscoverySearch {
    /** Discovery is not available for the current credentials, environment or destination; manual entry can continue. */
    data object Unavailable : SetupDiscoverySearch

    /** Offered terminals, the most recently active first.
     * @property terminals Nonempty choices from this search.
     */
    data class Found(
        val terminals: List<SetupTerminalChoice>,
    ) : SetupDiscoverySearch {
        /** The offered terminal IDs. */
        val ids: List<String> get() = terminals.map { it.id }
    }

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

    /**
     * The terminal belongs to another account; nothing changed. Choosing it again with the assigned account accepts it.
     * @property question The terminal chosen and the two accounts, to put to the operator.
     */
    data class Asks(
        val question: SetupQuestion.AccountDiffers,
    ) : SetupDiscoveryChoice

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

    /** The Tap to Pay account's key was read from Adyen and saved; only a payment can test it. */
    data object Saved : SharedKeyConnection {
        override val configured: Boolean = true
    }

    data class Failed(
        val failure: Failure,
        val offer: SharedKeyOffer? = null,
        val pending: Boolean = false,
        override val configured: Boolean = false,
    ) : SharedKeyConnection
}

/** Optional setup discovery through Management for the saved setup, independent of payment readiness: the
 * [SetupConversation] asks its questions, and this applies the answers with fenced writes to the stored settings.
 * @param setups Unlocks the API key through the setup source, and saves selected connection fields through
 *   its [TerminalSetupSource.SetupFence] only while the setup a search began on is still stored.
 * @param savePassphrase Encrypts a retrieved passphrase through the container's secret-store writer.
 * @param conversation Asks setup discovery's questions; tests give it fake Management access.
 * @param readEnvironment Reads this device's certificate before using Management.
 * @param verifyConnection Checks saved setup and records its verified identity through TerminalStatus.
 * @param stores Read-only store listing for the selected credential and environment.
 */
class SetupDiscovery internal constructor(
    private val setups: TerminalSetupSource,
    private val savePassphrase: suspend (String) -> Unit,
    private val conversation: SetupConversation,
    private val readEnvironment: suspend () -> Unit = {},
    private val verifyConnection: suspend () -> TerminalConnection,
    private val stores: (String, TerminalEnvironment) -> StoreDetailsApi,
) {
    private var selection: Selection? = null
    private var storeSelection: StoreSelection? = null
    private val mutex = Mutex()

    /** Reads the shared key of the saved Tap to Pay account and saves it. */
    private suspend fun phoneKey(unlocked: UnlockedSetup): SharedKeyConnection {
        val owner = setups.owner(unlocked)
        if (owner is BoardingOwner.Blocked) return SharedKeyConnection.Failed(Failure.NotSetUp(owner.problem))
        owner as BoardingOwner.Ready
        val storeId =
            unlocked.setup.settings.terminal.storeId
                .trim()
                .ifEmpty { null }
        val fence = setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL)
        val api = conversation.management(owner.apiKey)
        return when (val found = conversation.phoneKey(api, owner.environment, owner.merchantAccount, storeId)) {
            is SetupReply.Halted -> {
                SharedKeyConnection.Failed(found.failure)
            }

            is SetupReply.Next -> {
                if (saveKey(fence, found.value) == null) {
                    SharedKeyConnection.Failed(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
                } else {
                    SharedKeyConnection.Saved
                }
            }
        }
    }

    /** Roles the saved API key lacks and can be given; null when it has them all or cannot be changed from here. */
    suspend fun keyRolesOffer(): KeyRolesOffer? =
        mutex.withLock {
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock null
            val environment = unlocked.setup.environment ?: return@withLock null
            (
                (
                    conversation.credential(
                        key,
                        environment,
                        accepted = null,
                    ) as? SetupReply.Asks
                )?.question as? SetupQuestion.RolesMissing
            )?.offer
        }

    /** Adds [offer] to the saved API key while it still lacks exactly those roles; whether the key now holds them. */
    suspend fun addKeyRoles(offer: KeyRolesOffer): Boolean =
        mutex.withLock {
            val unlocked = setups.unlocked()
            val key = unlocked.apiKey ?: return@withLock false
            val environment = unlocked.setup.environment ?: return@withLock false
            conversation.credential(key, environment, accepted = offer) is SetupReply.Next
        }

    /** Lists stores for the saved account; missing setup and stale responses return typed failures. */
    suspend fun findStores(): SetupStoreSearch =
        mutex.withLock {
            storeSelection = null
            val unlocked = setups.unlocked()
            val owner = setups.owner(unlocked)
            if (owner is BoardingOwner.Blocked) return@withLock SetupStoreSearch.Failed(Failure.NotSetUp(owner.problem))
            owner as BoardingOwner.Ready
            val fence = setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL)
            val result = stores(owner.apiKey, owner.environment).stores(owner.merchantAccount)
            if (!fence.stillCurrent()) return@withLock SetupStoreSearch.Failed(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
            when (result) {
                is StoreListing.Found -> {
                    val choices = result.stores.map { SetupStoreChoice(it.id, it.reference, it.description) }
                    storeSelection = StoreSelection(fence, choices)
                    SetupStoreSearch.Found(choices)
                }

                is StoreListing.Failed -> {
                    SetupStoreSearch.Failed(Failure.Remote(result.fault))
                }
            }
        }

    /**
     * Applies only an offered store while its original setup remains current; null dismisses without changing settings.
     * Returns null once saved or dismissed, or [SetupProblem.SETUP_CHANGED] for an unknown, already used or stale choice.
     */
    suspend fun chooseStore(id: String?): Failure? =
        mutex.withLock {
            val selected = storeSelection
            storeSelection = null
            if (id == null) return@withLock null
            val saved =
                selected != null &&
                    selected.stores.any { it.id == id } &&
                    selected.fence.update { it.copy(terminal = it.terminal.copy(storeId = id)) }
            if (saved) null else Failure.NotSetUp(SetupProblem.SETUP_CHANGED)
        }

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
            // Settings presents missing roles as the failure; the key assistant then offers to add them.
            val credential = conversation.credential(key, environment, accepted = null)
            if (credential is SetupReply.Halted) return@withLock SetupDiscoverySearch.Failed(credential.failure)
            val api = conversation.management(key)
            val onDevice = setup.onTerminal
            val terminals =
                when (val found = TerminalAssignments.listOffered(api, environment, onDevice, setup.poiId)) {
                    is DiscoveredTerminals.Found -> found.terminals
                    is DiscoveredTerminals.Failed -> return@withLock SetupDiscoverySearch.Failed(found.failure)
                }
            selection = Selection(setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL), setup, api, environment, terminals)
            SetupDiscoverySearch.Found(terminalChoices(terminals))
        }

    /** Applies only a currently offered ID; null dismisses the list, and stale selections are ignored. A terminal assigned
     * to another account than the one saved is applied only with that account as [confirmedAccount].
     * The outcome distinguishes missing optional details from lookup failures. Missing fields preserve saved values,
     * and unavailable encryption or storage never blocks importing the account and address. Local devices keep their detected identity.
     */
    suspend fun choose(
        id: String?,
        confirmedAccount: String? = null,
    ): SetupDiscoveryChoice =
        mutex.withLock {
            val selected = selection ?: return@withLock SetupDiscoveryChoice.Ignored
            selection = null
            if (id == null) return@withLock SetupDiscoveryChoice.Ignored
            val entered = selected.setup.settings.terminal.merchantAccount
            val terminal =
                when (val chosen = conversation.choose(selected.terminals, id, entered, confirmedAccount)) {
                    is SetupReply.Next -> {
                        chosen.value
                    }

                    is SetupReply.Asks -> {
                        selection = selected
                        val question = chosen.question as SetupQuestion.AccountDiffers
                        return@withLock SetupDiscoveryChoice.Asks(question)
                    }

                    // An ID that this search did not offer is a stale or unknown selection, not an access failure.
                    is SetupReply.Failed -> {
                        return@withLock if (chosen.failure == Failure.NotSetUp(SetupProblem.TERMINAL_ACCESS)) {
                            SetupDiscoveryChoice.Ignored
                        } else {
                            SetupDiscoveryChoice.Failed(chosen.failure)
                        }
                    }
                }
            val lookup = conversation.terminalKey(selected.api, selected.environment, selected.setup, terminal)
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

    internal suspend fun pendingKey(): SharedKeyOffer? = conversation.pendingKey(setups.unlocked(forValidation = true))

    internal suspend fun setupKey(confirmed: SharedKeyOffer? = null): SharedKeyConnection =
        mutex.withLock {
            val unlocked = setups.unlocked(forValidation = true)
            if (unlocked.setup.boardsPhone) return@withLock phoneKey(unlocked)
            // The passphrase saved below is part of this work, so only the connection fields and API key must stay.
            val fence = setups.SetupFence(unlocked, SetupFreshness.CREDENTIAL)
            when (val result = conversation.createKey(unlocked, confirmed, unchanged = fence::stillCurrent)) {
                is SetupReply.Asks -> {
                    SharedKeyConnection.Confirmation((result.question as SetupQuestion.KeyConfirmation).offer)
                }

                is SetupReply.Failed -> {
                    val offer = pendingKey()
                    SharedKeyConnection.Failed(result.failure, offer, pending = offer?.resume == true)
                }

                is SetupReply.Next -> {
                    connectKey(fence, result.value)
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
        // Settings always takes a network terminal's listed address: the operator is choosing it from that listing.
        selected.fence.update { it.copy(terminal = conversation.assign(it.terminal, selected.setup, terminal, listedAddress = true, key)) }

    private class Selection(
        val fence: TerminalSetupSource.SetupFence,
        val setup: TerminalSetup,
        val api: SharedKeyApi,
        val environment: TerminalEnvironment,
        val terminals: List<TerminalDetails>,
    )

    private class StoreSelection(
        val fence: TerminalSetupSource.SetupFence,
        val stores: List<SetupStoreChoice>,
    )
}

/**
 * Independent Management access checks.
 * @property failure Credential or assignment failure, or null when allowed.
 * @property roles Observed required roles, or null when no readable credential response exists.
 */
data class SetupAccessCheck(
    val failure: Failure? = null,
    val roles: CredentialRoles? = null,
)

internal class SetupAccess(
    private val connect: (String) -> TerminalListingApi,
) {
    /** Checks [unlocked]'s credential and assignment, retaining role evidence even when access fails. */
    suspend fun verify(unlocked: UnlockedSetup): SetupAccessCheck {
        val setup = unlocked.setup
        val key = unlocked.apiKey
        val environment = setup.environment
        return when {
            setup.apiSetup == ApiSetup.Simulated -> {
                SetupAccessCheck()
            }

            key == null -> {
                SetupAccessCheck(Failure.NotSetUp(setup.apiSetup.problem ?: SetupProblem.API_KEY))
            }

            environment == null -> {
                SetupAccessCheck(Failure.NotSetUp(SetupProblem.ENVIRONMENT))
            }

            else -> {
                val api = connect(key)
                when (val credential = api.credential(environment)) {
                    is CredentialLookup.Failed -> {
                        SetupAccessCheck(Failure.Remote(credential.fault), credential.roles)
                    }

                    is CredentialLookup.Allowed -> {
                        val failure =
                            if (setup.capabilities.discoversTerminals && setup.poiId != null) assignment(api, setup) else null
                        SetupAccessCheck(failure, credential.roles)
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

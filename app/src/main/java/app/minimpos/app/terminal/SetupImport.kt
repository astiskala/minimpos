package app.minimpos.app.terminal

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.ImportMode
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.ConnectionSetup
import app.minimpos.app.data.transfer.ImportOutcome
import app.minimpos.app.data.transfer.PreparedTransfer
import app.minimpos.app.data.transfer.ReceivedTransfer
import app.minimpos.app.data.transfer.SetupTransfer
import app.minimpos.core.codec.Transfer
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.SharedKeyApi
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface SetupImportOutcome {
    data class Committed(
        val outcome: ImportOutcome,
    ) : SetupImportOutcome

    data class Terminals(
        val ids: List<String>,
    ) : SetupImportOutcome

    data class KeyConfirmation(
        val offer: SharedKeyOffer,
    ) : SetupImportOutcome

    data object BoardingRequired : SetupImportOutcome

    data class HistoryConfirmation(
        val plan: HistorySwitchPlan,
    ) : SetupImportOutcome

    data class Failed(
        val failure: Failure,
        val incomplete: Boolean = false,
    ) : SetupImportOutcome {
        constructor(problem: SetupProblem, incomplete: Boolean = false) : this(Failure.NotSetUp(problem), incomplete)

        val keyPending: Boolean get() = failure == Failure.NotSetUp(SetupProblem.KEY_CONNECTION_PENDING)
    }
}

/** Management checks and separately confirmed key setup used before activating an import candidate. */
class SetupImportChecks(
    /** Proposes store or merchant receipt fields without replacing saved merchant text. */
    val businessDetails: ReceiptBusinessDetails,
    /** Creates a read-only terminal-management client for the candidate credential. */
    val terminals: (String) -> SharedKeyApi,
    /** Owns key creation only after explicit consent, without changing active import configuration. */
    val sharedKeys: SharedKeySetup,
)

/** Verifies a scanned setup without replacing active configuration, then commits it with durable recovery.
 * @param transfer Authenticates transfer codes and owns encrypted commit persistence.
 * @param setups Resolves candidates and records local verification fingerprints.
 * @param gateway Tests candidate connections without publishing learned facts into active settings.
 * @param api Verifies Checkout access for the candidate account and environment.
 * @param tapToPay Registers the phone during a confirmed helper import or separate registration action, retaining its recovery identity.
 * @param checks Reads Management terminal facts and proposed receipt fields without modifying active setup.
 * @param historySwitches Requires explicit confirmation before an import purges another environment’s history.
 * @param busy Whether a financial operation is running, so its configuration cannot be replaced.
 */
class SetupImport internal constructor(
    private val transfer: SetupTransfer,
    private val setups: TerminalSetupSource,
    private val gateway: TerminalGateway,
    private val api: AdyenApi,
    private val tapToPay: TapToPaySetup,
    private val checks: SetupImportChecks,
    private val historySwitches: HistorySwitches,
    private val busy: () -> Boolean,
) {
    private val sharedKeys get() = checks.sharedKeys
    private val mutex = Mutex()

    /** Reads the settings and connection of a scanned [raw] transfer before verification. */
    fun receive(raw: Transfer): ReceivedTransfer = transfer.receive(raw)

    internal suspend fun import(
        received: ReceivedTransfer,
        mode: ImportMode,
        code: String,
        terminalId: String? = null,
        board: Boolean = false,
        confirmedHistorySwitch: HistorySwitchPlan? = null,
        confirmedKey: SharedKeyOffer? = null,
    ): SetupImportOutcome =
        mutex.withLock {
            if (busy()) return@withLock SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
            if (transfer.pending.first()) return@withLock recoverResult()
            val prepared = transfer.prepare(received, code) ?: return@withLock SetupImportOutcome.Committed(ImportOutcome.WrongCode)
            if (!prepared.original.acceptsSetupImport) return@withLock SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
            try {
                val choices = Choices(terminalId, board, confirmedHistorySwitch, confirmedKey)
                val verified =
                    when (val step = verify(prepared, choices)) {
                        is Step.Halted -> return@withLock step.outcome
                        is Step.Next -> step.candidate.prepared
                    }
                val switch = historySwitches.preview(verified.settings.terminal)
                if (switch != null && !switch.wasConfirmedBy(confirmedHistorySwitch)) {
                    return@withLock SetupImportOutcome.HistoryConfirmation(switch)
                }
                SetupImportOutcome.Committed(transfer.commit(verified, mode, purgeHistory = switch != null, verified = ::remember))
            } catch (ignored: SecretStoreException) {
                SetupImportOutcome.Committed(ImportOutcome.StorageFailed(DeviceFault.SECURE_STORAGE))
            }
        }

    internal suspend fun recover(): ImportOutcome? =
        mutex.withLock {
            if (busy()) return@withLock ImportOutcome.Rejected(SetupProblem.SETUP_CHANGED)
            transfer.recover(::remember)
        }

    private suspend fun recoverResult(): SetupImportOutcome =
        SetupImportOutcome.Committed(transfer.recover(::remember) ?: ImportOutcome.Rejected(SetupProblem.TRANSFER_PENDING))

    private suspend fun remember(): Boolean = setups.rememberVerified(setups.unlocked(forValidation = true))

    private fun candidate(prepared: PreparedTransfer): UnlockedSetup = setups.candidate(prepared.settings, prepared.secrets)

    private class Choices(
        val terminalId: String?,
        val board: Boolean,
        val confirmedHistory: HistorySwitchPlan?,
        val confirmedKey: SharedKeyOffer?,
    )

    private data class Candidate(
        val prepared: PreparedTransfer,
        val missingKey: Boolean = false,
    )

    private sealed interface Step {
        data class Next(
            val candidate: Candidate,
        ) : Step

        data class Halted(
            val outcome: SetupImportOutcome,
        ) : Step
    }

    private inline fun Step.andThen(block: (Candidate) -> Step): Step =
        when (this) {
            is Step.Next -> block(candidate)
            is Step.Halted -> this
        }

    private suspend fun verify(
        initial: PreparedTransfer,
        choices: Choices,
    ): Step {
        val start = Candidate(initial)
        if (!initial.changesSetup || candidate(initial).setup.apiSetup == ApiSetup.Simulated) return Step.Next(start)
        return environment(start)
            .andThen { credential(it) }
            .andThen { discovery(it, choices.terminalId) }
            .andThen { requiredFields(it, allowMissingKey = it.missingKey) }
            .andThen { checkout(it) }
            .andThen { phone(it, choices.board || initial.received.hasConnection, choices.confirmedHistory) }
            .andThen { sharedKey(it, choices.confirmedHistory, choices.confirmedKey) }
            .andThen { requiredFields(it) }
            .andThen { connection(it) }
            .andThen { business(it) }
    }

    private suspend fun environment(current: Candidate): Step {
        val prepared = current.prepared
        if (!candidate(prepared).setup.suppliesCertificateEnvironment) return Step.Next(current)
        val environment =
            gateway.candidates.environment() ?: return Step.Halted(SetupImportOutcome.Failed(SetupProblem.TERMINAL_ENVIRONMENT))
        return Step.Next(current.copy(prepared = prepared.withTerminal(prepared.settings.terminal.selectEnvironment(environment))))
    }

    private suspend fun credential(current: Candidate): Step {
        val unlocked = candidate(current.prepared)
        val key = unlocked.apiKey ?: return Step.Halted(SetupImportOutcome.Failed(SetupProblem.API_KEY, incomplete = true))
        val environment =
            unlocked.setup.environment ?: return Step.Halted(SetupImportOutcome.Failed(SetupProblem.ENVIRONMENT, incomplete = true))
        return when (val result = checks.terminals(key).credential(environment)) {
            is CredentialLookup.Allowed -> Step.Next(current)
            is CredentialLookup.Failed -> Step.Halted(SetupImportOutcome.Failed(Failure.Remote(result.fault)))
        }
    }

    private suspend fun discovery(
        current: Candidate,
        selected: String?,
    ): Step {
        val prepared = current.prepared
        val unlocked = candidate(prepared)
        if (!unlocked.setup.capabilities.discoversTerminals) return Step.Next(current)
        val setup = unlocked.setup
        val environment = checkNotNull(setup.environment)
        val management = checks.terminals(checkNotNull(unlocked.apiKey))
        return when (
            val assignment =
                TerminalAssignments.discover(
                    management,
                    environment,
                    setup.poiId ?: selected,
                    prepared.settings.terminal.merchantAccount,
                )
        ) {
            is DiscoveredAssignment.Choices -> {
                Step.Halted(SetupImportOutcome.Terminals(assignment.ids))
            }

            is DiscoveredAssignment.Failed -> {
                Step.Halted(SetupImportOutcome.Failed(assignment.failure))
            }

            is DiscoveredAssignment.Assigned -> {
                val terminal = assignment.terminal
                val assigned = applyAssignment(prepared, setup, terminal)
                if (!prepared.received.automatic || !setup.needsSharedKey) {
                    Step.Next(current.copy(prepared = assigned))
                } else {
                    when (val found = management.sharedKey(terminal.id, environment)) {
                        is SharedKeyLookup.Found -> Step.Next(current.copy(prepared = assigned.withSharedKey(found.key)))
                        SharedKeyLookup.Missing -> Step.Next(Candidate(assigned, missingKey = true))
                        is SharedKeyLookup.Failed -> Step.Halted(SetupImportOutcome.Failed(Failure.Remote(found.fault)))
                    }
                }
            }
        }
    }

    private fun applyAssignment(
        prepared: PreparedTransfer,
        setup: TerminalSetup,
        terminal: TerminalDetails,
    ): PreparedTransfer {
        val current = prepared.settings.terminal
        return prepared.withTerminal(
            current.copy(
                merchantAccount = terminal.merchantAccount,
                storeId = terminal.storeId,
                poiIdOverride = if (setup.onTerminal) current.poiIdOverride else terminal.id,
                host =
                    if (prepared.received.automatic && setup.host != TerminalSetup.LOCALHOST &&
                        setup.needsSharedKey
                    ) {
                        terminal.host
                    } else {
                        current.host
                    },
            ),
        )
    }

    private suspend fun sharedKey(
        current: Candidate,
        confirmedHistory: HistorySwitchPlan?,
        confirmedKey: SharedKeyOffer?,
    ): Step {
        val prepared = current.prepared
        val unlocked = candidate(prepared)
        if (!current.missingKey && sharedKeys.pendingOffer(unlocked) == null) return Step.Next(current)
        historyConfirmation(prepared, confirmedHistory)?.let { return it }
        return when (val result = sharedKeys.resolve(unlocked, confirmedKey, verified = true) { transfer.unchanged(prepared) }) {
            is SharedKeySetupOutcome.Confirmation -> {
                Step.Halted(SetupImportOutcome.KeyConfirmation(result.offer))
            }

            is SharedKeySetupOutcome.Ready -> {
                Step.Next(current.copy(prepared = prepared.withSharedKey(result.key)))
            }

            is SharedKeySetupOutcome.Failed -> {
                Step.Halted(SetupImportOutcome.Failed(result.failure))
            }
        }
    }

    private suspend fun historyConfirmation(
        prepared: PreparedTransfer,
        confirmed: HistorySwitchPlan?,
    ): Step.Halted? =
        historySwitches
            .preview(prepared.settings.terminal)
            ?.takeUnless { it.wasConfirmedBy(confirmed) }
            ?.let { Step.Halted(SetupImportOutcome.HistoryConfirmation(it)) }

    private fun requiredFields(
        current: Candidate,
        allowMissingKey: Boolean = false,
    ): Step {
        val prepared = current.prepared
        val problem =
            candidate(prepared)
                .setup
                .importProblem(prepared.secrets.keys)
                ?.takeUnless {
                    allowMissingKey &&
                        it in
                        setOf(
                            SetupProblem.KEY_IDENTIFIER,
                            SetupProblem.KEY_VERSION,
                            SetupProblem.PASSPHRASE,
                            SetupProblem.UNREADABLE_PASSPHRASE,
                        )
                }
        return if (problem == null) Step.Next(current) else Step.Halted(SetupImportOutcome.Failed(problem, incomplete = true))
    }

    private suspend fun checkout(current: Candidate): Step =
        when (val result = api.verify(candidate(current.prepared), accessVerified = true)) {
            ApiCheck.Works -> Step.Next(current)
            is ApiCheck.NotSetUp -> Step.Halted(SetupImportOutcome.Failed(result.problem))
            is ApiCheck.Failed -> Step.Halted(SetupImportOutcome.Failed(result.failure))
        }

    private suspend fun phone(
        current: Candidate,
        explicit: Boolean,
        confirmedHistory: HistorySwitchPlan?,
    ): Step {
        val prepared = current.prepared
        val unlocked = candidate(prepared)
        if (!unlocked.setup.boardsPhone) return Step.Next(current)
        // Registration creates a credential at Adyen, so history loss is confirmed before it rather than after.
        if (explicit) historyConfirmation(prepared, confirmedHistory)?.let { return it }
        return when (val result = tapToPay.candidate(unlocked, explicit)) {
            is TapToPayOutcome.Boarded -> {
                val evidence = tapToPay.candidateIdentity(unlocked, result.installationId)
                Step.Next(current.copy(prepared = prepared.withBoardedPhone(result.installationId, evidence)))
            }

            is TapToPayOutcome.NotSetUp -> {
                Step.Halted(
                    if (result.problem == SetupProblem.PAYMENTS_APP_NOT_BOARDED) {
                        SetupImportOutcome.BoardingRequired
                    } else {
                        SetupImportOutcome.Failed(result.problem, incomplete = true)
                    },
                )
            }

            is TapToPayOutcome.Failed -> {
                Step.Halted(SetupImportOutcome.Failed(result.failure))
            }

            TapToPayOutcome.Unregistered -> {
                Step.Halted(SetupImportOutcome.Failed(SetupProblem.PAYMENTS_APP_NOT_BOARDED))
            }
        }
    }

    private suspend fun connection(current: Candidate): Step {
        val prepared = current.prepared
        if (!transfer.unchanged(prepared)) return Step.Halted(SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED))
        val unlocked = candidate(prepared)
        val (connection, detected) = gateway.candidates.check(unlocked)
        if (connection !is TerminalConnection.Connected && sharedKeys.pendingOffer(unlocked) != null) {
            return Step.Halted(SetupImportOutcome.Failed(SetupProblem.KEY_CONNECTION_PENDING))
        }
        return when (connection) {
            is TerminalConnection.Connected -> {
                if (detected != null && detected.environment != unlocked.setup.environment) {
                    Step.Halted(SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED))
                } else {
                    val updated = detected?.let { prepared.copy(settings = setups.learnedCandidate(it)) } ?: prepared
                    Step.Next(current.copy(prepared = updated))
                }
            }

            is TerminalConnection.NotSetUp -> {
                Step.Halted(SetupImportOutcome.Failed(connection.problem, incomplete = true))
            }

            is TerminalConnection.Failed -> {
                Step.Halted(SetupImportOutcome.Failed(connection.failure))
            }

            // A candidate check always settles; anything else is unexpected.
            TerminalConnection.Checking, TerminalConnection.Unknown -> {
                Step.Halted(SetupImportOutcome.Failed(Failure.Device(DeviceFault.UNEXPECTED)))
            }
        }
    }

    private suspend fun business(current: Candidate): Step {
        val prepared = current.prepared
        val choices = prepared.received.connection ?: transferredSetup
        if (!choices.needsReceiptDetails(prepared.settings.receipt, addressAndPhone = true)) return Step.Next(current)
        val result = checks.businessDetails.lookup(candidate(prepared))
        val withReceipt =
            if (result is ReceiptBusinesses.Found) {
                val business = result.business
                val selected =
                    ReceiptBusiness(
                        name = business.name.takeIf { choices.importReceiptName }.orEmpty(),
                        address = business.address.takeIf { choices.importReceiptAddress }.orEmpty(),
                        phone = business.phone.takeIf { choices.importReceiptPhone }.orEmpty(),
                    )
                prepared.withReceipt(selected.applyTo(prepared.settings.receipt))
            } else {
                prepared
            }
        val fromStore = (result as? ReceiptBusinesses.Found)?.fromStore != false
        val warning = choices.needsReceiptDetails(withReceipt.settings.receipt, addressAndPhone = fromStore)
        return Step.Next(current.copy(prepared = withReceipt.copy(businessWarning = warning)))
    }
}

/** Setup moved from another device carries no setup helper choices, so it may look up every blank receipt field. */
private val transferredSetup = ConnectionSetup()

internal fun PreparedTransfer.withSharedKey(key: DiscoveredKey): PreparedTransfer =
    withSharedKey(key.identifier, key.version, key.passphrase)

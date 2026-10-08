package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.ConnectionSetup
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.PreparedTransfer
import io.github.astiskala.minimpos.app.data.transfer.ReceivedTransfer
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
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
        val problem: SetupProblem? = null,
        val message: String? = null,
        val incomplete: Boolean = false,
    ) : SetupImportOutcome {
        val keyPending: Boolean get() = problem == SetupProblem.KEY_CONNECTION_PENDING
    }
}

/** Management checks and separately confirmed key setup used before activating an import candidate. */
class SetupImportChecks(
    /** Proposes store or merchant receipt fields without replacing saved merchant text. */
    val businessDetails: ReceiptBusinessDetails,
    /** Creates a read-only terminal-management client for the candidate credential. */
    val terminals: (String) -> TerminalDetailsApi,
    /** Owns key creation only after explicit consent, without changing active import configuration. */
    val sharedKeys: SharedKeySetup,
)

/** Verifies a scanned setup without replacing active configuration, then commits it with durable recovery.
 * @param transfer Authenticates transfer codes and owns encrypted commit persistence.
 * @param setups Resolves candidates and records local verification fingerprints.
 * @param gateway Tests candidate connections without publishing learned facts into active settings.
 * @param api Verifies Checkout access for the candidate account and environment.
 * @param tapToPay Performs only explicitly requested phone registration, retaining its recovery identity.
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
    private var missingKey = false

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
                val problem = verify(prepared, choices)
                if (problem != null) return@withLock problem
                val switch = historySwitches.preview(prepared.settings.terminal)
                if (switch != null && !switch.wasConfirmedBy(confirmedHistorySwitch)) {
                    return@withLock SetupImportOutcome.HistoryConfirmation(switch)
                }
                SetupImportOutcome.Committed(transfer.commit(prepared, mode, purgeHistory = switch != null, verified = ::remember))
            } catch (e: SecretStoreException) {
                SetupImportOutcome.Committed(ImportOutcome.StorageFailed(e.message.orEmpty()))
            }
        }

    internal suspend fun recover(): ImportOutcome? =
        mutex.withLock {
            if (busy()) return@withLock ImportOutcome.Rejected(SetupProblem.SETUP_CHANGED)
            transfer.recover(::remember)
        }

    private suspend fun recoverResult(): SetupImportOutcome =
        SetupImportOutcome.Committed(transfer.recover(::remember) ?: ImportOutcome.Rejected(SetupProblem.TRANSFER_PENDING))

    private suspend fun remember(): Boolean {
        val unlocked = setups.unlocked(forValidation = true)
        val remembered = setups.rememberVerified(unlocked)
        if (remembered) sharedKeys.complete(unlocked)
        return remembered
    }

    private fun candidate(prepared: PreparedTransfer): UnlockedSetup = setups.candidate(prepared.settings, prepared.secrets)

    private class Choices(
        val terminalId: String?,
        val board: Boolean,
        val confirmedHistory: HistorySwitchPlan?,
        val confirmedKey: SharedKeyOffer?,
    )

    private suspend fun verify(
        prepared: PreparedTransfer,
        choices: Choices,
    ): SetupImportOutcome? {
        missingKey = false
        if (!prepared.changesSetup || candidate(prepared).setup.apiSetup == ApiSetup.Simulated) return null
        return environment(prepared)
            ?: credential(prepared)
            ?: discovery(prepared, choices.terminalId)
            ?: requiredFields(prepared, allowMissingKey = missingKey)
            ?: checkout(prepared)
            ?: phone(prepared, choices.board)
            ?: sharedKey(prepared, choices.confirmedHistory, choices.confirmedKey)
            ?: requiredFields(prepared)
            ?: connection(prepared)
            ?: business(prepared)
    }

    private suspend fun environment(prepared: PreparedTransfer): SetupImportOutcome? {
        if (!candidate(prepared).setup.suppliesCertificateEnvironment) return null
        val environment = gateway.candidates.environment() ?: return SetupImportOutcome.Failed(SetupProblem.TERMINAL_ENVIRONMENT)
        prepared.settings = prepared.settings.copy(terminal = prepared.settings.terminal.selectEnvironment(environment))
        return null
    }

    private suspend fun credential(prepared: PreparedTransfer): SetupImportOutcome? {
        val unlocked = candidate(prepared)
        val key = unlocked.apiKey ?: return SetupImportOutcome.Failed(SetupProblem.API_KEY, incomplete = true)
        val environment = unlocked.setup.environment ?: return SetupImportOutcome.Failed(SetupProblem.ENVIRONMENT, incomplete = true)
        return when (val result = checks.terminals(key).credential(environment)) {
            CredentialLookup.Allowed -> null
            is CredentialLookup.Failed -> SetupImportOutcome.Failed(result.reason.setupProblem())
        }
    }

    private suspend fun discovery(
        prepared: PreparedTransfer,
        selected: String?,
    ): SetupImportOutcome? {
        val unlocked = candidate(prepared)
        if (!unlocked.setup.discoversTerminals) return null
        val management = checks.terminals(checkNotNull(unlocked.apiKey))
        return when (val result = management.terminals(checkNotNull(unlocked.setup.environment), unlocked.setup.poiId ?: selected)) {
            is TerminalListing.Failed -> SetupImportOutcome.Failed(result.reason.setupProblem())
            is TerminalListing.Listed -> chooseTerminal(prepared, unlocked.setup, management, result, selected)
        }
    }

    private suspend fun chooseTerminal(
        prepared: PreparedTransfer,
        setup: TerminalSetup,
        management: TerminalDetailsApi,
        listing: TerminalListing.Listed,
        selected: String?,
    ): SetupImportOutcome? {
        return when (
            val assignment =
                TerminalAssignments.discover(
                    listing.terminals,
                    setup.poiId ?: selected,
                    prepared.settings.terminal.merchantAccount,
                )
        ) {
            is TerminalAssignment.Choices -> {
                SetupImportOutcome.Terminals(assignment.ids)
            }

            is TerminalAssignment.Blocked -> {
                SetupImportOutcome.Failed(assignment.problem)
            }

            is TerminalAssignment.Assigned -> {
                val terminal = assignment.terminal
                applyAssignment(prepared, setup, terminal)
                if (prepared.received.automatic && setup.needsSharedKey) {
                    when (val found = management.sharedKey(terminal.id, listing.environment)) {
                        is SharedKeyLookup.Found -> applyKey(prepared, found.key)
                        SharedKeyLookup.Missing -> missingKey = true
                        is SharedKeyLookup.Failed -> return SetupImportOutcome.Failed(found.reason.setupProblem())
                    }
                }
                null
            }
        }
    }

    private fun applyAssignment(
        prepared: PreparedTransfer,
        setup: TerminalSetup,
        terminal: TerminalDetails,
    ) {
        val current = prepared.settings.terminal
        prepared.settings =
            prepared.settings.copy(
                terminal =
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

    private fun applyKey(
        prepared: PreparedTransfer,
        key: DiscoveredKey,
    ) {
        prepared.settings =
            prepared.settings.copy(terminal = prepared.settings.terminal.copy(keyIdentifier = key.identifier, keyVersion = key.version))
        prepared.secrets[Secret.TERMINAL_PASSPHRASE] = key.passphrase
        prepared.importedSecrets[Secret.TERMINAL_PASSPHRASE] = key.passphrase
    }

    private suspend fun sharedKey(
        prepared: PreparedTransfer,
        confirmedHistory: HistorySwitchPlan?,
        confirmedKey: SharedKeyOffer?,
    ): SetupImportOutcome? {
        val unlocked = candidate(prepared)
        if (!missingKey && sharedKeys.pendingOffer(unlocked) == null) return null
        val switch = historySwitches.preview(prepared.settings.terminal)
        if (switch != null && !switch.wasConfirmedBy(confirmedHistory)) return SetupImportOutcome.HistoryConfirmation(switch)
        return when (val result = sharedKeys.resolve(unlocked, confirmedKey) { transfer.unchanged(prepared) }) {
            is SharedKeySetupOutcome.Confirmation -> {
                SetupImportOutcome.KeyConfirmation(result.offer)
            }

            is SharedKeySetupOutcome.Ready -> {
                applyKey(prepared, result.key)
                null
            }

            is SharedKeySetupOutcome.Failed -> {
                SetupImportOutcome.Failed(result.problem, result.message)
            }
        }
    }

    private fun requiredFields(
        prepared: PreparedTransfer,
        allowMissingKey: Boolean = false,
    ): SetupImportOutcome? =
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
            }?.let { SetupImportOutcome.Failed(it, incomplete = true) }

    private suspend fun checkout(prepared: PreparedTransfer): SetupImportOutcome? =
        when (val result = api.verify(candidate(prepared))) {
            ApiCheck.Works -> null
            is ApiCheck.NotSetUp -> SetupImportOutcome.Failed(result.problem)
            is ApiCheck.Failed -> SetupImportOutcome.Failed(message = result.message)
        }

    private suspend fun phone(
        prepared: PreparedTransfer,
        explicit: Boolean,
    ): SetupImportOutcome? {
        if (!candidate(prepared).setup.boardsPhone) return null
        return when (val result = tapToPay.candidate(prepared.settings, prepared.secrets, explicit)) {
            is TapToPayOutcome.Boarded -> {
                prepared.settings =
                    prepared.settings.copy(terminal = prepared.settings.terminal.copy(paymentsAppInstallationId = result.installationId))
                prepared.paymentsAppChecked = true
                null
            }

            is TapToPayOutcome.NotSetUp -> {
                if (result.problem == SetupProblem.PAYMENTS_APP_NOT_BOARDED) {
                    SetupImportOutcome.BoardingRequired
                } else {
                    SetupImportOutcome.Failed(result.problem, incomplete = true)
                }
            }

            is TapToPayOutcome.Failed -> {
                SetupImportOutcome.Failed(message = result.message)
            }

            TapToPayOutcome.Unregistered -> {
                SetupImportOutcome.Failed(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
            }
        }
    }

    private suspend fun connection(prepared: PreparedTransfer): SetupImportOutcome? {
        if (!transfer.unchanged(prepared)) return SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
        val unlocked = candidate(prepared)
        val (connection, detected) = gateway.candidates.check(unlocked)
        if (connection !is TerminalConnection.Connected && sharedKeys.pendingOffer(unlocked) != null) {
            return SetupImportOutcome.Failed(SetupProblem.KEY_CONNECTION_PENDING)
        }
        return when (connection) {
            is TerminalConnection.Connected -> {
                if (detected != null && detected.environment != unlocked.setup.environment) {
                    SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
                } else {
                    detected?.let { prepared.settings = setups.learnedCandidate(it) }
                    null
                }
            }

            is TerminalConnection.NotSetUp -> {
                SetupImportOutcome.Failed(connection.problem, incomplete = true)
            }

            is TerminalConnection.Failed -> {
                SetupImportOutcome.Failed(message = connection.message)
            }

            TerminalConnection.Checking, TerminalConnection.Unknown -> {
                SetupImportOutcome.Failed()
            }
        }
    }

    private suspend fun business(prepared: PreparedTransfer): SetupImportOutcome? {
        val choices = prepared.received.connection ?: ConnectionSetup()
        if (!choices.needsReceiptDetails(prepared.settings.receipt)) return null
        val result = checks.businessDetails.lookup(candidate(prepared))
        if (result is ReceiptBusinesses.Found) {
            val business = result.business
            val selected =
                ReceiptBusiness(
                    name = business.name.takeIf { choices.importReceiptName }.orEmpty(),
                    address = business.address.takeIf { choices.importReceiptAddress }.orEmpty(),
                    phone = business.phone.takeIf { choices.importReceiptPhone }.orEmpty(),
                )
            prepared.settings = prepared.settings.copy(receipt = selected.applyTo(prepared.settings.receipt))
        }
        prepared.businessWarning = choices.needsReceiptDetails(prepared.settings.receipt)
        return null
    }
}

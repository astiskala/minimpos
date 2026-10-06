package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.PreparedTransfer
import io.github.astiskala.minimpos.app.data.transfer.ReceivedTransfer
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
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

    data class Businesses(
        val stores: List<ReceiptBusiness>,
    ) : SetupImportOutcome

    data object BoardingRequired : SetupImportOutcome

    data class Failed(
        val problem: SetupProblem? = null,
        val message: String? = null,
        val incomplete: Boolean = false,
    ) : SetupImportOutcome
}

/** Verifies a scanned setup without replacing active configuration, then commits it with durable recovery.
 * @param transfer Authenticates transfer codes and owns encrypted commit persistence.
 * @param setups Resolves candidates and records local verification fingerprints.
 * @param gateway Tests candidate connections without publishing learned facts into active settings.
 * @param api Verifies Checkout access for the candidate account and environment.
 * @param tapToPay Performs only explicitly requested phone registration, retaining its recovery identity.
 * @param businessDetails Reads proposed receipt fields; failures do not invalidate financial setup.
 * @param terminals Read-only Management client factory; tests supply a fake.
 * @param busy Whether a financial operation is running, so its configuration cannot be replaced.
 */
class SetupImport internal constructor(
    private val transfer: SetupTransfer,
    private val setups: TerminalSetupSource,
    private val gateway: TerminalGateway,
    private val api: AdyenApi,
    private val tapToPay: TapToPaySetup,
    private val businessDetails: ReceiptBusinessDetails,
    private val terminals: (String) -> TerminalDetailsApi,
    private val busy: () -> Boolean,
) {
    private val mutex = Mutex()

    internal suspend fun import(
        received: ReceivedTransfer,
        mode: ImportMode,
        code: String,
        terminalId: String? = null,
        businessId: String? = null,
        skipBusiness: Boolean = false,
        board: Boolean = false,
    ): SetupImportOutcome =
        mutex.withLock {
            if (busy()) return@withLock SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
            if (transfer.pending.first()) return@withLock recoverResult()
            val prepared = transfer.prepare(received, code) ?: return@withLock SetupImportOutcome.Committed(ImportOutcome.WrongCode)
            if (!prepared.original.acceptsSetupImport) return@withLock SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED)
            try {
                verify(prepared, terminalId, businessId, skipBusiness, board)
                    ?: SetupImportOutcome.Committed(transfer.commit(prepared, mode, ::remember))
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

    private suspend fun remember(): Boolean = setups.rememberVerified(setups.unlocked(forValidation = true))

    private fun candidate(prepared: PreparedTransfer): UnlockedSetup = setups.candidate(prepared.settings, prepared.secrets)

    private suspend fun verify(
        prepared: PreparedTransfer,
        terminalId: String?,
        businessId: String?,
        skipBusiness: Boolean,
        board: Boolean,
    ): SetupImportOutcome? {
        if (!prepared.changesSetup || candidate(prepared).setup.apiSetup == ApiSetup.Simulated) return null
        return environment(prepared)
            ?: credential(prepared)
            ?: discovery(prepared, terminalId)
            ?: requiredFields(prepared)
            ?: checkout(prepared)
            ?: phone(prepared, board)
            ?: connection(prepared)
            ?: business(prepared, businessId, skipBusiness)
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
        return when (val result = terminals(key).credential(environment)) {
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
        val management = terminals(checkNotNull(unlocked.apiKey))
        return when (val result = management.terminals(checkNotNull(unlocked.setup.environment))) {
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
        val id = setup.poiId ?: selected
        val account =
            prepared.settings.terminal.merchantAccount
                .trim()
        val eligible = listing.terminals.filter { it.merchantAccount.isNotBlank() && (account.isBlank() || it.merchantAccount == account) }
        val terminal = if (id != null) listing.terminals.firstOrNull { it.id == id } else eligible.singleOrNull()
        return when {
            terminal == null -> {
                if (id == null && eligible.size > 1) {
                    SetupImportOutcome.Terminals(eligible.map { it.id })
                } else {
                    SetupImportOutcome.Failed(SetupProblem.TERMINAL_ACCESS)
                }
            }

            account.isNotBlank() && account != terminal.merchantAccount -> {
                SetupImportOutcome.Failed(SetupProblem.MERCHANT_MISMATCH)
            }

            else -> {
                applyAssignment(prepared, setup, terminal)
                if (prepared.received.automatic && setup.needsSharedKey) {
                    management.sharedKey(terminal.id, listing.environment)?.let { key ->
                        prepared.settings =
                            prepared.settings.copy(
                                terminal = prepared.settings.terminal.copy(keyIdentifier = key.identifier, keyVersion = key.version),
                            )
                        prepared.secrets[Secret.TERMINAL_PASSPHRASE] = key.passphrase
                        prepared.importedSecrets[Secret.TERMINAL_PASSPHRASE] = key.passphrase
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

    private fun requiredFields(prepared: PreparedTransfer): SetupImportOutcome? =
        candidate(prepared).setup.importProblem(prepared.secrets.keys)?.let { SetupImportOutcome.Failed(it, incomplete = true) }

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

    private suspend fun business(
        prepared: PreparedTransfer,
        selected: String?,
        skip: Boolean,
    ): SetupImportOutcome? {
        if (skip || !prepared.settings.receipt.needsBusinessDetails()) return null
        return when (val result = businessDetails.stores(candidate(prepared))) {
            is ReceiptBusinesses.Listed -> {
                chooseBusiness(prepared, result.stores, selected)
            }

            is ReceiptBusinesses.Failed, is ReceiptBusinesses.NotSetUp -> {
                prepared.businessWarning = true
                null
            }
        }
    }

    private fun chooseBusiness(
        prepared: PreparedTransfer,
        stores: List<ReceiptBusiness>,
        selected: String?,
    ): SetupImportOutcome? {
        val business = if (selected == null) stores.singleOrNull() else stores.firstOrNull { it.id == selected }
        return when {
            business != null -> {
                prepared.settings = prepared.settings.copy(receipt = business.applyTo(prepared.settings.receipt))
                prepared.businessWarning = !business.available
                null
            }

            stores.size > 1 -> {
                SetupImportOutcome.Businesses(stores)
            }

            else -> {
                prepared.businessWarning = true
                null
            }
        }
    }
}

internal fun ReceiptSettings.needsBusinessDetails(): Boolean = businessName.isBlank() || addressLines.isBlank() || phone.isBlank()

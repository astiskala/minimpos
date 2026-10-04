package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.repo.DeleteResult
import io.github.astiskala.minimpos.app.data.repo.HistoryRepository
import io.github.astiskala.minimpos.app.data.security.PinManager
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.security.SessionLock
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.feature.ActionOutcome
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.feature.persisting
import io.github.astiskala.minimpos.app.feature.toState
import io.github.astiskala.minimpos.app.payment.ReceiptDelivery
import io.github.astiskala.minimpos.app.terminal.AdyenApi
import io.github.astiskala.minimpos.app.terminal.ApiCheck
import io.github.astiskala.minimpos.app.terminal.ReceiptBusinessDetails
import io.github.astiskala.minimpos.app.terminal.TerminalConnection
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.astiskala.minimpos.app.payment.PricingChanges as PricingChangeOperations

/**
 * What the settings screen shows.
 *
 * @property settings The stored settings.
 * @property secrets Which secrets are stored (their values are never exposed to the UI).
 * @property taxRates All tax rates, in display order.
 * @property taxRateUsage Number of products using each tax rate, by tax rate id.
 * @property loaded False until the settings, secrets and catalogue have been read.
 * @property sampleReceipt The sample receipt with the current settings, previewed and test-printed; null until loaded.
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val secrets: Set<Secret> = emptySet(),
    val taxRates: List<TaxRateEntity> = emptyList(),
    val taxRateUsage: Map<Long, Int> = emptyMap(),
    val loaded: Boolean = false,
    val sampleReceipt: ReceiptDocument? = null,
) {
    /** Whether an admin PIN is set. */
    val pinSet: Boolean get() = Secret.PIN_VERIFIER in secrets

    /** The rate new products and custom items start with: the configured one, else the first. */
    val defaultTaxRate: TaxRateEntity?
        get() = settings.payment.defaultTaxRate(taxRates)
}

/**
 * Outcomes of the settings screen's actions.
 *
 * @property connection The latest connection test; its message is shown in a dialog until
 *   [SettingsViewModel.dismissConnectionResult].
 * @property passphraseStored Whether the passphrase given to the latest connection test was stored (so the field can be
 *   cleared).
 * @property secretError Set when a secret (password, passphrase or PIN) could not be stored on this device.
 * @property email The latest test email.
 * @property print The latest test print.
 * @property cleared Whether the history has been cleared.
 * @property taxRateInUse Set when a tax rate could not be deleted: the number of products still using it.
 * @property api The latest Checkout API test.
 * @property apiKeyStored Whether the API key given to the latest test was stored (so the field can be cleared).
 */
data class SettingsActions(
    val connection: ActionState = ActionState(),
    val passphraseStored: Boolean = false,
    val secretError: ActionOutcome.SecretNotStored? = null,
    val email: ActionState = ActionState(),
    val print: ActionState = ActionState(),
    val cleared: Boolean = false,
    val taxRateInUse: Int? = null,
    val api: ActionState = ActionState(),
    val apiKeyStored: Boolean = false,
)

/** What [SettingsViewModel.saveAndTest] checks after saving. */
enum class SettingsTest {
    /** The connection to where payments go, shown in a dialog. */
    CONNECTION,

    /** The Adyen API key. */
    API,

    /**
     * A terminal in the cloud and the Checkout API, which share the API key: the connection, shown in a dialog, then
     * (once connected) the Checkout API, whose outcome the dialog shows too.
     */
    CLOUD,
}

/**
 * The services behind the settings screen's test buttons.
 *
 * @property status Tests the connection to the terminal, which also tells whether it has a printer.
 * @property receipts Prints the test receipt and sends the test email.
 * @property api Tests the Adyen API key.
 * @property businessDetails Reads store receipt fields for reviewed import.
 */
class SettingsChecks(
    val status: TerminalStatus,
    val receipts: ReceiptDelivery,
    val api: AdyenApi,
    val businessDetails: ReceiptBusinessDetails,
)

/**
 * All of Settings: stored settings, secrets and the PIN, tax rates, the connection, email and print tests, and
 * clearing history. Secrets are only ever written and checked for presence here, never shown.
 *
 * @param pricingChanges Owns settings writes, pricing confirmation, session repricing and recovery.
 * @param secrets The stored secrets.
 * @param pins The admin PIN.
 * @param sessionLock Kept unlocked when a PIN is set here.
 * @param checks The services behind the test buttons.
 * @param history Cleared from here.
 * @param catalog The tax rates.
 * @param sampleReceipt The sample receipt with the given settings.
 * @param managerPins The Manager PIN instance, so resetting it also clears its live lockout.
 */
class SettingsViewModel(
    private val pricingChanges: PricingChangeOperations,
    private val secrets: SecretStore,
    private val pins: PinManager,
    private val sessionLock: SessionLock,
    private val checks: SettingsChecks,
    private val history: HistoryRepository,
    private val catalog: CatalogRepository,
    sampleReceipt: (AppSettings) -> ReceiptDocument,
    private val managerPins: PinManager = PinManager(secrets, verifierSecret = Secret.MANAGER_PIN_VERIFIER),
) : ViewModel() {
    /** The screen state, updated whenever settings, secrets or the catalogue change. */
    val state: StateFlow<SettingsUiState> =
        combine(
            pricingChanges.changes,
            secrets.configured,
            catalog.taxRates,
            catalog.products,
        ) { appSettings, configured, taxRates, products ->
            SettingsUiState(
                appSettings,
                configured,
                taxRates,
                products
                    .mapNotNull {
                        it.taxRateId
                    }.groupingBy { it }
                    .eachCount(),
                loaded = true,
                sampleReceipt = sampleReceipt(appSettings),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    private val _actions = MutableStateFlow(SettingsActions())

    /** Outcomes of the latest actions. */
    val actions: StateFlow<SettingsActions> = _actions.asStateFlow()

    private val pricingConfirmation = PricingChanges(this, pricingChanges)

    /** Store receipt proposals and confirmation actions, without automatic replacement of merchant text. */
    internal val businessImport = ReceiptBusinessImports(this, checks.businessDetails, pricingChanges, state)

    /** Stores ordinary settings; currency and tax-style changes require confirmation. */
    fun update(transform: (AppSettings) -> AppSettings) = pricingConfirmation.update(transform)

    /** Preview state and confirmation actions for pricing changes. */
    internal val pricing = pricingConfirmation

    /** Adds (ID 0) or updates [taxRate], trimming its name, and makes it the default rate when [makeDefault]. */
    fun saveTaxRate(
        taxRate: TaxRateEntity,
        makeDefault: Boolean,
    ) {
        _actions.update { it.copy(taxRateInUse = null) }
        launchWrite({
            val id = catalog.saveTaxRate(taxRate.copy(name = taxRate.name.trim()))
            if (makeDefault) pricingChanges.update { it.copy(payment = it.payment.copy(defaultTaxRateId = id)) }
        })
    }

    /** Deletes [taxRate] unless products still use it or it is the last one (products and custom items need a rate). */
    fun deleteTaxRate(taxRate: TaxRateEntity) {
        if (state.value.taxRates.size <= 1) return
        launchWrite({ catalog.deleteTaxRate(taxRate) }) { result ->
            when (result) {
                DeleteResult.Deleted -> _actions.update { it.copy(taxRateInUse = null) }
                is DeleteResult.InUse -> _actions.update { it.copy(taxRateInUse = result.productCount) }
            }
        }
    }

    /** Stores [value] as [secret], or removes it for null or empty; a failure is reported in [SettingsActions.secretError]. */
    fun setSecret(
        secret: Secret,
        value: String?,
    ) {
        if (secret == Secret.ADYEN_API_KEY) _actions.update { it.copy(apiKeyStored = false) }
        launchWrite({
            if (storeSecret { secrets.set(secret, value) } && secret == Secret.ADYEN_API_KEY && !value.isNullOrBlank()) {
                _actions.update { it.copy(apiKeyStored = true) }
            }
        })
    }

    /** Sets [pin], which must be [PinManager.isValidPin]; [manager] selects the Manager PIN and requires an admin PIN. */
    fun setPin(
        pin: String,
        manager: Boolean = false,
    ) {
        if (manager && !state.value.pinSet) return
        val target = if (manager) managerPins else pins
        launchWrite({ if (storeSecret { target.setPin(pin) } && !manager) sessionLock.unlock() })
    }

    /** Removes admin protection and the Manager PIN it protects, after the screen confirms both removals. */
    fun clearPin() {
        launchWrite({
            pins.clearPin()
            secrets.set(Secret.MANAGER_PIN_VERIFIER, null)
        })
    }

    /** Runs [store], reporting (instead of crashing on) a device that cannot encrypt secrets. Returns whether it worked. */
    private suspend fun storeSecret(store: suspend () -> Unit): Boolean =
        try {
            store()
            _actions.update { it.copy(secretError = null) }
            true
        } catch (e: SecretStoreException) {
            _actions.update { it.copy(secretError = ActionOutcome.SecretNotStored(e.message)) }
            false
        }

    /**
     * Saves [value] as [secret] if one was entered (an API key without surrounding spaces) and verifies that it reads
     * back, then runs [test]: by default the terminal connection for [Secret.TERMINAL_PASSPHRASE] and the Checkout API
     * for [Secret.ADYEN_API_KEY] (which also reaches terminals in the cloud, so its test can be both,
     * [SettingsTest.CLOUD]). No test charges anything. The connection's outcome is shown until
     * [dismissConnectionResult].
     *
     * @throws IllegalArgumentException for a secret that cannot be tested.
     */
    fun saveAndTest(
        secret: Secret,
        value: String? = null,
        test: SettingsTest = if (secret == Secret.ADYEN_API_KEY) SettingsTest.API else SettingsTest.CONNECTION,
    ) {
        require(secret == Secret.TERMINAL_PASSPHRASE || secret == Secret.ADYEN_API_KEY) { "$secret cannot be tested" }
        val api = test == SettingsTest.API
        val apiKey = secret == Secret.ADYEN_API_KEY

        fun SettingsActions.with(state: ActionState) = if (api) copy(api = state) else copy(connection = state)
        _actions.update {
            val started = it.with(ActionState(running = true)).copy(apiKeyStored = false, passphraseStored = false)
            if (test == SettingsTest.CLOUD) started.copy(api = ActionState()) else started
        }
        viewModelScope.launch {
            val entered = (if (apiKey) value?.trim() else value)?.takeIf { it.isNotEmpty() }
            if (entered != null) {
                val stored = persisting { storeSecret { secrets.set(secret, entered) } } && secrets.get(secret) == entered
                if (!stored) {
                    val error = _actions.value.secretError ?: ActionOutcome.SecretNotStored("it did not read back")
                    _actions.update { it.with(ActionState(outcome = error, isError = true)) }
                    return@launch
                }
                _actions.update { if (apiKey) it.copy(apiKeyStored = true) else it.copy(passphraseStored = true) }
            }
            run(test)
        }
    }

    /** Runs [test] and shows its outcome. */
    private suspend fun run(test: SettingsTest) {
        when (test) {
            SettingsTest.API -> {
                _actions.update { it.copy(api = apiResult()) }
            }

            SettingsTest.CONNECTION -> {
                _actions.update { it.copy(connection = connectionResult()) }
            }

            SettingsTest.CLOUD -> {
                val connection = connectionResult()
                val checked = if (connection.done) apiResult() else ActionState()
                _actions.update { it.copy(connection = connection, api = checked) }
            }
        }
    }

    private suspend fun connectionResult(): ActionState =
        when (val connection = checks.status.check()) {
            is TerminalConnection.Connected -> {
                val diagnosis = connection.diagnosis
                ActionState(outcome = ActionOutcome.Connected(diagnosis.globalStatus, diagnosis.hasPrinter), done = true)
            }

            is TerminalConnection.NotSetUp -> {
                ActionState(outcome = ActionOutcome.NotSetUp(connection.problem), isError = true)
            }

            is TerminalConnection.Failed -> {
                ActionState(outcome = ActionOutcome.ConnectionFailed(connection.message), isError = true)
            }

            TerminalConnection.Checking, TerminalConnection.Unknown -> {
                ActionState()
            }
        }

    private suspend fun apiResult(): ActionState =
        when (val check = checks.api.verify()) {
            ApiCheck.Works -> ActionState(outcome = ActionOutcome.ApiWorks, done = true)
            is ApiCheck.NotSetUp -> ActionState(outcome = ActionOutcome.NotSetUp(check.problem), isError = true)
            is ApiCheck.Failed -> ActionState(outcome = ActionOutcome.Failed(check.message), isError = true)
        }

    /** Closes the connection test's result dialog. */
    fun dismissConnectionResult() {
        _actions.update { it.copy(connection = ActionState()) }
    }

    /** Sends a test email to [to] with the stored SMTP settings. */
    fun sendTestEmail(to: String) {
        _actions.update { it.copy(email = ActionState(running = true)) }
        viewModelScope.launch {
            val result = checks.receipts.sendTestEmail(to).toState(ActionOutcome.TestEmailSent(to))
            _actions.update { it.copy(email = result) }
        }
    }

    /** Prints the [SettingsUiState.sampleReceipt] to check the receipt layout; ignored until it is loaded. */
    fun printTest() {
        val document = state.value.sampleReceipt ?: return
        _actions.update { it.copy(print = ActionState(running = true)) }
        viewModelScope.launch {
            val result = checks.receipts.printDocument(document)
            _actions.update { it.copy(print = result.toState(ActionOutcome.Printed)) }
        }
    }

    /** Deletes every sale and refund; the catalogue and settings stay. */
    fun clearHistory() {
        launchWrite({ history.clear() }) { _ -> _actions.update { it.copy(cleared = true) } }
    }
}

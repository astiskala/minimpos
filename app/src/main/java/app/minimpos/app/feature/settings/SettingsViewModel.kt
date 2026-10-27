package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.repo.DeleteResult
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.security.PinManager
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.persisting
import app.minimpos.app.feature.runAction
import app.minimpos.app.feature.toState
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.app.terminal.HistorySwitches
import app.minimpos.app.terminal.ReceiptBusinessDetails
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalStatus
import app.minimpos.core.logo.MonoBitmap
import app.minimpos.core.receipt.ReceiptDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.minimpos.app.payment.PricingChanges as PricingChangeOperations

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
 *   [SettingsViewModel.dismissTestResult].
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
    val secretError: ActionOutcome.Failed? = null,
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
     * A terminal in the cloud and the Checkout API, which share the API key. The final connection check verifies both;
     * its dialog includes the successful API reading too.
     */
    CLOUD,
}

/** Which completed settings test popup is being acknowledged; separate from secret-testing commands. */
enum class SettingsResult {
    /** Terminal connection test. */
    CONNECTION,

    /** Checkout API test. */
    API,

    /** Combined cloud connection and Checkout API test. */
    CLOUD,

    /** Receipt test print. */
    PRINT,

    /** Receipt test email. */
    EMAIL,

    /** History clearing result. */
    HISTORY,
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
 * @param sampleReceipt Builds the sample receipt with the given settings and current logo.
 * @param managerPins The Manager PIN instance, so resetting it also clears its live lockout.
 * @param historySwitches Owns explicitly confirmed environment-change deletion and recovery.
 * @param cpu Dispatcher for catalogue counts and sample receipt construction.
 * @param logos Current receipt image, including null after removal; every change rebuilds the preview.
 */
class SettingsViewModel(
    private val pricingChanges: PricingChangeOperations,
    private val secrets: SecretStore,
    private val pins: PinManager,
    private val sessionLock: SessionLock,
    private val checks: SettingsChecks,
    private val history: HistoryRepository,
    private val catalog: CatalogRepository,
    sampleReceipt: (AppSettings, MonoBitmap?) -> ReceiptDocument,
    private val managerPins: PinManager = PinManager(secrets, verifierSecret = Secret.MANAGER_PIN_VERIFIER),
    /** Confirmed history deletion on LIVE/TEST/simulator changes. */
    historySwitches: HistorySwitches? = null,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    logos: Flow<MonoBitmap?> = flowOf(null),
) : ViewModel() {
    private val productCounts = catalog.products.map { products -> products.groupingBy { it.taxRateId }.eachCount() }.distinctUntilChanged()
    private val sample =
        pricingChanges.changes
            .distinctUntilChanged { before, after ->
                before.receipt == after.receipt && before.languageTag == after.languageTag &&
                    before.payment.currencyCode == after.payment.currencyCode && before.payment.taxMode == after.payment.taxMode &&
                    before.payment.asksCustomerReference == after.payment.asksCustomerReference
            }.combine(logos, sampleReceipt)

    /** The screen state, updated whenever settings, secrets or the catalogue change. */
    val state: StateFlow<SettingsUiState> =
        combine(
            pricingChanges.changes,
            secrets.configured,
            catalog.taxRates,
            productCounts,
            sample,
        ) { appSettings, configured, taxRates, counts, receipt ->
            SettingsUiState(
                appSettings,
                configured,
                taxRates,
                counts,
                loaded = true,
                sampleReceipt = receipt,
            )
        }.flowOn(cpu).stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /** Outcomes of the latest actions. */
    val actions: StateFlow<SettingsActions>
        field = MutableStateFlow(SettingsActions())

    /** Explicit environment-change confirmation, separate from pricing confirmation. */
    internal val environment = HistorySwitchConfirmation(this, historySwitches)

    /** Preview state and confirmation actions for pricing changes. */
    internal val pricing = PricingChanges(this, pricingChanges, historySwitches, environment)

    /** Store receipt proposals and confirmation actions, without automatic replacement of merchant text. */
    internal val businessImport =
        ReceiptBusinessImports(this, checks.businessDetails, pricingChanges, state.map { it.settings.receipt }, checks.api)

    /** Stores ordinary settings; pricing and history-destructive environment changes require confirmation. */
    fun update(transform: (AppSettings) -> AppSettings) = pricing.edit(transform)

    /** Adds (ID 0) or updates [taxRate], trimming its name, and makes it the default rate when [makeDefault]. */
    fun saveTaxRate(
        taxRate: TaxRateEntity,
        makeDefault: Boolean,
    ) {
        actions.update { it.copy(taxRateInUse = null) }
        launchWrite({
            val id = catalog.saveTaxRate(taxRate.copy(name = taxRate.name.trim()))
            if (makeDefault) pricingChanges.update { it.copy(payment = it.payment.copy(defaultTaxRateId = id)) }
        })
    }

    /** Deletes [taxRate] unless products still use it or it is the last one (products and custom items need a rate). */
    fun deleteTaxRate(taxRate: TaxRateEntity) {
        launchWrite({ catalog.deleteTaxRate(taxRate) }) { result ->
            when (result) {
                DeleteResult.Deleted, DeleteResult.LastRate -> actions.update { it.copy(taxRateInUse = null) }
                is DeleteResult.InUse -> actions.update { it.copy(taxRateInUse = result.productCount) }
            }
        }
    }

    /** Stores [value] as [secret], or removes it for null or empty; a failure is reported in [SettingsActions.secretError]. */
    fun setSecret(
        secret: Secret,
        value: String?,
    ) {
        if (secret == Secret.ADYEN_API_KEY) actions.update { it.copy(apiKeyStored = false) }
        launchWrite({
            if (storeSecret { secrets.set(secret, value) } && secret == Secret.ADYEN_API_KEY && !value.isNullOrBlank()) {
                actions.update { it.copy(apiKeyStored = true) }
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
        when (val error = runSecretWrite(store)) {
            null -> {
                actions.update { it.copy(secretError = null) }
                true
            }

            else -> {
                actions.update { it.copy(secretError = error) }
                false
            }
        }

    /**
     * Saves [value] as [secret] if one was entered (an API key without surrounding spaces) and verifies that it reads
     * back, then runs [test]: by default the terminal connection for [Secret.TERMINAL_PASSPHRASE] and the Checkout API
     * for [Secret.ADYEN_API_KEY] (which also reaches terminals in the cloud, so its test can be both,
     * [SettingsTest.CLOUD]). No test charges anything. Each outcome is shown until [dismissTestResult].
     *
     * @param secret The API key or terminal passphrase to save and test.
     * @param value Unsaved secret text; null or empty keeps the stored secret. API keys are trimmed.
     * @param test Which read-only verification to run after saving.
     * @param onApiVerified Optional continuation after a successful API-only test; its success dialog is skipped.
     * @throws IllegalArgumentException for a secret that cannot be tested or a continuation on another test.
     */
    fun saveAndTest(
        secret: Secret,
        value: String? = null,
        test: SettingsTest = if (secret == Secret.ADYEN_API_KEY) SettingsTest.API else SettingsTest.CONNECTION,
        onApiVerified: (() -> Unit)? = null,
    ) {
        require(secret == Secret.TERMINAL_PASSPHRASE || secret == Secret.ADYEN_API_KEY) { "$secret cannot be tested" }
        require(onApiVerified == null || test == SettingsTest.API) { "Only API tests can continue discovery" }
        val api = test == SettingsTest.API
        val apiKey = secret == Secret.ADYEN_API_KEY

        fun SettingsActions.with(state: ActionState) = if (api) copy(api = state) else copy(connection = state)
        actions.update {
            val started = it.with(ActionState(running = true)).copy(apiKeyStored = false, passphraseStored = false)
            if (test == SettingsTest.CLOUD) started.copy(api = ActionState()) else started
        }
        viewModelScope.launch {
            val entered = (if (apiKey) value?.trim() else value)?.takeIf { it.isNotEmpty() }
            if (entered != null) {
                val stored = persisting { storeSecret { secrets.set(secret, entered) } } && secrets.get(secret) == entered
                if (!stored) {
                    val error = actions.value.secretError ?: SECURE_STORAGE_FAILED
                    actions.update { it.with(ActionState(outcome = error, isError = true)) }
                    return@launch
                }
                actions.update { if (apiKey) it.copy(apiKeyStored = true) else it.copy(passphraseStored = true) }
            }
            run(test, onApiVerified)
        }
    }

    /** Runs [test], continuing through [onApiVerified] after API success or showing the outcome. */
    private suspend fun run(
        test: SettingsTest,
        onApiVerified: (() -> Unit)?,
    ) {
        when (test) {
            SettingsTest.API -> {
                val result = apiResult()
                if (result.done && onApiVerified != null) {
                    actions.update { it.copy(api = result.dismissed()) }
                    onApiVerified()
                } else {
                    actions.update { it.copy(api = result) }
                }
            }

            SettingsTest.CONNECTION -> {
                val result = connectionResult()
                actions.update { it.copy(connection = result) }
            }

            SettingsTest.CLOUD -> {
                val connection = connectionResult()
                val checked = if (connection.done) ActionState(outcome = ActionOutcome.ApiWorks, done = true) else ActionState()
                actions.update { it.copy(connection = connection, api = checked) }
            }
        }
    }

    private suspend fun connectionResult(): ActionState =
        when (val connection = checks.status.check(includeDiagnostics = true)) {
            is TerminalConnection.Connected -> {
                val diagnosis = connection.diagnosis
                businessImport.findIfMissing()
                ActionState(
                    outcome = ActionOutcome.Connected(diagnosis.globalStatus, diagnosis.hasPrinter, connection.setupOnly),
                    done = true,
                )
            }

            is TerminalConnection.NotSetUp -> {
                ActionState(outcome = ActionOutcome.Failed(Failure.NotSetUp(connection.problem)), isError = true)
            }

            is TerminalConnection.Failed -> {
                ActionState(outcome = ActionOutcome.ConnectionFailed(connection.failure), isError = true)
            }

            TerminalConnection.Checking, TerminalConnection.Unknown -> {
                ActionState()
            }
        }

    private suspend fun apiResult(): ActionState =
        when (val check = checks.api.verify()) {
            ApiCheck.Works -> ActionState(outcome = ActionOutcome.ApiWorks, done = true)
            is ApiCheck.NotSetUp -> ActionState(outcome = ActionOutcome.Failed(Failure.NotSetUp(check.problem)), isError = true)
            is ApiCheck.Failed -> ActionState(outcome = ActionOutcome.Failed(check.failure), isError = true)
        }

    /**
     * Closes the result dialog of [test]: the Checkout API test's, keeping whether it succeeded; the connection test's;
     * or, for [SettingsResult.CLOUD], the connection test's together with the Checkout API result it showed.
     * Receipt test results keep their success or failure state after acknowledgment too.
     */
    fun dismissTestResult(test: SettingsResult) {
        actions.update {
            when (test) {
                SettingsResult.CONNECTION -> it.copy(connection = ActionState())
                SettingsResult.API -> it.copy(api = it.api.dismissed())
                SettingsResult.CLOUD -> it.copy(connection = ActionState(), api = it.api.dismissed())
                SettingsResult.PRINT -> it.copy(print = it.print.dismissed())
                SettingsResult.EMAIL -> it.copy(email = it.email.dismissed())
                SettingsResult.HISTORY -> it.copy(cleared = false)
            }
        }
    }

    /** Sends a test email to [to] with the stored SMTP settings. */
    fun sendTestEmail(to: String) =
        runAction(
            actions,
            { it.email },
            { copy(email = it) },
        ) { checks.receipts.sendTestEmail(to).toState(ActionOutcome.TestEmailSent(to)) }

    /** Prints the [SettingsUiState.sampleReceipt] to check the receipt layout; ignored until it is loaded. */
    fun printTest() {
        val document = state.value.sampleReceipt ?: return
        runAction(actions, { it.print }, { copy(print = it) }) { checks.receipts.printDocument(document).toState() }
    }

    /** Deletes every sale and refund; the catalogue and settings stay. */
    fun clearHistory() {
        launchWrite({ history.clear() }) { _ -> actions.update { it.copy(cleared = true) } }
    }
}

/** A secret could not be stored, or did not read back, on this device. */
internal val SECURE_STORAGE_FAILED = ActionOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE))

/** Runs [write] and maps a [SecretStoreException] into [SECURE_STORAGE_FAILED] so callers share one failure translation. */
internal suspend fun runSecretWrite(write: suspend () -> Unit): ActionOutcome.Failed? =
    try {
        write()
        null
    } catch (ignored: SecretStoreException) {
        SECURE_STORAGE_FAILED
    }

/** Stores [value] as [secret] through [runSecretWrite]. */
internal suspend fun SecretStore.storeSafely(
    secret: Secret,
    value: String?,
): ActionOutcome.Failed? = runSecretWrite { set(secret, value) }

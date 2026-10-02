package io.minimpos.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.BuildConfig
import io.minimpos.app.R
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.MerchantCopyPolicy
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.data.settings.SimulatorSettings
import io.minimpos.app.data.settings.SmtpSecurity
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.app.feature.OutcomeMessage
import io.minimpos.app.feature.lock.SetPinScreen
import io.minimpos.app.feature.text
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.app.terminal.TerminalConnection
import io.minimpos.app.ui.components.ActionMessage
import io.minimpos.app.ui.components.ConfirmDialog
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.ReceiptPreview
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.SectionHeader
import io.minimpos.app.ui.components.StatusBadge
import io.minimpos.app.ui.components.StatusKind
import io.minimpos.app.ui.components.TertiaryButton
import io.minimpos.app.ui.components.TextInputDialog
import io.minimpos.app.ui.components.currentLocale
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.app.ui.theme.LocalStatusColors
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.receipt.label
import io.minimpos.core.shopper.EmailReferenceMode
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.simulator.SimulatedOutcome
import io.minimpos.terminal.transport.TerminalEnvironment

@Composable
private fun settingsViewModel(): SettingsViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        SettingsViewModel(
            settings = container.settings,
            secrets = container.secrets,
            pins = container.pinManager,
            sessionLock = container.sessionLock,
            checks = SettingsChecks(container.terminalStatus, container.receipts, container.api),
            history = container.history,
            catalog = container.catalog,
            sampleReceipt = container::sampleReceipt,
        )
    }
}

@Composable
private fun terminalSetupViewModel(): TerminalSetupViewModel {
    val container = LocalAppContainer.current
    return viewModel { TerminalSetupViewModel(container.settings, container.secrets, container.terminalStatus, container.tapToPay) }
}

/** What the settings sections ask [SettingsViewModel] to do, so that they get callbacks rather than the view model. */
internal interface SettingsEvents {
    /** Stores the settings with [transform] applied ([SettingsViewModel.update]). */
    fun onUpdate(transform: (AppSettings) -> AppSettings)

    /** Stores [value] as [secret], or removes it for null ([SettingsViewModel.setSecret]). */
    fun onSecretChange(
        secret: Secret,
        value: String?,
    )

    /** Saves [value] as [secret] unless it is null, then runs [test] ([SettingsViewModel.saveAndTest]). */
    fun onSaveAndTest(
        secret: Secret,
        value: String?,
        test: SettingsTest,
    )

    /** Closes the connection test's result ([SettingsViewModel.dismissConnectionResult]). */
    fun onConnectionResultDismiss()

    /** Prints the sample receipt ([SettingsViewModel.printTest]). */
    fun onTestPrint()

    /** Sends a test email [to] an address ([SettingsViewModel.sendTestEmail]). */
    fun onTestEmailSend(to: String)

    /** Removes the admin PIN ([SettingsViewModel.clearPin]). */
    fun onPinClear()

    /** Deletes every sale and refund ([SettingsViewModel.clearHistory]). */
    fun onHistoryClear()

    /** Adds or updates [taxRate], making it the default when [makeDefault] ([SettingsViewModel.saveTaxRate]). */
    fun onTaxRateSave(
        taxRate: TaxRateEntity,
        makeDefault: Boolean,
    )

    /** Deletes [taxRate] unless products still use it ([SettingsViewModel.deleteTaxRate]). */
    fun onTaxRateDelete(taxRate: TaxRateEntity)
}

/** What Settings › Terminal asks [TerminalSetupViewModel] to do, as [SettingsEvents] does for [SettingsViewModel]. */
internal interface TerminalSetupEvents {
    /** Lists the terminals connected in the cloud ([TerminalSetupViewModel.findTerminals]). */
    fun onTerminalsFind()

    /** Takes [poiId] as the terminal in the cloud; null only closes the list ([TerminalSetupViewModel.chooseTerminal]). */
    fun onTerminalChoose(poiId: String?)

    /** Sets up Tap to Pay, saving [apiKey] first ([TerminalSetupViewModel.setUpTapToPay]). */
    fun onTapToPaySetUp(
        apiKey: String,
        again: Boolean,
    )

    /** Removes this phone's Payments app instance ([TerminalSetupViewModel.removeTapToPay]). */
    fun onTapToPayRemove()
}

private fun settingsEvents(settings: SettingsViewModel): SettingsEvents =
    object : SettingsEvents {
        override fun onUpdate(transform: (AppSettings) -> AppSettings) = settings.update(transform)

        override fun onSecretChange(
            secret: Secret,
            value: String?,
        ) = settings.setSecret(secret, value)

        override fun onSaveAndTest(
            secret: Secret,
            value: String?,
            test: SettingsTest,
        ) = settings.saveAndTest(secret, value, test)

        override fun onConnectionResultDismiss() = settings.dismissConnectionResult()

        override fun onTestPrint() = settings.printTest()

        override fun onTestEmailSend(to: String) = settings.sendTestEmail(to)

        override fun onPinClear() = settings.clearPin()

        override fun onHistoryClear() = settings.clearHistory()

        override fun onTaxRateSave(
            taxRate: TaxRateEntity,
            makeDefault: Boolean,
        ) = settings.saveTaxRate(taxRate, makeDefault)

        override fun onTaxRateDelete(taxRate: TaxRateEntity) = settings.deleteTaxRate(taxRate)
    }

private fun terminalSetupEvents(setup: TerminalSetupViewModel): TerminalSetupEvents =
    object : TerminalSetupEvents {
        override fun onTerminalsFind() = setup.findTerminals()

        override fun onTerminalChoose(poiId: String?) = setup.chooseTerminal(poiId)

        override fun onTapToPaySetUp(
            apiKey: String,
            again: Boolean,
        ) = setup.setUpTapToPay(apiKey, again)

        override fun onTapToPayRemove() = setup.removeTapToPay()
    }

/** The list of settings sections, each with a summary of its current value, plus the connection status. */
@Composable
fun SettingsScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm = settingsViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    val mode = terminal.mode

    fun open(section: String) = navigator.push(Route.SettingsSection(section))
    MiniScaffold(title = stringResource(R.string.settings_title), onBack = navigator::back, modifier = modifier) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp)) {
                PaymentSectionRows(
                    terminalSummary =
                        if (mode == TerminalMode.SIMULATOR) {
                            stringResource(R.string.settings_mode_simulator)
                        } else {
                            connectionTitle(terminal.connection)
                        },
                    simulatorOutcome = settings.simulator.outcome.takeIf { mode == TerminalMode.SIMULATOR },
                    currency = container.currency(settings).code,
                    taxSummary =
                        if (settings.payment.chargeTax) {
                            state.defaultTaxRate?.let { AppliedTax(it.name, it.rateMilliPercent).label() }
                        } else {
                            stringResource(R.string.settings_tax_off)
                        },
                    onOpen = ::open,
                )
                OtherSectionRows(settings, state.pinSet, onOpen = ::open)
            }
        }
    }
}

/** Rows for the sections that decide how payments are taken; the simulator's only while payments go to it. */
@Composable
private fun ColumnScope.PaymentSectionRows(
    terminalSummary: String,
    simulatorOutcome: SimulatedOutcome?,
    currency: String,
    taxSummary: String?,
    onOpen: (section: String) -> Unit,
) {
    SettingNavRow(
        Icons.Default.PhonelinkSetup,
        stringResource(R.string.settings_terminal),
        terminalSummary,
        { onOpen(SettingsSections.TERMINAL) },
        tag = "section_terminal",
    )
    simulatorOutcome?.let { outcome ->
        SettingNavRow(Icons.Default.Science, stringResource(R.string.settings_simulator), simulatorOutcomeLabel(outcome), {
            onOpen(SettingsSections.SIMULATOR)
        })
    }
    SettingNavRow(
        Icons.Default.Payments,
        stringResource(R.string.settings_payments),
        currency,
        { onOpen(SettingsSections.PAYMENTS) },
        tag = "section_payments",
    )
    SettingNavRow(
        Icons.Default.Percent,
        stringResource(R.string.settings_tax),
        taxSummary,
        { onOpen(SettingsSections.TAX) },
        tag = "section_tax",
    )
}

/** Rows for receipts, email, security, data and About. */
@Composable
private fun ColumnScope.OtherSectionRows(
    settings: AppSettings,
    pinSet: Boolean,
    onOpen: (section: String) -> Unit,
) {
    SettingNavRow(
        Icons.Default.Receipt,
        stringResource(R.string.settings_receipts),
        settings.receipt.businessName.ifBlank { null },
        { onOpen(SettingsSections.RECEIPTS) },
        tag = "section_receipts",
    )
    SettingNavRow(
        Icons.Default.Email,
        stringResource(R.string.settings_email),
        if (settings.email.isConfigured) settings.email.host else stringResource(R.string.settings_not_configured),
        { onOpen(SettingsSections.EMAIL) },
        tag = "section_email",
    )
    SettingNavRow(
        Icons.Default.Lock,
        stringResource(R.string.settings_security),
        stringResource(if (pinSet) R.string.settings_pin_on else R.string.settings_pin_off),
        { onOpen(SettingsSections.SECURITY) },
        tag = "section_security",
    )
    SettingNavRow(Icons.Default.Storage, stringResource(R.string.settings_data), null, { onOpen(SettingsSections.DATA) })
    SettingNavRow(Icons.Default.Info, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME, {
        onOpen(SettingsSections.ABOUT)
    }, tag = "section_about")
}

/** One settings section, chosen by [section] (one of the [SettingsSections] keys; unknown keys show About). */
@Composable
fun SettingsSectionScreen(
    section: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val vm = settingsViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val setup = terminalSetupViewModel()
    val setupActions by setup.actions.collectAsStateWithLifecycle()
    val events = remember(vm) { settingsEvents(vm) }
    val setupEvents = remember(setup) { terminalSetupEvents(setup) }
    var settingPin by remember { mutableStateOf(false) }
    if (settingPin) {
        SetPinScreen(onDone = {
            vm.setPin(it)
            settingPin = false
        }, onCancel = { settingPin = false }, modifier = modifier)
        return
    }
    MiniScaffold(title = stringResource(sectionTitle(section)), onBack = navigator::back, modifier = modifier) { padding ->
        if (!state.loaded) return@MiniScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).padding(bottom = 32.dp)) {
                when (section) {
                    SettingsSections.TERMINAL -> {
                        TerminalSection(state, actions, events, navigator)
                        TerminalApiSection(state, actions, setupActions, events, setupEvents)
                        TerminalAdvancedSection(state, actions, events)
                    }

                    SettingsSections.SIMULATOR -> {
                        SimulatorSection(state, events)
                    }

                    SettingsSections.PAYMENTS -> {
                        PaymentsSection(state, events)
                    }

                    SettingsSections.TAX -> {
                        TaxSection(state, actions, events)
                    }

                    SettingsSections.RECEIPTS -> {
                        ReceiptsSection(state, actions, events)
                    }

                    SettingsSections.EMAIL -> {
                        EmailSection(state, actions, events)
                    }

                    SettingsSections.SECURITY -> {
                        SecuritySection(state, actions, events) { settingPin = true }
                    }

                    SettingsSections.DATA -> {
                        DataSection(state, actions, events, navigator)
                    }

                    else -> {
                        AboutSection(state)
                    }
                }
            }
        }
    }
}

/** The title of the settings [section] (one of the [SettingsSections] keys; unknown keys are About). */
@StringRes
private fun sectionTitle(section: String): Int =
    when (section) {
        SettingsSections.TERMINAL -> R.string.settings_terminal
        SettingsSections.SIMULATOR -> R.string.settings_simulator
        SettingsSections.PAYMENTS -> R.string.settings_payments
        SettingsSections.TAX -> R.string.settings_tax
        SettingsSections.RECEIPTS -> R.string.settings_receipts
        SettingsSections.EMAIL -> R.string.settings_email
        SettingsSections.SECURITY -> R.string.settings_security
        SettingsSections.DATA -> R.string.settings_data
        else -> R.string.settings_about
    }

/**
 * Settings › Terminal. On an Adyen terminal only the shared key needs entering: the POIID is detected, the host is
 * localhost and the environment comes from the terminal's certificate. Off-terminal, payments can also go to a terminal
 * on the network (its IP address and POIID), one in the cloud (an API key instead of the shared key) or the Adyen
 * Payments app on this phone (the shared key, then Tap to Pay is set up). The Checkout API (for captures) follows; it is
 * optional except in the cloud, whose API key it shares. The simulator stands in for all of them.
 */
@Composable
private fun ColumnScope.TerminalSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    navigator: Navigator,
) {
    val container = LocalAppContainer.current
    val terminalStatus = container.terminalStatus
    val status by terminalStatus.state.collectAsStateWithLifecycle()
    val terminal = state.settings.terminal
    val mode = status.mode

    fun update(transform: (TerminalSettings) -> TerminalSettings) = events.onUpdate { it.copy(terminal = transform(it.terminal)) }
    if (mode != TerminalMode.SIMULATOR) {
        ConnectionStatus(status.connection, status.poiId, status.environment, status.setupProblem?.takeIf { mode != TerminalMode.TERMINAL })
    }
    TerminalModeChoice(mode, status.onTerminal) { choice ->
        // The device's own default is stored as Automatic, so the app keeps following it. Another destination has its
        // own environment, found again at its first connection.
        val stored = if (choice == terminalStatus.automaticMode) TerminalMode.AUTO else choice
        if (choice != mode) update { it.copy(mode = stored, environment = null, cloudRegion = null) }
    }
    when (mode) {
        TerminalMode.SIMULATOR -> {
            SettingNavRow(
                Icons.Default.Science,
                stringResource(R.string.settings_simulator),
                simulatorOutcomeLabel(state.settings.simulator.outcome),
                { navigator.push(Route.SettingsSection(SettingsSections.SIMULATOR)) },
            )
        }

        TerminalMode.TERMINAL, TerminalMode.PAYMENTS_APP, TerminalMode.AUTO -> {
            if (mode == TerminalMode.TERMINAL && !status.onTerminal) NetworkTerminalFields(terminal, ::update)
            SharedKeySettings(
                terminal = terminal,
                passphraseSaved = Secret.TERMINAL_PASSPHRASE in state.secrets,
                actions = actions,
                update = ::update,
                onSaveAndTest = { passphrase ->
                    if (!actions.connection.running) events.onSaveAndTest(Secret.TERMINAL_PASSPHRASE, passphrase, SettingsTest.CONNECTION)
                },
                onForgetPassphrase = { events.onSecretChange(Secret.TERMINAL_PASSPHRASE, null) },
            )
        }

        // A terminal in the cloud is reached with the API key, below.
        TerminalMode.CLOUD -> {}
    }
}

/**
 * The rest of Settings › Terminal that depends on where payments go: the Checkout API (shared with the cloud), and the
 * terminal in the cloud or the Tap to Pay setup.
 */
@Composable
private fun ColumnScope.TerminalApiSection(
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
) {
    val container = LocalAppContainer.current
    val status by container.terminalStatus.state.collectAsStateWithLifecycle()
    val terminal = state.settings.terminal
    val mode = status.mode

    fun update(transform: (TerminalSettings) -> TerminalSettings) = events.onUpdate { it.copy(terminal = transform(it.terminal)) }
    if (mode != TerminalMode.SIMULATOR) {
        ApiSettings(
            terminal = terminal,
            apiKeySaved = Secret.CHECKOUT_API_KEY in state.secrets,
            problem = status.apiProblem?.text(),
            cloud = mode == TerminalMode.CLOUD,
            actions = actions,
            update = ::update,
            onSaveAndTest = { key -> if (!actions.api.running) events.onSaveAndTest(Secret.CHECKOUT_API_KEY, key, SettingsTest.API) },
            onForgetApiKey = { events.onSecretChange(Secret.CHECKOUT_API_KEY, null) },
        )
    }
    when (mode) {
        TerminalMode.CLOUD -> {
            CloudTerminalSettings(
                poiId = terminal.poiIdOverride,
                setup = setup,
                testing = actions.connection.running,
                onPoiId = { id -> update { it.copy(poiIdOverride = id) } },
                onFindTerminals = setupEvents::onTerminalsFind,
                onChooseTerminal = setupEvents::onTerminalChoose,
                onTestConnection = {
                    if (!actions.connection.running) events.onSaveAndTest(Secret.CHECKOUT_API_KEY, null, SettingsTest.CONNECTION)
                },
            )
        }

        TerminalMode.PAYMENTS_APP -> {
            TapToPaySettings(
                paymentsApps = remember { container.device.paymentsApps },
                installationId = terminal.paymentsAppInstallationId,
                storeId = terminal.storeId,
                apiKeySaved = Secret.PAYMENTS_APP_API_KEY in state.secrets,
                setup = setup,
                onStoreId = { id -> update { it.copy(storeId = id) } },
                onSetUp = setupEvents::onTapToPaySetUp,
                onRemove = setupEvents::onTapToPayRemove,
                onForgetApiKey = { events.onSecretChange(Secret.PAYMENTS_APP_API_KEY, null) },
            )
        }

        TerminalMode.TERMINAL, TerminalMode.SIMULATOR, TerminalMode.AUTO -> {}
    }
}

/** The advanced terminal settings (SaleID and timeout), and the outcome of the last connection test. */
@Composable
private fun ColumnScope.TerminalAdvancedSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    val status by LocalAppContainer.current.terminalStatus.state
        .collectAsStateWithLifecycle()
    val terminal = state.settings.terminal

    fun update(transform: (TerminalSettings) -> TerminalSettings) = events.onUpdate { it.copy(terminal = transform(it.terminal)) }
    AdvancedSettings {
        SettingTextField(stringResource(R.string.settings_sale_id), terminal.saleId, { value -> update { it.copy(saleId = value.trim()) } })
        SettingNumberField(
            stringResource(R.string.settings_timeout),
            terminal.timeoutSeconds,
            TerminalSettings.TIMEOUT_SECONDS,
            { seconds -> update { it.copy(timeoutSeconds = seconds) } },
            supporting = stringResource(R.string.settings_timeout_hint),
        )
    }
    actions.connection.outcome?.let { outcome ->
        ConnectionResultDialog(outcome.text(), actions.connection.isError, status.environment, events::onConnectionResultDismiss)
    }
}

/**
 * "Payments go to": this terminal or the simulator on a terminal; elsewhere a terminal on the network or in the cloud,
 * Tap to Pay with the Adyen Payments app, or the simulator.
 */
@Composable
private fun TerminalModeChoice(
    mode: TerminalMode,
    onTerminal: Boolean,
    onSelect: (TerminalMode) -> Unit,
) {
    SettingChoice(
        title = stringResource(R.string.settings_mode),
        options =
            if (onTerminal) {
                listOf(TerminalMode.TERMINAL to stringResource(R.string.settings_mode_terminal))
            } else {
                listOf(
                    TerminalMode.TERMINAL to stringResource(R.string.settings_mode_network),
                    TerminalMode.CLOUD to stringResource(R.string.settings_mode_cloud),
                    TerminalMode.PAYMENTS_APP to stringResource(R.string.settings_mode_payments_app),
                )
            } + (TerminalMode.SIMULATOR to stringResource(R.string.settings_mode_simulator)),
        selected = mode,
        onSelect = onSelect,
        tag = "terminalMode",
    )
}

/** The IP address and POIID of a terminal on the network; a terminal running the app knows both itself. */
@Composable
private fun ColumnScope.NetworkTerminalFields(
    terminal: TerminalSettings,
    update: ((TerminalSettings) -> TerminalSettings) -> Unit,
) {
    SettingTextField(
        stringResource(R.string.settings_host),
        terminal.host,
        { value -> update { it.copy(host = value.trim()) } },
        placeholder = stringResource(R.string.settings_host_placeholder),
        supporting = stringResource(R.string.settings_host_hint),
        keyboardType = KeyboardType.Uri,
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "host",
    )
    SettingTextField(
        label = stringResource(R.string.settings_poiid),
        value = terminal.poiIdOverride,
        onCommit = { value -> update { it.copy(poiIdOverride = value.trim()) } },
        placeholder = stringResource(R.string.settings_poiid_hint),
        supporting = stringResource(R.string.settings_poiid_help),
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "poiId",
    )
}

/**
 * The shared key: identifier, passphrase (only kept in memory while typed, and cleared once stored) and version, and
 * the button that saves the passphrase typed ([onSaveAndTest] gets it, or an empty one) and tests the connection.
 */
@Composable
private fun ColumnScope.SharedKeySettings(
    terminal: TerminalSettings,
    passphraseSaved: Boolean,
    actions: SettingsActions,
    update: ((TerminalSettings) -> TerminalSettings) -> Unit,
    onSaveAndTest: (passphrase: String) -> Unit,
    onForgetPassphrase: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    LaunchedEffect(actions.passphraseStored) { if (actions.passphraseStored) passphrase = "" }
    SectionHeader(stringResource(R.string.settings_shared_key))
    SettingNote(stringResource(R.string.settings_shared_key_hint))
    // Next moves on to the passphrase, whose Done key saves and tests.
    SettingTextField(
        stringResource(R.string.settings_key_identifier),
        terminal.keyIdentifier,
        { value -> update { it.copy(keyIdentifier = value.trim()) } },
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "keyIdentifier",
    )
    SecretField(
        label = stringResource(R.string.settings_passphrase),
        isSet = passphraseSaved,
        value = passphrase,
        onValueChange = { passphrase = it },
        onSubmit = { onSaveAndTest(passphrase) },
        tag = "passphrase",
    )
    SettingNumberField(stringResource(R.string.settings_key_version), terminal.keyVersion, TerminalSettings.KEY_VERSIONS, { version ->
        update { it.copy(keyVersion = version) }
    }, tag = "keyVersion")
    Column(Modifier.padding(horizontal = 16.dp, vertical = LocalDimens.current.spacing)) {
        if (passphrase.isNotEmpty()) {
            PrimaryButton(
                stringResource(R.string.settings_save_and_test),
                { onSaveAndTest(passphrase) },
                loading = actions.connection.running,
                modifier = Modifier.testTag("testConnection"),
            )
        } else {
            SecondaryButton(
                stringResource(R.string.settings_test_connection),
                { onSaveAndTest("") },
                loading = actions.connection.running,
                modifier = Modifier.testTag("testConnection"),
            )
        }
        if (passphraseSaved) {
            TertiaryButton(
                stringResource(R.string.settings_forget_passphrase),
                onForgetPassphrase,
                destructive = true,
                modifier = Modifier.testTag("forgetPassphrase"),
            )
        }
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
    }
}

/**
 * The Checkout API: merchant account, API key (only kept in memory while typed, and cleared once stored) and, unless
 * the terminal is known to be TEST, the live URL prefix; what is still missing ([problem]), and the button that saves
 * the key typed ([onSaveAndTest] gets it, or an empty one) and tests it, with the outcome of the last test. It is
 * optional, except in the [cloud], where the same API key reaches the terminal.
 */
@Composable
private fun ColumnScope.ApiSettings(
    terminal: TerminalSettings,
    apiKeySaved: Boolean,
    problem: String?,
    cloud: Boolean,
    actions: SettingsActions,
    update: ((TerminalSettings) -> TerminalSettings) -> Unit,
    onSaveAndTest: (apiKey: String) -> Unit,
    onForgetApiKey: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(actions.apiKeyStored) { if (actions.apiKeyStored) apiKey = "" }
    val test = actions.api
    SectionHeader(stringResource(if (cloud) R.string.settings_api_cloud else R.string.settings_api))
    SettingNote(stringResource(if (cloud) R.string.settings_api_cloud_hint else R.string.settings_api_hint))
    SettingTextField(
        stringResource(R.string.settings_merchant_account),
        terminal.merchantAccount,
        { value -> update { it.copy(merchantAccount = value.trim()) } },
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "merchantAccount",
    )
    SecretField(
        label = stringResource(R.string.settings_api_key),
        isSet = apiKeySaved,
        value = apiKey,
        onValueChange = { apiKey = it },
        onSubmit = { onSaveAndTest(apiKey) },
        tag = "apiKey",
    )
    if (terminal.environment != TerminalEnvironment.TEST) {
        SettingTextField(
            stringResource(R.string.settings_live_prefix),
            terminal.liveUrlPrefix,
            { value -> update { it.copy(liveUrlPrefix = value.trim()) } },
            supporting = stringResource(R.string.settings_live_prefix_hint),
            autoCorrect = false,
            tag = "livePrefix",
        )
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = LocalDimens.current.spacing)) {
        problem?.let { ActionMessage(it, isError = true) }
        if (apiKey.isNotBlank()) {
            PrimaryButton(
                stringResource(R.string.settings_save_and_test_api),
                { onSaveAndTest(apiKey) },
                loading = test.running,
                modifier = Modifier.testTag("testApi"),
            )
        } else {
            SecondaryButton(
                stringResource(R.string.settings_test_api),
                { onSaveAndTest("") },
                loading = test.running,
                modifier = Modifier.testTag("testApi"),
            )
        }
        if (apiKeySaved) {
            TertiaryButton(
                stringResource(R.string.settings_forget_api_key),
                onForgetApiKey,
                destructive = true,
                modifier = Modifier.testTag("forgetApiKey"),
            )
        }
        OutcomeMessage(test, Modifier.testTag("apiResult"))
    }
}

@Composable
@ReadOnlyComposable
internal fun connectionTitle(connection: TerminalConnection): String =
    stringResource(
        when (connection) {
            TerminalConnection.Unknown, TerminalConnection.Checking -> R.string.settings_status_checking
            is TerminalConnection.Connected -> R.string.settings_status_connected
            is TerminalConnection.NotSetUp -> R.string.settings_status_not_set_up
            is TerminalConnection.Failed -> R.string.settings_status_failed
        },
    )

@Composable
@ReadOnlyComposable
private fun environmentLabel(environment: TerminalEnvironment?): String? =
    when (environment) {
        TerminalEnvironment.TEST -> stringResource(R.string.settings_env_test)
        TerminalEnvironment.LIVE -> stringResource(R.string.settings_env_live)
        null -> null
    }

/**
 * Where the connection to the terminal stands, kept up to date by the background check. While it is not set up,
 * [problem] says what is missing; without one, the hint points to the shared key.
 */
@Composable
private fun ConnectionStatus(
    connection: TerminalConnection,
    poiId: String?,
    environment: TerminalEnvironment?,
    problem: SetupProblem?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    val (icon, tint) =
        when (connection) {
            is TerminalConnection.Connected -> Icons.Default.CheckCircle to colors.success
            is TerminalConnection.NotSetUp -> Icons.Default.Info to colors.warning
            is TerminalConnection.Failed -> Icons.Default.Error to colors.error
            TerminalConnection.Unknown, TerminalConnection.Checking -> Icons.Default.Sync to MaterialTheme.colorScheme.onSurfaceVariant
        }
    val detail =
        when (connection) {
            is TerminalConnection.Connected -> listOfNotNull(poiId, environmentLabel(environment)).joinToString(" · ")
            is TerminalConnection.NotSetUp -> problem?.text() ?: stringResource(R.string.settings_status_not_set_up_hint)
            is TerminalConnection.Failed -> connection.message ?: stringResource(R.string.setup_no_response)
            TerminalConnection.Unknown, TerminalConnection.Checking -> poiId.orEmpty()
        }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding)
                .testTag("connectionStatus"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(connectionTitle(connection), style = MaterialTheme.typography.bodyLarge)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/** The outcome of a connection test, in a dialog so it is seen however far the screen is scrolled. */
@Composable
private fun ConnectionResultDialog(
    message: String,
    isError: Boolean,
    environment: TerminalEnvironment?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            StatusBadge(if (isError) StatusKind.ERROR else StatusKind.SUCCESS, size = 40.dp)
        },
        title = {
            Text(stringResource(if (isError) R.string.settings_connection_result_failed else R.string.settings_connection_ok))
        },
        text = {
            Text(
                listOfNotNull(message, environmentLabel(environment).takeIf { !isError }).joinToString("\n"),
                modifier = Modifier.testTag("connectionResult"),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("connectionResultOk")) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun SimulatorSection(
    state: SettingsUiState,
    events: SettingsEvents,
) {
    val simulator = state.settings.simulator

    fun update(transform: (SimulatorSettings) -> SimulatorSettings) = events.onUpdate { it.copy(simulator = transform(it.simulator)) }
    SettingNote(stringResource(R.string.settings_simulator_hint))
    SettingChoice(
        title = stringResource(R.string.settings_sim_outcome),
        options = SimulatedOutcome.entries.map { it to simulatorOutcomeLabel(it) },
        selected = simulator.outcome,
        onSelect = { outcome -> update { it.copy(outcome = outcome) } },
        tag = "simOutcome",
    )
    SettingNumberField(
        stringResource(
            R.string.settings_sim_delay,
        ),
        simulator.delayMillis.toInt(),
        SimulatorSettings.DELAY_MILLIS,
        { delay ->
            update { it.copy(delayMillis = delay.toLong()) }
        },
    )
    SettingSwitch(stringResource(R.string.settings_sim_printer), simulator.hasPrinter, { value -> update { it.copy(hasPrinter = value) } })
    SettingSwitch(stringResource(R.string.settings_sim_signature), simulator.signatureRequired, { value ->
        update { it.copy(signatureRequired = value) }
    })
}

@Composable
@ReadOnlyComposable
private fun simulatorOutcomeLabel(outcome: SimulatedOutcome): String =
    stringResource(
        when (outcome) {
            SimulatedOutcome.APPROVE -> R.string.settings_sim_approve
            SimulatedOutcome.DECLINE -> R.string.settings_sim_decline
            SimulatedOutcome.CANCEL -> R.string.settings_sim_cancel
            SimulatedOutcome.TIMEOUT -> R.string.settings_sim_timeout
            SimulatedOutcome.BUSY -> R.string.settings_sim_busy
            SimulatedOutcome.RANDOM -> R.string.settings_sim_random
        },
    )

@Composable
private fun ColumnScope.PaymentsSection(
    state: SettingsUiState,
    events: SettingsEvents,
) {
    val payment = state.settings.payment

    fun update(transform: (PaymentSettings) -> PaymentSettings) = events.onUpdate { it.copy(payment = transform(it.payment)) }
    SectionHeader(stringResource(R.string.settings_pricing))
    CurrencySetting(
        selectedCode = payment.currencyCode,
        automatic = PaymentSettings().resolvedCurrency(currentLocale().country),
        onSelect = { code -> update { it.copy(currencyCode = code) } },
    )
    SectionHeader(stringResource(R.string.settings_references))
    SettingTextField(
        stringResource(R.string.settings_reference_prefix),
        payment.referencePrefix,
        { value -> update { it.copy(referencePrefix = value.trim()) } },
        supporting = stringResource(R.string.settings_reference_prefix_hint),
        autoCorrect = false,
    )
    SettingSwitch(stringResource(R.string.settings_ask_transaction_reference), payment.askTransactionReference, { value ->
        update { it.copy(askTransactionReference = value) }
    })
    SectionHeader(stringResource(R.string.settings_tokenization))
    TokenizationSettings(payment, ::update)
    SectionHeader(stringResource(R.string.settings_tipping))
    SettingSwitch(
        stringResource(R.string.settings_tip_default),
        payment.tipOnReceiptDefaultOn,
        { value -> update { it.copy(tipOnReceiptDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_tip_default_hint),
        tag = "tipDefault",
    )
    SectionHeader(stringResource(R.string.settings_email_receipts))
    EmailCaptureSettings(payment, ::update)
    SectionHeader(stringResource(R.string.settings_payment_links))
    PaymentLinkSettings(payment, ::update)
}

/**
 * Offering payment links at checkout and how long they work, with what is missing while the Checkout API is not set up
 * (links are never simulated).
 */
@Composable
private fun ColumnScope.PaymentLinkSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val status by LocalAppContainer.current.terminalStatus.state
        .collectAsStateWithLifecycle()
    SettingSwitch(
        stringResource(R.string.settings_links_enabled),
        payment.paymentLinks,
        { value -> update { it.copy(paymentLinks = value) } },
        subtitle = stringResource(R.string.settings_links_hint),
        tag = "paymentLinks",
    )
    if (!payment.paymentLinks) return
    SettingNumberField(
        stringResource(R.string.settings_link_expiry),
        payment.linkExpiryHours,
        PaymentSettings.LINK_EXPIRY_HOURS,
        { hours -> update { it.copy(linkExpiryHours = hours) } },
        supporting = stringResource(R.string.settings_link_expiry_hint),
        tag = "linkExpiry",
    )
    if (status.loaded && !status.paymentLinks) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = LocalDimens.current.spacing)) {
            ActionMessage(stringResource(R.string.settings_links_need_api), isError = true, modifier = Modifier.testTag("linksNeedApi"))
        }
    }
}

/** Saving cards: the defaults, the recurring model, and what the shopper reference is made from. */
@Composable
private fun ColumnScope.TokenizationSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SaveCardDefaults(payment, update)
    SettingChoice(
        title = stringResource(R.string.settings_recurring_model),
        options =
            PaymentSettings.RECURRING_MODELS.map {
                it to
                    stringResource(
                        when (it) {
                            "CardOnFile" -> R.string.recurring_card_on_file
                            "Subscription" -> R.string.recurring_subscription
                            else -> R.string.recurring_unscheduled
                        },
                    )
            },
        selected = payment.recurringProcessingModel,
        onSelect = { model -> update { it.copy(recurringProcessingModel = model) } },
    )
    // The source also decides whether checkout asks for a customer reference, which has no switch of its own.
    val emailIsReference = payment.shopperReferenceSource == ShopperReferenceSource.EMAIL
    SettingChoice(
        title = stringResource(R.string.settings_shopper_reference_source),
        options =
            listOf(
                ShopperReferenceSource.CUSTOMER_REFERENCE to stringResource(R.string.settings_source_customer),
                ShopperReferenceSource.EMAIL to stringResource(R.string.settings_source_email),
            ),
        selected = payment.shopperReferenceSource,
        onSelect = { source -> update { it.copy(shopperReferenceSource = source) } },
        subtitle =
            stringResource(
                if (emailIsReference) R.string.settings_source_email_hint else R.string.settings_source_customer_hint,
            ),
        tag = "referenceSource",
    )
    if (emailIsReference) {
        SettingChoice(
            title = stringResource(R.string.settings_email_reference_mode),
            options =
                listOf(
                    EmailReferenceMode.HASHED to stringResource(R.string.settings_email_hashed),
                    EmailReferenceMode.RAW to stringResource(R.string.settings_email_raw),
                ),
            selected = payment.emailReferenceMode,
            onSelect = { mode -> update { it.copy(emailReferenceMode = mode) } },
            subtitle = stringResource(R.string.settings_email_reference_hint),
        )
        if (payment.emailReferenceMode == EmailReferenceMode.HASHED) {
            SettingTextField(
                stringResource(R.string.settings_email_salt),
                payment.emailReferenceSalt,
                { value -> update { it.copy(emailReferenceSalt = value) } },
                supporting = stringResource(R.string.settings_email_salt_hint),
            )
        }
    }
    SettingSwitch(stringResource(R.string.settings_send_shopper_email), payment.sendShopperEmail, { value ->
        update { it.copy(sendShopperEmail = value) }
    })
}

/** Whether "Save card" starts switched on at checkout, for sales and for pre-authorisations. */
@Composable
private fun ColumnScope.SaveCardDefaults(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.settings_tokenize_default),
        payment.tokenizeDefaultOn,
        { value -> update { it.copy(tokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_tokenize_default_hint),
        tag = "tokenizeDefault",
    )
    SettingSwitch(
        stringResource(R.string.settings_pre_auth_tokenize_default),
        payment.preAuthTokenizeDefaultOn,
        { value -> update { it.copy(preAuthTokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_pre_auth_tokenize_default_hint),
        tag = "preAuthTokenizeDefault",
    )
}

/** When checkout asks for the email, and sending the receipt automatically. */
@Composable
private fun ColumnScope.EmailCaptureSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val emailIsReference = payment.shopperReferenceSource == ShopperReferenceSource.EMAIL
    // With the email as shopper reference it is needed when the payment starts, so only "before" choices are offered.
    SettingChoice(
        title = stringResource(R.string.settings_email_capture),
        options =
            listOfNotNull(
                (EmailCapture.OFF to stringResource(R.string.settings_capture_off)).takeUnless { emailIsReference },
                EmailCapture.BEFORE_PAYMENT to stringResource(R.string.settings_capture_before),
                (EmailCapture.AFTER_PAYMENT to stringResource(R.string.settings_capture_after)).takeUnless { emailIsReference },
                EmailCapture.BOTH to stringResource(R.string.settings_capture_both),
            ),
        selected = payment.effectiveEmailCapture,
        onSelect = { capture -> update { it.copy(emailCapture = capture) } },
        subtitle = stringResource(R.string.settings_email_capture_reference).takeIf { emailIsReference },
        tag = "emailCapture",
    )
    if (payment.captureEmailBefore) {
        SettingSwitch(stringResource(R.string.settings_auto_send), payment.autoSendEmail, { value ->
            update { it.copy(autoSendEmail = value) }
        })
    }
}

@Composable
private fun ColumnScope.ReceiptsSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    val receipt = state.settings.receipt

    fun update(transform: (ReceiptSettings) -> ReceiptSettings) = events.onUpdate { it.copy(receipt = transform(it.receipt)) }
    ReceiptTextSettings(receipt, ::update)
    SectionHeader(stringResource(R.string.settings_content))
    SettingSwitch(stringResource(R.string.settings_show_tax), receipt.showTaxBreakdown, { value ->
        update { it.copy(showTaxBreakdown = value) }
    })
    SettingSwitch(stringResource(R.string.settings_show_references), receipt.showReferences, { value ->
        update { it.copy(showReferences = value) }
    })
    SettingSwitch(
        stringResource(R.string.settings_show_qr),
        receipt.showRefundQr,
        { value -> update { it.copy(showRefundQr = value) } },
        subtitle = stringResource(R.string.settings_show_qr_hint),
    )
    ReceiptPrintingSettings(receipt, ::update)
    SectionHeader(stringResource(R.string.settings_preview))
    state.sampleReceipt?.let { document ->
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ReceiptPreview(document) }
    }
    Box(Modifier.padding(horizontal = 16.dp)) {
        Column {
            SecondaryButton(stringResource(R.string.settings_print_test), events::onTestPrint, loading = actions.print.running)
            OutcomeMessage(actions.print)
        }
    }
}

/** The business details, title and footer printed on every receipt. */
@Composable
private fun ColumnScope.ReceiptTextSettings(
    receipt: ReceiptSettings,
    update: ((ReceiptSettings) -> ReceiptSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_header_footer))
    SettingTextField(stringResource(R.string.settings_business_name), receipt.businessName, { value ->
        update { it.copy(businessName = value) }
    }, tag = "businessName")
    SettingTextField(stringResource(R.string.settings_address), receipt.addressLines, { value ->
        update { it.copy(addressLines = value) }
    }, singleLine = false)
    SettingTextField(stringResource(R.string.settings_tax_id_label), receipt.taxIdLabel, { value ->
        update { it.copy(taxIdLabel = value) }
    })
    SettingTextField(stringResource(R.string.settings_tax_id), receipt.taxId, { value -> update { it.copy(taxId = value) } })
    SettingTextField(stringResource(R.string.settings_phone), receipt.phone, { value ->
        update { it.copy(phone = value) }
    }, keyboardType = KeyboardType.Phone)
    SettingTextField(stringResource(R.string.settings_receipt_title), receipt.title, { value -> update { it.copy(title = value) } })
    SettingTextField(stringResource(R.string.settings_footer), receipt.footer, { value ->
        update { it.copy(footer = value) }
    }, singleLine = false)
}

/** Whether and when receipts print, the merchant copy, and the line width. */
@Composable
private fun ColumnScope.ReceiptPrintingSettings(
    receipt: ReceiptSettings,
    update: ((ReceiptSettings) -> ReceiptSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_printing))
    SettingChoice(
        title = stringResource(R.string.settings_printer),
        options =
            listOf(
                PrinterMode.AUTO to stringResource(R.string.settings_printer_auto),
                PrinterMode.ON to stringResource(R.string.settings_printer_on),
                PrinterMode.OFF to stringResource(R.string.settings_printer_off),
            ),
        selected = receipt.printerMode,
        onSelect = { mode -> update { it.copy(printerMode = mode) } },
    )
    SettingSwitch(stringResource(R.string.settings_auto_print), receipt.autoPrint, { value ->
        update { it.copy(autoPrint = value) }
    }, tag = "autoPrint")
    SettingChoice(
        title = stringResource(R.string.settings_merchant_copy),
        options =
            listOf(
                MerchantCopyPolicy.NEVER to stringResource(R.string.settings_merchant_never),
                MerchantCopyPolicy.SIGNATURE_ONLY to stringResource(R.string.settings_merchant_signature),
                MerchantCopyPolicy.ALWAYS to stringResource(R.string.settings_merchant_always),
            ),
        selected = receipt.merchantCopy,
        onSelect = { policy -> update { it.copy(merchantCopy = policy) } },
    )
    SettingNumberField(
        stringResource(R.string.settings_chars_per_line),
        receipt.charsPerLine,
        ReceiptSettings.CHARS_PER_LINE,
        { chars -> update { it.copy(charsPerLine = chars) } },
        supporting = stringResource(R.string.settings_chars_per_line_hint),
    )
}

@Composable
private fun ColumnScope.EmailSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    val email = state.settings.email
    var askRecipient by remember { mutableStateOf(false) }

    fun update(transform: (EmailSettings) -> EmailSettings) = events.onUpdate { it.copy(email = transform(it.email)) }
    SmtpServerSettings(
        email = email,
        passwordSaved = Secret.SMTP_PASSWORD in state.secrets,
        secretError = actions.secretError?.text(),
        onPassword = { events.onSecretChange(Secret.SMTP_PASSWORD, it) },
        update = ::update,
    )
    SectionHeader(stringResource(R.string.settings_message))
    SettingTextField(
        stringResource(R.string.settings_from_address),
        email.fromAddress,
        { value -> update { it.copy(fromAddress = value.trim()) } },
        isError = { it.isNotBlank() && !ShopperReferences.isValidEmail(it) },
        keyboardType = KeyboardType.Email,
        tag = "fromAddress",
    )
    SettingTextField(stringResource(R.string.settings_from_name), email.fromName, { value -> update { it.copy(fromName = value) } })
    SettingTextField(
        stringResource(R.string.settings_bcc),
        email.bcc,
        { value -> update { it.copy(bcc = value.trim()) } },
        isError = { it.isNotBlank() && !ShopperReferences.isValidEmail(it) },
        keyboardType = KeyboardType.Email,
    )
    SettingTextField(stringResource(R.string.settings_subject), email.subject, { value ->
        update { it.copy(subject = value) }
    }, supporting = stringResource(R.string.settings_subject_hint))
    Box(Modifier.padding(16.dp)) {
        Column {
            SecondaryButton(stringResource(R.string.settings_send_test), {
                askRecipient = true
            }, enabled = email.isConfigured, loading = actions.email.running)
            OutcomeMessage(actions.email)
        }
    }
    if (askRecipient) {
        TextInputDialog(
            title = stringResource(R.string.settings_send_test),
            label = stringResource(R.string.checkout_email),
            confirmLabel = stringResource(R.string.action_send),
            keyboardType = KeyboardType.Email,
            validate = ShopperReferences::isValidEmail,
            onConfirm = {
                askRecipient = false
                events.onTestEmailSend(it)
            },
            onDismiss = { askRecipient = false },
        )
    }
}

/** The SMTP server, login and password ([onPassword] with null forgets it). */
@Composable
private fun ColumnScope.SmtpServerSettings(
    email: EmailSettings,
    passwordSaved: Boolean,
    secretError: String?,
    onPassword: (String?) -> Unit,
    update: ((EmailSettings) -> EmailSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_smtp))
    SettingTextField(stringResource(R.string.settings_smtp_host), email.host, { value ->
        update { it.copy(host = value.trim()) }
    }, tag = "smtpHost")
    SettingNumberField(stringResource(R.string.settings_port), email.port, EmailSettings.PORTS, { port -> update { it.copy(port = port) } })
    SettingChoice(
        title = stringResource(R.string.settings_smtp_security),
        options =
            listOf(
                SmtpSecurity.STARTTLS to stringResource(R.string.settings_smtp_starttls),
                SmtpSecurity.SSL to stringResource(R.string.settings_smtp_ssl),
                SmtpSecurity.NONE to stringResource(R.string.settings_smtp_none),
            ),
        selected = email.security,
        onSelect = { security -> update { it.copy(security = security) } },
    )
    SettingTextField(stringResource(R.string.settings_smtp_username), email.username, { value ->
        update { it.copy(username = value.trim()) }
    })
    SettingSecret(stringResource(R.string.settings_smtp_password), passwordSaved, onPassword, { onPassword(null) })
    secretError?.let { ActionMessage(it, isError = true, modifier = Modifier.padding(horizontal = 16.dp)) }
}

@Composable
private fun SecuritySection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    onSetPin: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    SettingNote(stringResource(R.string.settings_pin_hint))
    Box(Modifier.padding(16.dp)) {
        Column {
            SecondaryButton(
                stringResource(if (state.pinSet) R.string.settings_change_pin else R.string.settings_set_pin),
                onSetPin,
                modifier = Modifier.testTag("setPin"),
            )
            if (state.pinSet) {
                SecondaryButton(stringResource(R.string.settings_remove_pin), {
                    confirmRemove = true
                }, modifier = Modifier.padding(top = 8.dp))
            }
            actions.secretError?.let { ActionMessage(it.text(), isError = true) }
        }
    }
    SettingChoice(
        title = stringResource(R.string.settings_auto_lock),
        options =
            listOf(1, 2, 5, 10, 0).map {
                it to
                    if (it == 0) {
                        stringResource(R.string.settings_auto_lock_never)
                    } else {
                        pluralStringResource(R.plurals.settings_minutes, it, it)
                    }
            },
        selected = state.settings.security.autoLockMinutes,
        onSelect = { minutes -> events.onUpdate { it.copy(security = it.security.copy(autoLockMinutes = minutes)) } },
    )
    SettingNote(stringResource(R.string.settings_pin_forgotten))
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.settings_remove_pin),
            message = stringResource(R.string.settings_remove_pin_message),
            confirmLabel = stringResource(R.string.settings_remove_pin),
            destructive = true,
            onConfirm = {
                confirmRemove = false
                events.onPinClear()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun DataSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    navigator: Navigator,
) {
    var confirmClear by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.settings_history))
    SettingChoice(
        title = stringResource(R.string.settings_retention),
        options =
            listOf(30, 90, 180, 365, 0).map { days ->
                days to
                    if (days == 0) {
                        stringResource(R.string.settings_retention_forever)
                    } else {
                        pluralStringResource(R.plurals.settings_days, days, days)
                    }
            },
        selected = state.settings.history.retentionDays,
        onSelect = { days -> events.onUpdate { it.copy(history = it.history.copy(retentionDays = days)) } },
    )
    Box(Modifier.padding(16.dp)) {
        Column {
            SecondaryButton(stringResource(R.string.settings_clear_history), { confirmClear = true })
            if (actions.cleared) ActionMessage(stringResource(R.string.settings_history_cleared), isError = false)
        }
    }
    SectionHeader(stringResource(R.string.settings_catalogue))
    SettingNavRow(
        Icons.Default.FileUpload,
        stringResource(R.string.transfer_export),
        stringResource(R.string.settings_export_hint),
        { navigator.push(Route.TransferExport) },
        tag = "shareToTerminal",
    )
    SettingNavRow(
        Icons.Default.FileDownload,
        stringResource(R.string.transfer_import),
        stringResource(R.string.settings_import_hint),
        { navigator.push(Route.TransferImport) },
        tag = "setUpFromTerminal",
    )
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_history),
            message = stringResource(R.string.settings_clear_history_message),
            confirmLabel = stringResource(R.string.settings_clear_history),
            destructive = true,
            onConfirm = {
                confirmClear = false
                events.onHistoryClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun AboutSection(state: SettingsUiState) {
    val container = LocalAppContainer.current
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    val mode =
        when {
            terminal.mode == TerminalMode.SIMULATOR -> R.string.settings_mode_simulator
            terminal.mode == TerminalMode.CLOUD -> R.string.settings_mode_cloud
            terminal.mode == TerminalMode.PAYMENTS_APP -> R.string.settings_mode_payments_app
            terminal.onTerminal -> R.string.settings_mode_terminal
            else -> R.string.settings_mode_network
        }
    Column(Modifier.padding(16.dp)) {
        LabeledValue(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        LabeledValue(stringResource(R.string.settings_mode), stringResource(mode))
        LabeledValue(stringResource(R.string.settings_poiid), terminal.poiId)
        LabeledValue(stringResource(R.string.settings_environment), terminal.environment?.name)
        LabeledValue(
            stringResource(R.string.about_printer),
            stringResource(if (terminal.printerAvailable) R.string.about_printer_yes else R.string.about_printer_no),
        )
        LabeledValue(stringResource(R.string.about_model), container.device.model)
        LabeledValue(stringResource(R.string.about_android), container.device.osVersion)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.about_app_info), style = MaterialTheme.typography.titleSmall)
        val info = remember { container.application.summary() }
        LabeledValue(stringResource(R.string.about_app_info_application), info.application)
        LabeledValue(stringResource(R.string.about_app_info_platform), info.platform)
        LabeledValue(stringResource(R.string.about_app_info_integrator), info.integrator)
        LabeledValue(stringResource(R.string.about_app_info_device), info.device)
        LabeledValue(stringResource(R.string.about_app_info_library), info.library)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.about_text))
    }
}

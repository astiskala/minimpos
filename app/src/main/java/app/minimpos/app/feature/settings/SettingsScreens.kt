package app.minimpos.app.feature.settings

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.BuildConfig
import app.minimpos.app.R
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.PricingChange
import app.minimpos.app.data.settings.SimulatorSettings
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.lock.SetPinScreen
import app.minimpos.app.feature.text
import app.minimpos.app.feature.walletCheckFailed
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.terminal.SharedKeyOffer
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.HistorySwitchDialog
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.StatusBadge
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.money.AdyenCurrencies
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.label
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.map

@Composable
private fun settingsViewModel(): SettingsViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        SettingsViewModel(
            pricingChanges = container.pricingChanges,
            secrets = container.secrets,
            pins = container.pinManager,
            sessionLock = container.sessionLock,
            checks = SettingsChecks(container.terminalStatus, container.receipts, container.api, container.receiptBusinessDetails),
            history = container.history,
            catalog = container.catalog,
            sampleReceipt = container::sampleReceipt,
            managerPins = container.managerPin,
            historySwitches = container.historySwitches,
        )
    }
}

@Composable
private fun terminalSetupViewModel(settings: SettingsViewModel): TerminalSetupViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        TerminalSetupViewModel(
            container.secrets,
            container.terminalStatus,
            container.tapToPay,
            container.setupDiscovery,
            container.settings,
            settings.state.map { it.settings.terminal },
        )
    }
}

@Composable
private fun capabilitiesViewModel(): CapabilitiesViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        CapabilitiesViewModel(
            container.capabilityDiscovery,
            container.settings.settings.map { it.terminal },
        )
    }
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

    /** Store selection and receipt-business confirmation callbacks. */
    val businessImport: ReceiptBusinessEvents

    /** Prints the sample receipt ([SettingsViewModel.printTest]). */
    fun onTestPrint()

    /** Sends a test email [to] an address ([SettingsViewModel.sendTestEmail]). */
    fun onTestEmailSend(to: String)

    /** Removes the admin PIN ([SettingsViewModel.clearPin]). */
    fun onPinClear()

    /** Deletes every sale and refund ([SettingsViewModel.clearHistory]). */
    fun onHistoryClear()

    /** Tracked sample creation and scoped removal callbacks. */
    val samples: SampleDataEvents

    /** Adds or updates [taxRate], making it the default when [makeDefault] ([SettingsViewModel.saveTaxRate]). */
    fun onTaxRateSave(
        taxRate: TaxRateEntity,
        makeDefault: Boolean,
    )

    /** Deletes [taxRate] unless products still use it ([SettingsViewModel.deleteTaxRate]). */
    fun onTaxRateDelete(taxRate: TaxRateEntity)
}

/** Callbacks for the receipt-business lookup and reviewed import. */
internal interface ReceiptBusinessEvents {
    /** Loads stores without changing saved receipt details. */
    fun onFind()

    /** Confirms a currently offered proposal, or dismisses the selection for null. */
    fun onChoose(business: ReceiptBusiness?)
}

/** What Settings › Terminal asks [TerminalSetupViewModel] to do, as [SettingsEvents] does for [SettingsViewModel]. */
internal interface TerminalSetupEvents {
    /** Tracks whether [secret] has unsaved visible text, without passing or storing its value. */
    fun onSecretDraft(
        secret: Secret,
        present: Boolean,
    )

    /** Lists the terminals connected in the cloud, saving [apiKey] first ([TerminalSetupViewModel.findTerminals]). */
    fun onTerminalsFind(apiKey: String)

    /** Takes [poiId] as the terminal in the cloud; null only closes the list ([TerminalSetupViewModel.chooseTerminal]). */
    fun onTerminalChoose(poiId: String?)

    /** Confirms the displayed non-secret key offer; stale context never grants permission to write. */
    fun onSharedKeyConfirm(offer: SharedKeyOffer)

    /** Reads key settings and retries connection verification without granting permission to PATCH. */
    fun onSharedKeyCheck()

    /** Sets up Tap to Pay, saving [apiKey] first ([TerminalSetupViewModel.setUpTapToPay]). */
    fun onTapToPaySetUp(
        apiKey: String,
        again: Boolean,
    )

    /** Removes this phone's Payments app instance ([TerminalSetupViewModel.removeTapToPay]). */
    fun onTapToPayRemove()
}

@Composable
private fun PricingConfirmation(
    change: PricingChange,
    confirm: () -> Unit,
    cancel: () -> Unit,
) {
    val to = CurrencySpec.of(change.toCurrency)
    val examples =
        change.prices.values
            .take(10)
            .joinToString("\n", "\n") { to.toMajor(it).toPlainString() + " " + to.code }
    ConfirmDialog(
        title = stringResource(R.string.pricing_change_title),
        message = stringResource(R.string.pricing_change_message, change.fromCurrency, change.toCurrency) + examples,
        confirmLabel = stringResource(R.string.action_ok),
        onConfirm = confirm,
        onDismiss = cancel,
    )
}

private fun settingsEvents(
    settings: SettingsViewModel,
    sampleData: SampleDataViewModel,
): SettingsEvents =
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

        override val businessImport =
            object : ReceiptBusinessEvents {
                override fun onFind() = settings.businessImport.find()

                override fun onChoose(business: ReceiptBusiness?) = settings.businessImport.choose(business)
            }

        override fun onTestPrint() = settings.printTest()

        override fun onTestEmailSend(to: String) = settings.sendTestEmail(to)

        override fun onPinClear() = settings.clearPin()

        override fun onHistoryClear() = settings.clearHistory()

        override val samples =
            object : SampleDataEvents {
                override fun onAdd() = sampleData.populate()

                override fun onPurge() = sampleData.purge()
            }

        override fun onTaxRateSave(
            taxRate: TaxRateEntity,
            makeDefault: Boolean,
        ) = settings.saveTaxRate(taxRate, makeDefault)

        override fun onTaxRateDelete(taxRate: TaxRateEntity) = settings.deleteTaxRate(taxRate)
    }

private fun terminalSetupEvents(setup: TerminalSetupViewModel): TerminalSetupEvents =
    object : TerminalSetupEvents {
        override fun onSecretDraft(
            secret: Secret,
            present: Boolean,
        ) = setup.secretDraft(secret, present)

        override fun onTerminalsFind(apiKey: String) = setup.findTerminals(apiKey)

        override fun onTerminalChoose(poiId: String?) = setup.chooseTerminal(poiId)

        override fun onSharedKeyConfirm(offer: SharedKeyOffer) = setup.createSharedKey(offer)

        override fun onSharedKeyCheck() = setup.checkSharedKey()

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
                SectionHeader(stringResource(R.string.settings_connection_group))
                ConnectionSectionRows(
                    terminalSummary =
                        if (mode == TerminalMode.SIMULATOR) {
                            stringResource(R.string.settings_mode_simulator)
                        } else {
                            connectionTitle(terminal.connection)
                        },
                    simulatorOutcome = settings.simulator.outcome.takeIf { mode == TerminalMode.SIMULATOR },
                    onOpen = ::open,
                )
                SectionHeader(stringResource(R.string.checkout_title))
                PaymentSectionRows(
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
                SettingChoice(
                    title = stringResource(R.string.settings_language),
                    options = AppSettings.LANGUAGE_TAGS.zip(stringArrayResource(R.array.app_language_names).toList()),
                    selected = settings.languageTag,
                    onSelect = { language -> vm.update { it.copy(languageTag = language) } },
                    subtitle = stringResource(R.string.settings_language_hint),
                    tag = "appLanguage",
                    navigationIcon = Icons.Default.Language,
                )
                SettingNavRow(Icons.Default.Info, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME, {
                    open(SettingsSections.ABOUT)
                }, tag = "section_about")
            }
        }
    }
}

/** Destination setup and Adyen-side configuration; simulator controls appear only while payments go to it. */
@Composable
private fun ColumnScope.ConnectionSectionRows(
    terminalSummary: String,
    simulatorOutcome: SimulatedOutcome?,
    onOpen: (section: String) -> Unit,
) {
    SettingNavRow(
        Icons.Default.PhonelinkSetup,
        stringResource(R.string.settings_terminal),
        terminalSummary,
        { onOpen(SettingsSections.TERMINAL) },
        tag = "section_terminal",
    )
    SettingNavRow(
        Icons.Default.Sync,
        stringResource(R.string.settings_capabilities),
        stringResource(R.string.settings_capabilities_summary),
        { onOpen(SettingsSections.CAPABILITIES) },
        tag = "section_capabilities",
    )
    simulatorOutcome?.let { outcome ->
        SettingNavRow(Icons.Default.Science, stringResource(R.string.settings_simulator), simulatorOutcomeLabel(outcome), {
            onOpen(SettingsSections.SIMULATOR)
        })
    }
}

/** App-side checkout preferences, separate from configuring capabilities at Adyen. */
@Composable
private fun ColumnScope.PaymentSectionRows(
    currency: String,
    taxSummary: String?,
    onOpen: (section: String) -> Unit,
) {
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
        taxSummary ?: stringResource(R.string.settings_tax_summary),
        { onOpen(SettingsSections.TAX) },
        tag = "section_tax",
    )
    SettingNavRow(
        Icons.Default.Favorite,
        stringResource(R.string.settings_tips_donations),
        stringResource(R.string.settings_tipping_summary),
        { onOpen(SettingsSections.TIPPING) },
        tag = "section_tipping",
    )
    SettingNavRow(
        Icons.Default.Tv,
        stringResource(R.string.settings_shopper_display),
        stringResource(R.string.settings_shopper_display_summary),
        { onOpen(SettingsSections.SHOPPER_DISPLAY) },
        tag = "section_shopper_display",
    )
}

/** Receipt delivery followed by device-local access and data management. */
@Composable
private fun ColumnScope.OtherSectionRows(
    settings: AppSettings,
    pinSet: Boolean,
    onOpen: (section: String) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_receipts))
    SettingNavRow(
        Icons.Default.Receipt,
        stringResource(R.string.settings_receipts),
        settings.receipt.businessName.ifBlank { stringResource(R.string.settings_receipts_summary) },
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
    SectionHeader(stringResource(R.string.settings_device_group))
    SettingNavRow(
        Icons.Default.Lock,
        stringResource(R.string.settings_security),
        stringResource(if (pinSet) R.string.settings_pin_on else R.string.settings_pin_off),
        { onOpen(SettingsSections.SECURITY) },
        tag = "section_security",
    )
    SettingNavRow(
        Icons.Default.Storage,
        stringResource(R.string.settings_data),
        stringResource(R.string.settings_data_summary),
        { onOpen(SettingsSections.DATA) },
        tag = "section_data",
    )
}

/** One settings [section] (a [SettingsSections] key; unknown keys show About). */
@Composable
fun SettingsSectionScreen(
    section: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val vm = settingsViewModel()
    val sampleData = sampleDataViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val setup = terminalSetupViewModel(vm)
    val setupActions by setup.actions.collectAsStateWithLifecycle()
    val pricing by vm.pricing.pending.collectAsStateWithLifecycle()
    pricing?.let { PricingConfirmation(it, vm.pricing::confirm, vm.pricing::cancel) }
    val historySwitch by vm.environment.pending.collectAsStateWithLifecycle()
    historySwitch?.let {
        HistorySwitchDialog(it.unfinished, { vm.environment.review(true) }, { vm.environment.review(false) })
    }
    val events = remember(vm, sampleData) { settingsEvents(vm, sampleData) }
    val setupEvents = remember(setup) { terminalSetupEvents(setup) }
    var settingPin by remember { mutableStateOf<Boolean?>(null) }
    if (settingPin != null) {
        SetPinScreen(onDone = {
            vm.setPin(it, manager = settingPin == true)
            settingPin = null
        }, onCancel = { settingPin = null }, modifier = modifier, manager = settingPin == true)
        return
    }
    MiniScaffold(
        title = stringResource(sectionTitle(section)),
        onBack = navigator::back,
        modifier = modifier,
        actions = {
            if (section == SettingsSections.TERMINAL) {
                SetupScannerAction(state.loaded, setupActions.unsavedSecrets.isNotEmpty()) { navigator.push(Route.TransferImport) }
            }
        },
    ) { padding ->
        if (!state.loaded) return@MiniScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).padding(bottom = 32.dp)) {
                if (section == SettingsSections.DATA) {
                    val samples by sampleData.state.collectAsStateWithLifecycle()
                    SampleDataSection(samples, events.samples)
                }
                val businessImport by vm.businessImport.state.collectAsStateWithLifecycle()
                SettingsContent(section, state, actions, setupActions, events, setupEvents, navigator, { settingPin = it }) {
                    ReceiptsSection(state, actions, businessImport, events)
                }
                if (section == SettingsSections.TERMINAL) {
                    val terminal by LocalAppContainer.current.terminalStatus.state
                        .collectAsStateWithLifecycle()
                    SetupImportFeedback(terminal.importPending, businessImport, events.businessImport, navigator)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CapabilitiesSectionScreen(payment: PaymentSettings) {
    val vm = capabilitiesViewModel()
    val capabilities by vm.state.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val events = remember(vm) { capabilitiesEvents(vm) }
    val currency = LocalAppContainer.current.currency(AppSettings(payment = payment)).code
    CapabilitiesSection(capabilities, actions, payment, currency, events)
}

@Composable
private fun ColumnScope.SetupImportFeedback(
    pending: Boolean,
    business: ReceiptBusinessImportState,
    events: ReceiptBusinessEvents,
    navigator: Navigator,
) {
    if (pending) {
        SettingActions {
            SecondaryButton(stringResource(R.string.result_try_again), {
                navigator.push(Route.TransferImport)
            }, modifier = Modifier.testTag("resumeSetupImport"))
        }
    }
    if (business.proposal != null || business.lookup.isError) {
        ReceiptBusinessImport(business, events)
        SecondaryButton(stringResource(R.string.settings_receipts), { navigator.push(Route.SettingsSection(SettingsSections.RECEIPTS)) })
    }
}

/** Header shortcut available throughout setup, with an explicit choice before leaving unsaved secret drafts. */
@Composable
private fun SetupScannerAction(
    enabled: Boolean,
    hasDrafts: Boolean,
    onScan: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    IconButton(
        onClick = { if (hasDrafts) confirm = true else onScan() },
        enabled = enabled,
        modifier = Modifier.testTag("scanSetup"),
    ) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.settings_quick_setup_import))
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_quick_setup_import),
            message = stringResource(R.string.settings_scan_discard_drafts),
            confirmLabel = stringResource(R.string.settings_scan_discard_confirm),
            onConfirm = {
                confirm = false
                onScan()
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Section-specific rows; receipt content and PIN navigation stay owned by the screen. */
@Composable
private fun ColumnScope.SettingsContent(
    section: String,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    navigator: Navigator,
    onSetPin: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (section) {
        SettingsSections.TERMINAL -> {
            TerminalSection(state, actions, setup, events, setupEvents, navigator)
            ConnectionResultSection(state, actions, events)
        }

        SettingsSections.CAPABILITIES -> {
            CapabilitiesSectionScreen(state.settings.payment)
        }

        SettingsSections.SIMULATOR -> {
            SimulatorSection(state, events)
        }

        SettingsSections.PAYMENTS -> {
            PaymentsSection(state, events)
        }

        SettingsSections.TIPPING -> {
            TippingSection(state.settings.payment) { transform ->
                events.onUpdate { it.copy(payment = transform(it.payment)) }
            }
        }

        SettingsSections.SHOPPER_DISPLAY -> {
            ShopperDisplaySection(state.settings.payment) { transform ->
                events.onUpdate { it.copy(payment = transform(it.payment)) }
            }
        }

        SettingsSections.TAX -> {
            TaxSection(state, actions, events)
        }

        SettingsSections.RECEIPTS -> {
            content()
        }

        SettingsSections.EMAIL -> {
            EmailSection(state, actions, events)
        }

        SettingsSections.SECURITY -> {
            SecuritySection(state, actions, events, onSetPin)
        }

        SettingsSections.DATA -> {
            DataSection(state, actions, events, navigator)
        }

        else -> {
            AboutSection(state)
        }
    }
}

/** The title of the settings [section] (one of the [SettingsSections] keys; unknown keys are About). */
@StringRes
private fun sectionTitle(section: String): Int =
    when (section) {
        SettingsSections.TERMINAL -> R.string.settings_terminal
        SettingsSections.CAPABILITIES -> R.string.settings_capabilities
        SettingsSections.SIMULATOR -> R.string.settings_simulator
        SettingsSections.PAYMENTS -> R.string.settings_payments
        SettingsSections.TIPPING -> R.string.settings_tips_donations
        SettingsSections.SHOPPER_DISPLAY -> R.string.settings_shopper_display
        SettingsSections.TAX -> R.string.settings_tax
        SettingsSections.RECEIPTS -> R.string.settings_receipts
        SettingsSections.EMAIL -> R.string.settings_email
        SettingsSections.SECURITY -> R.string.settings_security
        SettingsSections.DATA -> R.string.settings_data
        else -> R.string.settings_about
    }

/**
 * Settings › Terminal: where the connection stands, where payments go, then what that destination needs as numbered
 * steps ([TerminalSteps]), the Checkout API among them; for the simulator, its own settings.
 */
@Composable
private fun ColumnScope.TerminalSection(
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    navigator: Navigator,
) {
    val terminalStatus = LocalAppContainer.current.terminalStatus
    val status by terminalStatus.state.collectAsStateWithLifecycle()
    val mode = status.mode
    var manualSetup by rememberSaveable { mutableStateOf(false) }
    val terminal = state.settings.terminal
    // Initial setup is about saved work, not reachability or missing fields: a partially configured destination
    // must not regrow the full Quick setup section after a connection error.
    val untouched =
        terminal.mode in setOf(TerminalMode.AUTO, TerminalMode.SIMULATOR) &&
            listOf(
                terminal.host,
                terminal.poiIdOverride,
                terminal.merchantAccount,
                terminal.keyIdentifier,
                terminal.paymentsAppInstallationId,
                terminal.storeId,
                terminal.liveUrlPrefix,
            ).all { it.isBlank() } &&
            state.secrets.none { it in setOf(Secret.ADYEN_API_KEY, Secret.TERMINAL_PASSPHRASE, Secret.PAYMENTS_APP_API_KEY) }
    val quickSetup = status.loaded && untouched && !manualSetup && setup.unsavedSecrets.isEmpty()
    if (quickSetup) {
        QuickSetup { navigator.push(Route.TransferImport) }
        SectionHeader(stringResource(R.string.settings_manual_setup))
    }
    if (mode != TerminalMode.SIMULATOR && status.connection !is TerminalConnection.NotSetUp) {
        ConnectionStatus(status.connection, status.poiId, status.environment, status.setupProblem)
    }
    val selectMode: (TerminalMode) -> Unit = { choice ->
        manualSetup = true
        // The device's own default is stored as Automatic, so the app keeps following it. The selected environment stays
        // with the saved API key and merchant account it belongs to; otherwise it is selected or read again.
        val apiKeySaved = Secret.ADYEN_API_KEY in state.secrets
        events.onUpdate { it.copy(terminal = it.terminal.selectDestination(choice, terminalStatus.automaticMode, apiKeySaved)) }
    }
    TerminalModeChoice(mode, status.onTerminal, selectMode)
    if (mode == TerminalMode.SIMULATOR) {
        SettingNavRow(
            Icons.Default.Science,
            stringResource(R.string.settings_simulator),
            simulatorOutcomeLabel(state.settings.simulator.outcome),
            { navigator.push(Route.SettingsSection(SettingsSections.SIMULATOR)) },
        )
    } else {
        TerminalSteps(status, state, actions, setup, events, setupEvents)
    }
    val container = LocalAppContainer.current
    val wallets by container.walletDiscovery.state.collectAsStateWithLifecycle()
    if (wallets.supported) WalletSettings(wallets, container.currency(state.settings).code, container.walletDiscovery::refreshInBackground)
}

@Composable
private fun WalletSettings(
    state: WalletAvailability,
    currency: String,
    onRefresh: () -> Unit,
) {
    SectionHeader(stringResource(R.string.wallet_methods))
    val offered = state.offered(currency)
    if (state.checked) {
        SettingNote(
            if (offered.isEmpty()) {
                stringResource(R.string.wallet_none, currency)
            } else {
                stringResource(R.string.wallet_available, currency, offered.joinToString { it.displayName })
            },
        )
    }
    state.failure?.let { SettingNote(walletCheckFailed(it)) }
    SettingActions {
        GatedButton(
            stringResource(R.string.wallet_refresh),
            onRefresh,
            blocked = state.refreshBlocked,
            loading = state.checking,
            modifier = Modifier.testTag("refreshWallets"),
        )
    }
}

/** The outcome of the last connection test. */
@Composable
private fun ConnectionResultSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    val container = LocalAppContainer.current
    val status by container.terminalStatus.state.collectAsStateWithLifecycle()
    // A currency that follows the device's region may not be the merchant's, so a successful test names it.
    val automaticCurrency = container.currency(state.settings).code.takeIf { AdyenCurrencies[state.settings.payment.currencyCode] == null }

    actions.connection.outcome?.let { outcome ->
        // In the cloud the same test checks the Checkout API too (SettingsTest.CLOUD).
        val api = actions.api.outcome?.takeIf { status.mode == TerminalMode.CLOUD }
        ConnectionResultDialog(
            outcome.text(),
            actions.connection.isError,
            status.environment,
            automaticCurrency,
            api?.let {
                if (actions.api.isError) {
                    stringResource(R.string.settings_connection_api_failed, it.text())
                } else {
                    stringResource(R.string.settings_connection_api_ok)
                }
            },
            events::onConnectionResultDismiss,
        )
    }
}

@Composable
private fun QuickSetup(onImport: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_quick_setup))
    SettingNote(stringResource(R.string.settings_quick_setup_hint))
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_quick_setup_import),
            onImport,
            icon = Icons.Default.FileDownload,
            modifier = Modifier.testTag("quickSetupImport"),
        )
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
            is TerminalConnection.Failed -> connection.failure.text()
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

/**
 * The outcome of a connection test, in a dialog so it is seen however far the screen is scrolled. After a success it
 * also names the [environment], how the Checkout API test that came with it went ([api], in the cloud) and, while the
 * currency follows the device's region, that [automaticCurrency] (a code).
 */
@Composable
private fun ConnectionResultDialog(
    message: String,
    isError: Boolean,
    environment: TerminalEnvironment?,
    automaticCurrency: String?,
    api: String?,
    onDismiss: () -> Unit,
) {
    val details =
        if (isError) {
            emptyList()
        } else {
            listOfNotNull(
                environmentLabel(environment),
                api,
                automaticCurrency?.let { stringResource(R.string.settings_connection_currency, it) },
            )
        }
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
                (listOf(message) + details).joinToString("\n"),
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
    SettingIntro(stringResource(R.string.settings_simulator_hint))
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
    SettingSwitch(
        stringResource(R.string.settings_sim_offline),
        simulator.offlineMode,
        { value -> update { it.copy(offlineMode = value) } },
        subtitle = stringResource(R.string.settings_sim_offline_hint),
        tag = "simOffline",
    )
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
private fun SecuritySection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    onSetPin: (Boolean) -> Unit,
) {
    SettingIntro(stringResource(R.string.settings_pin_hint))
    SettingActions {
        SecondaryButton(
            stringResource(if (state.pinSet) R.string.settings_change_pin else R.string.settings_set_pin),
            { onSetPin(false) },
            icon = Icons.Default.Lock,
            modifier = Modifier.testTag("setPin"),
        )
        if (state.pinSet) {
            ConfirmedRemoval(
                text = stringResource(R.string.settings_remove_pin),
                confirmTitle = stringResource(R.string.settings_remove_pin),
                confirmMessage = stringResource(R.string.settings_remove_pin_message),
                onConfirm = events::onPinClear,
                icon = Icons.Default.LockOpen,
                modifier = Modifier.testTag("removePin"),
            )
        }
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
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
    ManagerPinSettings(state, events) { onSetPin(true) }
    SettingNote(stringResource(R.string.settings_pin_forgotten))
}

@Composable
private fun ManagerPinSettings(
    state: SettingsUiState,
    events: SettingsEvents,
    onSet: () -> Unit,
) {
    val set = Secret.MANAGER_PIN_VERIFIER in state.secrets
    SectionHeader(stringResource(R.string.manager_pin_title))
    SettingNote(stringResource(R.string.manager_pin_hint))
    SettingActions {
        SecondaryButton(
            stringResource(if (set) R.string.manager_pin_change else R.string.manager_pin_set),
            onSet,
            enabled = state.pinSet,
            icon = Icons.Default.Lock,
            modifier = Modifier.testTag("setManagerPin"),
        )
        if (set) {
            ConfirmedRemoval(
                text = stringResource(R.string.manager_pin_remove),
                confirmTitle = stringResource(R.string.manager_pin_remove),
                confirmMessage = stringResource(R.string.manager_pin_remove_message),
                onConfirm = { events.onSecretChange(Secret.MANAGER_PIN_VERIFIER, null) },
                icon = Icons.Default.LockOpen,
                modifier = Modifier.testTag("removeManagerPin"),
            )
        }
    }
}

@Composable
private fun DataSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    navigator: Navigator,
) {
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
    SettingActions {
        ConfirmedRemoval(
            text = stringResource(R.string.settings_clear_history),
            confirmTitle = stringResource(R.string.settings_clear_history),
            confirmMessage = stringResource(R.string.settings_clear_history_message),
            onConfirm = events::onHistoryClear,
            icon = Icons.Default.DeleteSweep,
            modifier = Modifier.testTag("clearHistory"),
        )
        if (actions.cleared) ActionMessage(stringResource(R.string.settings_history_cleared), isError = false)
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

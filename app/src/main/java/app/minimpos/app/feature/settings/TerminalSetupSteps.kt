package app.minimpos.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.DestinationRules
import app.minimpos.app.terminal.PaymentsAppDestination
import app.minimpos.app.terminal.SetupProgress
import app.minimpos.app.terminal.SetupStep
import app.minimpos.app.terminal.SetupStepKind
import app.minimpos.app.terminal.SetupStoreChoice
import app.minimpos.app.terminal.SetupVisit
import app.minimpos.app.terminal.TerminalSetupPlan
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SharedKeyDialog
import app.minimpos.app.ui.components.StepHeader
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.openUrl
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.terminal.transport.TerminalEnvironment

/** Changes the terminal settings with a transform of the stored ones. */
private typealias TerminalUpdate = ((TerminalSettings) -> TerminalSettings) -> Unit

/**
 * What Settings › Terminal asks for wherever payments go but the simulator, as numbered steps in the order they are
 * done. Network, cloud and Tap to Pay destinations first ask for the environment, before API access. On-device terminals read
 * their certificate; Tap to Pay then checks the selected environment's Payments app:
 * - this terminal: Adyen API, shared key, connection;
 * - a terminal on the network: environment, Adyen API, address and POIID, shared key, connection;
 * - a terminal in the cloud: environment, Adyen API, terminal, connection;
 * - Tap to Pay: environment, Payments app, Adyen API, boarding, shared key, connection.
 *
 * API testing unlocks subsequent steps in this visit; a later step stays hidden until then. Revealed steps stay available
 * for editing, so retesting or clearing an earlier field cannot discard an unsaved secret in a later step. A step's
 * number turns into a tick once it is done (every step once the setup is verified), and its actions follow one order:
 * the main action, then removals; outcomes show in a dialog instead, one at a time. Supplied helper details are not
 * tested.
 * The API key typed is kept here only in memory and cleared once stored. Discovery resets field editing state after
 * its saved settings have reached the screen, without resetting fields during ordinary typing.
 */
@Composable
internal fun ColumnScope.TerminalSteps(
    status: TerminalState,
    rules: DestinationRules,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
) {
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(actions.apiKeyStored, setup.apiKeyStored) { if (actions.apiKeyStored || setup.apiKeyStored) apiKey = "" }
    val api =
        ApiEntry(
            terminal = state.settings.terminal,
            environment = status.environment,
            keySaved = Secret.ADYEN_API_KEY in state.secrets,
            key = apiKey,
            onKey = { apiKey = it },
            problem = status.apiProblem,
        )
    val update: TerminalUpdate = { transform -> events.onUpdate { it.copy(terminal = transform(it.terminal)) } }
    key(status.mode, status.environment, setup.revision) {
        DestinationSteps(status, rules, state, actions, setup, events, setupEvents, api, update)
    }
}

@Composable
private fun ColumnScope.SharedKeyActions(
    setup: TerminalSetupActions,
    events: TerminalSetupEvents,
) {
    val offer = setup.sharedKeyOffer
    var review by remember(offer) { mutableStateOf(false) }
    offer?.let {
        SettingActions {
            SecondaryButton(
                stringResource(if (it.resume) R.string.settings_resume_shared_key else R.string.settings_create_shared_key),
                { review = true },
                loading = setup.keyResult.running,
                modifier = Modifier.testTag("createSharedKey"),
            )
        }
        if (review) {
            SharedKeyDialog(it, {
                review = false
                events.onSharedKeyConfirm(it)
            }, { review = false })
        }
    }
    if (setup.keyPending) {
        SettingActions {
            SecondaryButton(
                stringResource(R.string.settings_shared_key_check_again),
                events::onSharedKeyCheck,
                loading = setup.keyResult.running,
                modifier = Modifier.testTag("checkSharedKey"),
            )
        }
    }
}

/** Destination-specific steps after the selected or device-supplied environment is available. */
@Composable
private fun ColumnScope.DestinationSteps(
    status: TerminalState,
    rules: DestinationRules,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    api: ApiEntry,
    update: TerminalUpdate,
) {
    var testedFields by remember { mutableStateOf<Pair<String, String>?>(null) }
    var apiComplete by remember { mutableStateOf(false) }
    // An immediate retest can conflate running and success; new tested fields must still recheck completion.
    LaunchedEffect(actions.api, api.problem, api.key, testedFields) {
        if (api.testCompleted(actions.api, testedFields)) apiComplete = true
    }
    val terminal = state.settings.terminal
    val progress =
        SetupProgress(
            onTerminal = status.onTerminal,
            environmentChosen = status.environment != null,
            paymentsApps = status.paymentsApps,
            terminal = terminal,
            secrets = state.secrets,
            verified = !currentSetupGate().pending,
            visit = rememberSetupVisit(terminal, apiTested = apiComplete),
        )
    val plan = TerminalSetupPlan.steps(rules, progress)

    fun step(kind: SetupStepKind) = plan.firstOrNull { it.kind == kind }
    step(SetupStepKind.ENVIRONMENT)?.let { EnvironmentStep(it.number, it.done, status.environment, update) }
    step(SetupStepKind.PAYMENTS_APP)?.let { PaymentsAppStep(it, status.paymentsApps, status.environment) }
    val apiStep = step(SetupStepKind.ADYEN_API) ?: return
    AdyenApiStep(
        number = apiStep.number,
        done = apiStep.done,
        discovers = status.mode != TerminalMode.PAYMENTS_APP,
        findOnly = !status.onTerminal && status.mode != TerminalMode.PAYMENTS_APP,
        api = api,
        actions = actions,
        setup = setup,
        events = events,
        setupEvents = setupEvents,
        update = update,
        onFind = {
            testedFields = api.terminal.merchantAccount to api.terminal.liveUrlPrefix
            events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.API) { setupEvents.onTerminalsFind("") }
        },
        onTest = {
            testedFields = api.terminal.merchantAccount to api.terminal.liveUrlPrefix
            events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.API)
        },
    )
    ConnectionSteps(plan, status, state, actions, setup, events, setupEvents, api.key, update)
}

@Composable
private fun ColumnScope.ConnectionSteps(
    plan: List<SetupStep>,
    status: TerminalState,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    apiKey: String,
    update: TerminalUpdate,
) {
    var passphrase by remember { mutableStateOf("") }
    LaunchedEffect(actions.passphraseStored) { if (actions.passphraseStored) passphrase = "" }
    val terminal = state.settings.terminal

    fun step(kind: SetupStepKind) = plan.firstOrNull { it.kind == kind }
    step(SetupStepKind.NETWORK_TERMINAL)?.let { NetworkTerminalStep(it.number, it.done, terminal, update) }
    step(SetupStepKind.CLOUD_TERMINAL)?.let { cloud ->
        CloudTerminalStep(
            number = cloud.number,
            done = cloud.done,
            poiId = state.settings.terminal.poiIdOverride,
            onPoiId = { id -> update { it.copy(poiIdOverride = id) } },
        )
    }
    step(SetupStepKind.TAP_TO_PAY)?.let { TapToPayEntry(it.number, state, setup, setupEvents, update) }

    fun testConnection() {
        if (!actions.connection.running) {
            if (status.mode == TerminalMode.CLOUD) {
                events.onSaveAndTest(Secret.ADYEN_API_KEY, apiKey, SettingsTest.CLOUD)
            } else {
                events.onSaveAndTest(Secret.TERMINAL_PASSPHRASE, passphrase, SettingsTest.CONNECTION)
            }
        }
    }
    step(SetupStepKind.SHARED_KEY)?.let { key ->
        SharedKeyStep(key.number, key.done, state, events, setupEvents, update, passphrase, { passphrase = it }, ::testConnection)
    }
    SharedKeyActions(setup, setupEvents)
    step(SetupStepKind.CONNECTION)?.let {
        StepHeader(it.number, stringResource(R.string.settings_test_connection), done = it.done)
        SettingNote(stringResource(R.string.settings_connection_final_hint))
        SettingActions {
            TestButton(
                typed = passphrase.isNotEmpty(),
                save = R.string.settings_save_and_test,
                test = R.string.settings_test_connection,
                running = actions.connection.running,
                onClick = ::testConnection,
                tag = "testConnection",
            )
            actions.secretError?.let { error -> ActionMessage(error.text(), isError = true) }
        }
    }
}

/**
 * What this visit unlocked: [apiTested], and once entered in this visit a network terminal's address and POIID, or this
 * phone's registration; kept until the visit ends even if those details are removed again.
 */
@Composable
private fun rememberSetupVisit(
    terminal: TerminalSettings,
    apiTested: Boolean,
): SetupVisit {
    var terminalEntered by remember { mutableStateOf(false) }
    LaunchedEffect(terminal.host, terminal.poiIdOverride) {
        if (terminal.host.isNotBlank() && terminal.poiIdOverride.isNotBlank()) terminalEntered = true
    }
    var boarded by remember { mutableStateOf(false) }
    LaunchedEffect(terminal.paymentsAppInstallationId) {
        if (terminal.paymentsAppInstallationId.isNotBlank()) boarded = true
    }
    return SetupVisit(apiTested = apiTested, terminalEntered = terminalEntered, boarded = boarded)
}

/** The environment chosen off-terminal; no default silently opts into real payments. */
@Composable
private fun ColumnScope.EnvironmentStep(
    number: Int,
    done: Boolean,
    environment: TerminalEnvironment?,
    update: TerminalUpdate,
) {
    StepHeader(number, stringResource(R.string.settings_environment), done = done)
    SettingNote(stringResource(R.string.settings_environment_hint), Modifier.testTag("environmentHint"))
    Column(Modifier.selectableGroup().testTag("environment")) {
        TerminalEnvironment.entries.forEach { value ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = environment == value,
                        role = Role.RadioButton,
                        onClick = { update { it.selectEnvironment(value) } },
                    ).heightIn(min = 48.dp)
                    .padding(horizontal = LocalDimens.current.rowHorizontalPadding)
                    .testTag("environment_$value"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = environment == value, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(if (value == TerminalEnvironment.TEST) R.string.settings_env_test else R.string.settings_env_live))
            }
        }
    }
}

/**
 * The Checkout API as Settings › Terminal enters it.
 *
 * @property terminal The stored terminal settings (merchant account, live URL prefix).
 * @property environment Where payments go; the live URL prefix is asked for once it is LIVE.
 * @property keySaved Whether an API key is saved.
 * @property key The API key typed and not saved yet; empty when none is.
 * @property onKey Takes what is typed into the API key field.
 * @property problem What the Checkout API is still missing; null once it is set up.
 */
private class ApiEntry(
    val terminal: TerminalSettings,
    val environment: TerminalEnvironment?,
    val keySaved: Boolean,
    val key: String,
    val onKey: (String) -> Unit,
    val problem: SetupProblem?,
)

/** A successful test of the current account fields, with no missing setup or unsaved replacement key. */
private fun ApiEntry.testCompleted(
    result: ActionState,
    testedFields: Pair<String, String>?,
): Boolean {
    val matches = testedFields == (terminal.merchantAccount to terminal.liveUrlPrefix)
    val settled = result.done && key.isBlank()
    return matches && settled
}

/** Setting up Tap to Pay on this phone with an automatically provisioned credential. */
@Composable
private fun ColumnScope.TapToPayEntry(
    number: Int,
    state: SettingsUiState,
    setup: TerminalSetupActions,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
) {
    val terminal = state.settings.terminal
    TapToPayStep(
        number = number,
        installationId = terminal.paymentsAppInstallationId,
        storeId = terminal.storeId,
        setup = setup,
        onStoreId = { id -> update { it.copy(storeId = id) } },
        onFindStores = setupEvents::onStoresFind,
        onChooseStore = setupEvents::onStoreChoose,
        onSetUp = setupEvents::onTapToPaySetUp,
        onRemove = setupEvents::onTapToPayRemove,
    )
}

/** The IP address and POIID of a terminal on the network; a terminal running the app knows both itself. */
@Composable
private fun ColumnScope.NetworkTerminalStep(
    number: Int,
    done: Boolean,
    terminal: TerminalSettings,
    update: TerminalUpdate,
) {
    StepHeader(number, stringResource(R.string.settings_step_terminal), done = done)
    SettingNote(stringResource(R.string.settings_host_hint))
    SettingNote(stringResource(R.string.settings_poiid_help))
    SettingTextField(
        stringResource(R.string.settings_host),
        terminal.host,
        { value -> update { it.copy(host = value.trim()) } },
        placeholder = stringResource(R.string.settings_host_placeholder),
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
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "poiId",
    )
}

/**
 * Shared-key fields and removal. The following connection step saves the in-memory passphrase and verifies setup.
 */
@Composable
private fun ColumnScope.SharedKeyStep(
    number: Int,
    done: Boolean,
    state: SettingsUiState,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    onTest: () -> Unit,
) {
    val terminal = state.settings.terminal
    val saved = Secret.TERMINAL_PASSPHRASE in state.secrets
    StepHeader(number, stringResource(R.string.settings_shared_key), done = done)
    if (terminal.mode == TerminalMode.PAYMENTS_APP) {
        SettingNote(stringResource(R.string.settings_payments_app_shared_key_hint))
    }
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
        isSet = saved,
        value = passphrase,
        onValueChange = onPassphrase,
        onSubmit = onTest,
        tag = "passphrase",
        onDraft = { setupEvents.onSecretDraft(Secret.TERMINAL_PASSPHRASE, it) },
    )
    SettingNumberField(stringResource(R.string.settings_key_version), terminal.keyVersion, TerminalSettings.KEY_VERSIONS, { version ->
        update { it.copy(keyVersion = version) }
    }, tag = "keyVersion")
    SettingActions {
        if (saved) ForgetPassphrase(events)
    }
}

/** Removing a supplied shared-key passphrase, once confirmed. */
@Composable
private fun ForgetPassphrase(events: SettingsEvents) =
    ConfirmedRemoval(
        text = stringResource(R.string.settings_forget_passphrase),
        confirmTitle = stringResource(R.string.settings_forget_passphrase_title),
        confirmMessage = stringResource(R.string.settings_forget_passphrase_message),
        onConfirm = { events.onSecretChange(Secret.TERMINAL_PASSPHRASE, null) },
        icon = Icons.Default.KeyOff,
        modifier = Modifier.testTag("forgetPassphrase"),
    )

/**
 * The merchant account used with the saved Adyen API key and, once payments are known to go to LIVE, the live URL
 * prefix. These fields remain editable when discovery is unavailable.
 */
@Composable
private fun ColumnScope.ApiFields(
    api: ApiEntry,
    update: TerminalUpdate,
) {
    SettingNote(stringResource(R.string.settings_api_hint), Modifier.testTag("merchantAccountHint"))
    SettingTextField(
        stringResource(R.string.settings_merchant_account),
        api.terminal.merchantAccount,
        { value -> update { it.copy(merchantAccount = value.trim()) } },
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "merchantAccount",
    )

    // Until the environment is known, the Checkout API cannot be used anyway (SetupProblem.ENVIRONMENT).
    if (api.environment == TerminalEnvironment.LIVE || (api.environment == null && api.terminal.liveUrlPrefix.isNotBlank())) {
        SettingNote(stringResource(R.string.settings_live_prefix_hint), Modifier.testTag("livePrefixHint"))
        SettingTextField(
            stringResource(R.string.settings_live_prefix),
            api.terminal.liveUrlPrefix,
            { value -> update { it.copy(liveUrlPrefix = value.trim()) } },
            autoCorrect = false,
            tag = "livePrefix",
        )
    }
}

/**
 * One Adyen API step for every real destination: key and account together, then [onTest] and its outcome. Optional
 * terminal discovery first tests API access; saved helper fields are never marked tested.
 */
@Composable
private fun ColumnScope.AdyenApiStep(
    number: Int,
    done: Boolean,
    discovers: Boolean,
    findOnly: Boolean,
    api: ApiEntry,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
    onTest: () -> Unit,
    onFind: () -> Unit,
) {
    fun saveAndTest() {
        if (!actions.api.running) {
            if (findOnly) onFind() else onTest()
        }
    }
    StepHeader(number, stringResource(R.string.settings_api), done = done)
    SettingNote(stringResource(R.string.settings_adyen_key_hint))
    ApiKeyRoles()

    SecretField(
        label = stringResource(R.string.settings_api_key),
        isSet = api.keySaved,
        value = api.key,
        onValueChange = api.onKey,
        onSubmit = ::saveAndTest,
        tag = "apiKey",
        onDraft = { setupEvents.onSecretDraft(Secret.ADYEN_API_KEY, it) },
    )
    ApiFields(api, update)
    SettingActions {
        if (!findOnly) {
            TestButton(
                typed = api.key.isNotBlank(),
                save = R.string.settings_save_and_test_api,
                test = R.string.settings_test_api,
                running = actions.api.running,
                onClick = ::saveAndTest,
                tag = "testApi",
                icon = Icons.Default.Api,
            )
        }
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
        if (discovers) KeyDiscoveryActions(api, setup, actions.api.running, onFind)
        if (api.keySaved) ForgetApiKey(events)
    }
    setup.connectedTerminals?.let { TerminalChoiceDialog(it, setupEvents::onTerminalChoose) }
}

/**
 * The common required roles that new primary credentials lack by default; identical for every real destination.
 */
@Composable
private fun ColumnScope.ApiKeyRoles() {
    SettingNote(stringResource(R.string.settings_adyen_roles_hint))
    SettingNote("• " + stringResource(R.string.settings_adyen_role_cloud), Modifier.testTag("roleCloud"))
    SettingNote("• " + stringResource(R.string.settings_adyen_role_terminals), Modifier.testTag("roleTerminals"))
    listOf(R.string.settings_adyen_role_settings, R.string.settings_adyen_role_shared_key, R.string.settings_adyen_role_tokens).forEach {
        SettingNote("• " + stringResource(it))
    }
}

/** Optional terminal discovery, with its manual-entry fallback kept beside the API-key action. */
@Composable
private fun ColumnScope.KeyDiscoveryActions(
    api: ApiEntry,
    setup: TerminalSetupActions,
    testingApi: Boolean,
    onSave: () -> Unit,
) {
    TestButton(
        typed = api.key.isNotBlank(),
        save = if (testingApi) R.string.settings_testing_api else R.string.settings_save_and_discover,
        test =
            if (testingApi) {
                R.string.settings_testing_api
            } else if (setup.terminals.running) {
                R.string.settings_finding_setup
            } else {
                R.string.settings_discover
            },
        running = testingApi || setup.terminals.running,
        onClick = onSave,
        tag = "discoverSetup",
        icon = Icons.Default.Search,
    )
    if (setup.terminals.done || setup.terminals.isError || setup.manualDetails) {
        SettingNote(stringResource(R.string.settings_discovery_manual))
    }
}

/** Removing the saved Adyen API key, once confirmed. */
@Composable
private fun ForgetApiKey(events: SettingsEvents) =
    ConfirmedRemoval(
        text = stringResource(R.string.settings_forget_api_key),
        confirmTitle = stringResource(R.string.settings_forget_api_key_title),
        confirmMessage = stringResource(R.string.settings_forget_api_key_message),
        onConfirm = { events.onSecretChange(Secret.ADYEN_API_KEY, null) },
        icon = Icons.Default.KeyOff,
        modifier = Modifier.testTag("forgetApiKey"),
    )

/**
 * A test that first saves what was [typed]: then the primary [save] action, else the secondary [test] one, both
 * tagged [tag] and showing a spinner while [running].
 */
@Composable
private fun TestButton(
    typed: Boolean,
    @StringRes save: Int,
    @StringRes test: Int,
    running: Boolean,
    onClick: () -> Unit,
    tag: String,
    icon: ImageVector = Icons.Default.NetworkCheck,
) {
    val modifier = Modifier.testTag(tag)
    if (typed) {
        PrimaryButton(stringResource(save), onClick, modifier, loading = running, icon = icon)
    } else {
        SecondaryButton(stringResource(test), onClick, modifier, loading = running, icon = icon)
    }
}

/**
 * Terminal POIID, typed or chosen through the API step's discovery. Verification follows in its own step.
 */
@Composable
private fun ColumnScope.CloudTerminalStep(
    number: Int,
    done: Boolean,
    poiId: String,
    onPoiId: (String) -> Unit,
) {
    StepHeader(number, stringResource(R.string.settings_step_terminal), done = done)
    SettingNote(stringResource(R.string.settings_cloud_poiid_help))
    SettingTextField(
        label = stringResource(R.string.settings_poiid),
        value = poiId,
        onCommit = { onPoiId(it.trim()) },
        placeholder = stringResource(R.string.settings_poiid_hint),
        autoCorrect = false,
        imeAction = ImeAction.Done,
        tag = "poiId",
    )
}

/** The terminals connected in the cloud, to choose one; [onChoose] gets null when the dialog is dismissed. */
@Composable
internal fun TerminalChoiceDialog(
    poiIds: List<String>,
    onChoose: (poiId: String?) -> Unit,
) {
    ChoiceDialog(R.string.settings_choose_terminal, R.string.settings_no_terminals, poiIds.map { it to it }, "terminal", onChoose)
}

/** Choices of `id to label`, each tagged `<tagPrefix>_<id>`; [onChoose] gets null when the dialog is dismissed. */
@Composable
private fun ChoiceDialog(
    @StringRes title: Int,
    @StringRes empty: Int,
    choices: List<Pair<String, String>>,
    tagPrefix: String,
    onChoose: (id: String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) Text(stringResource(empty))
                choices.forEach { (id, label) ->
                    TextButton({ onChoose(id) }, Modifier.fillMaxWidth().testTag("${tagPrefix}_$id")) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton({ onChoose(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The installed Payments apps and, when the selected one is missing, its Google Play page. Both may be installed.
 * The app reads the device again when it comes back to the front, so an app installed meanwhile shows.
 */
@Composable
private fun ColumnScope.PaymentsAppStep(
    step: SetupStep,
    paymentsApps: Set<TerminalEnvironment>,
    environment: TerminalEnvironment?,
) {
    StepHeader(step.number, stringResource(R.string.settings_payments_app), done = step.done)
    SettingNote(stringResource(R.string.settings_payments_app_hint))
    Column(Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) {
        LabeledValue(stringResource(R.string.settings_payments_app_installed), paymentsAppLabel(paymentsApps))
    }
    if (environment != null && environment !in paymentsApps) {
        val context = LocalContext.current
        SettingActions {
            SecondaryButton(
                stringResource(
                    if (environment ==
                        TerminalEnvironment.TEST
                    ) {
                        R.string.settings_get_payments_app_test
                    } else {
                        R.string.settings_get_payments_app_live
                    },
                ),
                { context.openUrl(PaymentsAppDestination.storeUrl(environment)) },
                icon = Icons.Default.Download,
                modifier = Modifier.testTag(if (environment == TerminalEnvironment.TEST) "getPaymentsAppTest" else "getPaymentsAppLive"),
            )
        }
    }
}

/** Which Adyen Payments app is installed, for display. */
@Composable
@ReadOnlyComposable
private fun paymentsAppLabel(paymentsApps: Set<TerminalEnvironment>): String =
    when (paymentsApps.singleOrNull()) {
        TerminalEnvironment.TEST -> stringResource(R.string.settings_payments_app_test)
        TerminalEnvironment.LIVE -> stringResource(R.string.settings_payments_app_live)
        null -> stringResource(if (paymentsApps.isEmpty()) R.string.settings_payments_app_none else R.string.settings_payments_app_both)
    }

/**
 * Setting up Tap to Pay on this phone: the boarded [installationId] (blank until boarded), the optional [storeId], the
 * automatically managed credential, and buttons that set it up ([onSetUp] boards afresh when true) or remove this
 * phone; their outcome shows in a dialog.
 */
@Composable
private fun ColumnScope.TapToPayStep(
    number: Int,
    installationId: String,
    storeId: String,
    setup: TerminalSetupActions,
    onStoreId: (String) -> Unit,
    onFindStores: () -> Unit,
    onChooseStore: (String?) -> Unit,
    onSetUp: (again: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val boarded = installationId.isNotBlank()
    StepHeader(number, stringResource(R.string.settings_tap_to_pay), done = boarded)
    SettingNote(stringResource(R.string.settings_tap_to_pay_hint))
    Column(Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) {
        LabeledValue(
            stringResource(R.string.settings_installation_id),
            installationId.ifBlank { stringResource(R.string.settings_not_boarded) },
        )
    }
    SettingTextField(
        label = stringResource(R.string.settings_store_id),
        value = storeId,
        onCommit = { onStoreId(it.trim()) },
        supporting = stringResource(R.string.settings_store_id_hint),
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "storeId",
    )
    val focus = LocalFocusManager.current
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_choose_store),
            {
                // A focused Store ID draft would otherwise keep, and later re-commit, its text over the chosen store.
                focus.clearFocus()
                onFindStores()
            },
            Modifier.testTag("findStores"),
            loading = setup.stores.running,
            icon = Icons.Default.Search,
        )
    }
    setup.storeChoices?.let { StoreChoiceDialog(it, onChooseStore) }
    TapToPayButtons(boarded, setup.tapToPay, onSetUp, onRemove)
}

/** Store IDs with merchant reference/description; null selection closes without changing the saved store. */
@Composable
private fun StoreChoiceDialog(
    stores: List<SetupStoreChoice>,
    onChoose: (String?) -> Unit,
) {
    val choices =
        remember(stores) {
            stores.map { store ->
                store.id to listOf(store.reference, store.description, store.id).filter { it.isNotBlank() }.distinct().joinToString("\n")
            }
        }
    ChoiceDialog(R.string.settings_choose_store, R.string.settings_no_stores, choices, "store", onChoose)
}

/** Set up (or again, once [boarded]) or remove this phone, busy while [result] runs. */
@Composable
private fun TapToPayButtons(
    boarded: Boolean,
    result: ActionState,
    onSetUp: (again: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    SettingActions {
        val tag = Modifier.testTag("setUpTapToPay")
        if (boarded) {
            SecondaryButton(stringResource(R.string.settings_set_up_again), {
                onSetUp(true)
            }, tag, loading = result.running, icon = Icons.Default.Refresh)
        } else {
            PrimaryButton(
                stringResource(R.string.settings_set_up_tap_to_pay),
                { onSetUp(false) },
                tag,
                loading = result.running,
                icon = Icons.Default.Contactless,
            )
        }
        if (boarded) {
            ConfirmedRemoval(
                text = stringResource(R.string.settings_remove_phone),
                confirmTitle = stringResource(R.string.settings_remove_phone_title),
                confirmMessage = stringResource(R.string.settings_remove_phone_message),
                onConfirm = onRemove,
                icon = Icons.Default.PhonelinkErase,
                modifier = Modifier.testTag("removePhone"),
            )
        }
    }
}

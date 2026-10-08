package io.github.astiskala.minimpos.app.feature.settings

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.OutcomeMessage
import io.github.astiskala.minimpos.app.feature.text
import io.github.astiskala.minimpos.app.terminal.PaymentsAppDestination
import io.github.astiskala.minimpos.app.terminal.TerminalState
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.SharedKeyDialog
import io.github.astiskala.minimpos.app.ui.components.openUrl
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment

/** Changes the terminal settings with a transform of the stored ones. */
private typealias TerminalUpdate = ((TerminalSettings) -> TerminalSettings) -> Unit

/**
 * What Settings › Terminal asks for wherever payments go but the simulator, as numbered steps in the order they are
 * done. Network and cloud destinations first ask for the environment, before API access. On-device terminals read
 * their certificate; Tap to Pay starts with the Payments app whose installation determines the environment:
 * - this terminal: 1. Adyen API, 2. shared key;
 * - a terminal on the network: 1. environment, 2. Adyen API, 3. address and POIID, 4. shared key;
 * - a terminal in the cloud: 1. environment, 2. Adyen API, 3. terminal;
 * - Tap to Pay: 1. Payments app, 2. Adyen API, 3. boarding, 4. shared key.
 *
 * API testing unlocks subsequent steps in this visit. Revealed steps stay available for editing, so retesting or
 * clearing an earlier field cannot discard an unsaved secret in a later step. Supplied helper details are not tested.
 * The API key typed is kept here only in memory and cleared once stored. Discovery resets field editing state after
 * its saved settings have reached the screen, without resetting fields during ordinary typing.
 */
@Composable
internal fun ColumnScope.TerminalSteps(
    status: TerminalState,
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
        if (status.selectsEnvironment) {
            EnvironmentStep(status.environment, update)
        }
        if (!status.selectsEnvironment || status.environment != null || state.hasSavedConnectionDetails()) {
            DestinationSteps(status, state, actions, setup, events, setupEvents, api, update)
        }
    }
    SharedKeyActions(setup, setupEvents)
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
    OutcomeMessage(setup.keyResult, Modifier.testTag("sharedKeyResult"))
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

private fun SettingsUiState.hasSavedConnectionDetails(): Boolean {
    val terminal = settings.terminal
    val values = listOf(terminal.merchantAccount, terminal.keyIdentifier, terminal.host, terminal.poiIdOverride)
    return values.any(String::isNotBlank) || secrets.any { it in setOf(Secret.ADYEN_API_KEY, Secret.TERMINAL_PASSPHRASE) }
}

private fun TerminalState.needsPaymentsApp(
    suppliedDetails: Boolean,
    apiSaved: Boolean,
): Boolean = paymentsApps.size != 1 && !suppliedDetails && !apiSaved

private fun SettingsUiState.suppliedDestinationDetails(mode: TerminalMode): Boolean {
    val terminal = settings.terminal
    val fields =
        when (mode) {
            TerminalMode.TERMINAL -> listOf(terminal.host, terminal.poiIdOverride, terminal.keyIdentifier)
            TerminalMode.CLOUD -> listOf(terminal.poiIdOverride)
            TerminalMode.PAYMENTS_APP -> listOf(terminal.paymentsAppInstallationId, terminal.keyIdentifier)
            TerminalMode.AUTO, TerminalMode.SIMULATOR -> emptyList()
        }
    val passphraseSupplied = mode != TerminalMode.CLOUD && Secret.TERMINAL_PASSPHRASE in secrets
    return fields.any(String::isNotBlank) || passphraseSupplied
}

/** Destination-specific steps after the selected or device-supplied environment is available. */
@Composable
private fun ColumnScope.DestinationSteps(
    status: TerminalState,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    api: ApiEntry,
    update: TerminalUpdate,
) {
    val suppliedDetails = state.suppliedDestinationDetails(status.mode)
    var testedFields by remember { mutableStateOf<Pair<String, String>?>(null) }
    var apiComplete by remember { mutableStateOf(false) }
    // An immediate retest can conflate running and success; new tested fields must still recheck completion.
    LaunchedEffect(actions.api, api.problem, api.key, testedFields) {
        if (api.testCompleted(actions.api, testedFields)) apiComplete = true
    }
    if (status.mode == TerminalMode.PAYMENTS_APP) {
        PaymentsAppStep(1, status.paymentsApps)
        if (status.needsPaymentsApp(suppliedDetails, api.keySaved)) return
    }
    AdyenApiStep(
        number = if (status.selectsEnvironment || status.mode == TerminalMode.PAYMENTS_APP) 2 else 1,
        cloud = status.mode == TerminalMode.CLOUD,
        discovers = status.mode != TerminalMode.PAYMENTS_APP,
        discoveryReady = api.testCompleted(actions.api, testedFields),
        api = api,
        actions = actions,
        setup = setup,
        events = events,
        setupEvents = setupEvents,
        update = update,
        onTest = {
            testedFields = api.terminal.merchantAccount to api.terminal.liveUrlPrefix
            events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.API)
        },
    )
    if (!apiComplete && !suppliedDetails) return
    when (status.mode) {
        TerminalMode.TERMINAL -> {
            LocalTerminalSteps(status.onTerminal, state, actions, events, setupEvents, update)
        }

        TerminalMode.CLOUD -> {
            CloudTerminalStep(
                number = 3,
                poiId = state.settings.terminal.poiIdOverride,
                setup = setup,
                testing = actions.connection.running,
                onPoiId = { id -> update { it.copy(poiIdOverride = id) } },
                onFindTerminals = { setupEvents.onTerminalsFind(api.key) },
                onTest = {
                    if (!actions.connection.running) events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.CLOUD)
                },
            )
        }

        TerminalMode.PAYMENTS_APP -> {
            TapToPaySteps(state, actions, setup, events, setupEvents, update)
        }

        TerminalMode.SIMULATOR, TerminalMode.AUTO -> {}
    }
}

/** The environment chosen before using a network or cloud terminal; no default silently opts into real payments. */
@Composable
private fun ColumnScope.EnvironmentStep(
    environment: TerminalEnvironment?,
    update: TerminalUpdate,
) {
    SetupStep(1, stringResource(R.string.settings_environment))
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
                    .padding(horizontal = 16.dp)
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

/** A terminal reached by the local Terminal API: this one ([onTerminal]), or one on the network. */
@Composable
private fun ColumnScope.LocalTerminalSteps(
    onTerminal: Boolean,
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
) {
    val first = if (onTerminal) 2 else 4
    if (!onTerminal) {
        NetworkTerminalStep(3, state.settings.terminal, update)
        var terminalComplete by remember { mutableStateOf(false) }
        val terminal = state.settings.terminal
        LaunchedEffect(terminal.host, terminal.poiIdOverride) {
            if (terminal.host.isNotBlank() && terminal.poiIdOverride.isNotBlank()) terminalComplete = true
        }
        if (!terminalComplete && terminal.keyIdentifier.isBlank() && Secret.TERMINAL_PASSPHRASE !in state.secrets) return
    }
    SharedKeyStep(first, state, actions, events, setupEvents, update)
}

/** Tap to Pay with the Adyen Payments app on this phone: the app, the Checkout API, setting it up, the shared key. */
@Composable
private fun ColumnScope.TapToPaySteps(
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
) {
    val terminal = state.settings.terminal
    TapToPayStep(
        number = 3,
        installationId = terminal.paymentsAppInstallationId,
        storeId = terminal.storeId,
        apiKeySaved = Secret.PAYMENTS_APP_API_KEY in state.secrets,
        setup = setup,
        onStoreId = { id -> update { it.copy(storeId = id) } },
        onSetUp = setupEvents::onTapToPaySetUp,
        onRemove = setupEvents::onTapToPayRemove,
        onForgetApiKey = { events.onSecretChange(Secret.PAYMENTS_APP_API_KEY, null) },
        onDraft = { setupEvents.onSecretDraft(Secret.PAYMENTS_APP_API_KEY, it) },
    )
    var boarded by remember { mutableStateOf(false) }
    LaunchedEffect(terminal.paymentsAppInstallationId) {
        if (terminal.paymentsAppInstallationId.isNotBlank()) boarded = true
    }
    if (boarded || terminal.keyIdentifier.isNotBlank() || Secret.TERMINAL_PASSPHRASE in state.secrets) {
        SharedKeyStep(4, state, actions, events, setupEvents, update)
    }
}

/** The IP address and POIID of a terminal on the network; a terminal running the app knows both itself. */
@Composable
private fun ColumnScope.NetworkTerminalStep(
    number: Int,
    terminal: TerminalSettings,
    update: TerminalUpdate,
) {
    SetupStep(number, stringResource(R.string.settings_step_terminal))
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
 * The shared key: identifier, passphrase (only kept in memory while typed, and cleared once stored) and version, the
 * button that saves the passphrase typed and tests the connection, and removing the saved passphrase.
 */
@Composable
private fun ColumnScope.SharedKeyStep(
    number: Int,
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
) {
    val terminal = state.settings.terminal
    val saved = Secret.TERMINAL_PASSPHRASE in state.secrets
    var passphrase by remember { mutableStateOf("") }
    LaunchedEffect(actions.passphraseStored) { if (actions.passphraseStored) passphrase = "" }

    fun saveAndTest() {
        if (!actions.connection.running) events.onSaveAndTest(Secret.TERMINAL_PASSPHRASE, passphrase, SettingsTest.CONNECTION)
    }
    SetupStep(number, stringResource(R.string.settings_shared_key))
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
        onValueChange = { passphrase = it },
        onSubmit = ::saveAndTest,
        tag = "passphrase",
        onDraft = { setupEvents.onSecretDraft(Secret.TERMINAL_PASSPHRASE, it) },
    )
    SettingNumberField(stringResource(R.string.settings_key_version), terminal.keyVersion, TerminalSettings.KEY_VERSIONS, { version ->
        update { it.copy(keyVersion = version) }
    }, tag = "keyVersion")
    if (saved) {
        SettingActions { ForgetPassphrase(events) }
    }
    SettingActions {
        TestButton(
            typed = passphrase.isNotEmpty(),
            save = R.string.settings_save_and_test,
            test = R.string.settings_test_connection,
            running = actions.connection.running,
            onClick = ::saveAndTest,
            tag = "testConnection",
        )
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
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
 * terminal discovery appears after successful API testing; saved helper fields are never marked tested.
 */
@Composable
private fun ColumnScope.AdyenApiStep(
    number: Int,
    cloud: Boolean,
    discovers: Boolean,
    discoveryReady: Boolean,
    api: ApiEntry,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    update: TerminalUpdate,
    onTest: () -> Unit,
) {
    fun saveAndTest() {
        if (!actions.api.running) onTest()
    }
    SetupStep(number, stringResource(R.string.settings_api))
    SettingNote(stringResource(R.string.settings_adyen_key_hint))
    ApiKeyRoles(cloud)

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
        if (api.keySaved) ForgetApiKey(events)
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
    }
    SettingActions {
        TestButton(
            typed = api.key.isNotBlank(),
            save = R.string.settings_save_and_test_api,
            test = R.string.settings_test_api,
            running = actions.api.running,
            onClick = ::saveAndTest,
            tag = "testApi",
            icon = Icons.Default.Api,
        )
        OutcomeMessage(actions.api, Modifier.testTag("apiResult"))
        if (discovers && discoveryReady) KeyDiscoveryActions(api, setup) { setupEvents.onTerminalsFind(api.key) }
    }
    setup.connectedTerminals?.let { TerminalChoiceDialog(it, setupEvents::onTerminalChoose) }
}

/** Mandatory Management roles beyond default Checkout access; cloud adds its transport role. */
@Composable
private fun ColumnScope.ApiKeyRoles(cloud: Boolean) {
    SettingNote(stringResource(R.string.settings_adyen_roles_hint))
    if (cloud) SettingNote("• " + stringResource(R.string.settings_adyen_role_cloud), Modifier.testTag("roleCloud"))
    SettingNote("• " + stringResource(R.string.settings_adyen_role_terminals), Modifier.testTag("roleTerminals"))
    listOf(R.string.settings_adyen_role_settings, R.string.settings_adyen_role_shared_key).forEach {
        SettingNote("• " + stringResource(it))
    }
}

/** Optional terminal discovery, with its manual-entry fallback kept beside the API-key action. */
@Composable
private fun ColumnScope.KeyDiscoveryActions(
    api: ApiEntry,
    setup: TerminalSetupActions,
    onSave: () -> Unit,
) {
    TestButton(
        typed = api.key.isNotBlank(),
        save = R.string.settings_save_and_discover,
        test = R.string.settings_discover,
        running = setup.terminals.running,
        onClick = onSave,
        tag = "discoverSetup",
        icon = Icons.Default.Search,
    )
    if (setup.terminals.done || setup.terminals.isError || setup.manualDetails) {
        SettingNote(stringResource(R.string.settings_discovery_manual))
    }
    OutcomeMessage(setup.terminals, Modifier.testTag("terminalsResult"))
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
 * The terminal in the cloud: its POIID, typed or chosen using [onFindTerminals] and [TerminalChoiceDialog], and the
 * connection test ([testing] while it runs), which also rechecks the Checkout API.
 */
@Composable
private fun ColumnScope.CloudTerminalStep(
    number: Int,
    poiId: String,
    setup: TerminalSetupActions,
    testing: Boolean,
    onPoiId: (String) -> Unit,
    onFindTerminals: () -> Unit,
    onTest: () -> Unit,
) {
    SetupStep(number, stringResource(R.string.settings_step_terminal))
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
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_find_terminals),
            onFindTerminals,
            loading = setup.terminals.running,
            icon = Icons.Default.Search,
            modifier = Modifier.testTag("findTerminals"),
        )
        TestButton(
            typed = false,
            save = R.string.settings_save_and_test,
            test = R.string.settings_test_connection,
            running = testing,
            onClick = onTest,
            tag = "testConnection",
        )
    }
}

/** The terminals connected in the cloud, to choose one; [onChoose] gets null when the dialog is dismissed. */
@Composable
private fun TerminalChoiceDialog(
    poiIds: List<String>,
    onChoose: (poiId: String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(stringResource(R.string.settings_choose_terminal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (poiIds.isEmpty()) Text(stringResource(R.string.settings_no_terminals))
                poiIds.forEach { poiId ->
                    TextButton({ onChoose(poiId) }, Modifier.fillMaxWidth().testTag("terminal_$poiId")) { Text(poiId) }
                }
            }
        },
        confirmButton = { TextButton({ onChoose(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The Adyen Payments app: which one is installed ([paymentsApps]) and, while none is, buttons that open its Google Play
 * page (TEST and LIVE); with both installed, what to do about it. The app reads the device again when it comes back to
 * the front, so an app installed meanwhile shows.
 */
@Composable
private fun ColumnScope.PaymentsAppStep(
    number: Int,
    paymentsApps: Set<TerminalEnvironment>,
) {
    SetupStep(number, stringResource(R.string.settings_payments_app))
    SettingNote(stringResource(R.string.settings_payments_app_hint))
    Column(Modifier.padding(horizontal = 16.dp)) {
        LabeledValue(stringResource(R.string.settings_payments_app_installed), paymentsAppLabel(paymentsApps))
    }
    if (paymentsApps.size > 1) {
        SettingActions { ActionMessage(stringResource(R.string.setup_payments_app_ambiguous), isError = true) }
    } else if (paymentsApps.isEmpty()) {
        val context = LocalContext.current

        fun open(environment: TerminalEnvironment) {
            context.openUrl(PaymentsAppDestination.storeUrl(environment))
        }
        SettingActions {
            SecondaryButton(
                stringResource(R.string.settings_get_payments_app_test),
                { open(TerminalEnvironment.TEST) },
                icon = Icons.Default.Download,
                modifier = Modifier.testTag("getPaymentsAppTest"),
            )
            SecondaryButton(
                stringResource(R.string.settings_get_payments_app_live),
                { open(TerminalEnvironment.LIVE) },
                icon = Icons.Default.Download,
                modifier = Modifier.testTag("getPaymentsAppLive"),
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
 * Payments app API key (only kept in memory while typed, and cleared once stored), and the buttons that set it up
 * ([onSetUp] gets the key typed, or an empty one, and whether it is boarded already), remove this phone or forget the
 * key, with the last outcome.
 */
@Composable
private fun ColumnScope.TapToPayStep(
    number: Int,
    installationId: String,
    storeId: String,
    apiKeySaved: Boolean,
    setup: TerminalSetupActions,
    onStoreId: (String) -> Unit,
    onSetUp: (apiKey: String, again: Boolean) -> Unit,
    onRemove: () -> Unit,
    onForgetApiKey: () -> Unit,
    onDraft: (Boolean) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(setup.paymentsAppKeyStored) { if (setup.paymentsAppKeyStored) apiKey = "" }
    val boarded = installationId.isNotBlank()
    SetupStep(number, stringResource(R.string.settings_tap_to_pay))
    SettingNote(stringResource(R.string.settings_tap_to_pay_hint))
    SettingNote(stringResource(R.string.settings_payments_app_key_hint))
    Column(Modifier.padding(horizontal = 16.dp)) {
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
    SecretField(
        label = stringResource(R.string.settings_payments_app_key),
        isSet = apiKeySaved,
        value = apiKey,
        onValueChange = { apiKey = it },
        onSubmit = { onSetUp(apiKey, boarded) },
        tag = "paymentsAppKey",
        onDraft = onDraft,
    )
    TapToPayButtons(boarded, apiKeySaved, setup.tapToPay, { again -> onSetUp(apiKey, again) }, onRemove, onForgetApiKey)
}

/** Set up (or again, once [boarded]), remove this phone and forget the saved API key, with the last [result]. */
@Composable
private fun TapToPayButtons(
    boarded: Boolean,
    apiKeySaved: Boolean,
    result: ActionState,
    onSetUp: (again: Boolean) -> Unit,
    onRemove: () -> Unit,
    onForgetApiKey: () -> Unit,
) {
    SettingActions {
        val tag = Modifier.testTag("setUpTapToPay")
        if (boarded) {
            SecondaryButton(stringResource(R.string.settings_set_up_again), {
                onSetUp(true)
            }, tag, loading = result.running, icon = Icons.Default.Refresh)
            ConfirmedRemoval(
                text = stringResource(R.string.settings_remove_phone),
                confirmTitle = stringResource(R.string.settings_remove_phone_title),
                confirmMessage = stringResource(R.string.settings_remove_phone_message),
                onConfirm = onRemove,
                icon = Icons.Default.PhonelinkErase,
                modifier = Modifier.testTag("removePhone"),
            )
        } else {
            PrimaryButton(
                stringResource(R.string.settings_set_up_tap_to_pay),
                { onSetUp(false) },
                tag,
                loading = result.running,
                icon = Icons.Default.Contactless,
            )
        }
        if (apiKeySaved) {
            ConfirmedRemoval(
                text = stringResource(R.string.settings_forget_payments_app_key),
                confirmTitle = stringResource(R.string.settings_forget_payments_app_key_title),
                confirmMessage = stringResource(R.string.settings_forget_payments_app_key_message),
                onConfirm = onForgetApiKey,
                icon = Icons.Default.KeyOff,
                modifier = Modifier.testTag("forgetPaymentsAppKey"),
            )
        }
        OutcomeMessage(result, Modifier.testTag("tapToPayResult"))
    }
}

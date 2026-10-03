package io.minimpos.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.minimpos.app.R
import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.app.feature.ActionState
import io.minimpos.app.feature.OutcomeMessage
import io.minimpos.app.feature.text
import io.minimpos.app.terminal.PaymentsAppDestination
import io.minimpos.app.terminal.TerminalState
import io.minimpos.app.ui.components.ActionMessage
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.terminal.transport.TerminalEnvironment

/** Changes the terminal settings with a transform of the stored ones. */
private typealias TerminalUpdate = ((TerminalSettings) -> TerminalSettings) -> Unit

/**
 * What Settings › Terminal asks for wherever payments go but the simulator, as numbered steps in the order they are
 * done. Every destination needs the Checkout API too:
 * - this terminal: 1. the shared key, 2. the Checkout API;
 * - a terminal on the network: 1. its address and POIID, 2. the shared key, 3. the Checkout API;
 * - a terminal in the cloud: 1. the Adyen account (merchant account and the API key it shares with the Checkout API),
 *   2. the terminal, with one test of both;
 * - Tap to Pay: 1. the Adyen Payments app, 2. the Checkout API (whose merchant account it is set up for), 3. setting up
 *   Tap to Pay, 4. the shared key its payments are encrypted with.
 *
 * The API key typed is kept here (only in memory, and cleared once stored), so the cloud's buttons in step 2 can save it.
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
    when (status.mode) {
        TerminalMode.TERMINAL -> {
            LocalTerminalSteps(status.onTerminal, state, actions, events, api, update)
        }

        TerminalMode.CLOUD -> {
            CloudSteps(state, actions, setup, events, setupEvents, api, update)
        }

        TerminalMode.PAYMENTS_APP -> {
            TapToPaySteps(status, state, actions, setup, events, setupEvents, api, update)
        }

        TerminalMode.SIMULATOR, TerminalMode.AUTO -> {}
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

/** A terminal reached by the local Terminal API: this one ([onTerminal]), or one on the network. */
@Composable
private fun ColumnScope.LocalTerminalSteps(
    onTerminal: Boolean,
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
    api: ApiEntry,
    update: TerminalUpdate,
) {
    val first = if (onTerminal) 1 else 2
    if (!onTerminal) NetworkTerminalStep(1, state.settings.terminal, update)
    SharedKeyStep(first, state, actions, events, update)
    CheckoutApiStep(first + 1, api, actions, events, update)
}

/** Tap to Pay with the Adyen Payments app on this phone: the app, the Checkout API, setting it up, the shared key. */
@Composable
private fun ColumnScope.TapToPaySteps(
    status: TerminalState,
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    api: ApiEntry,
    update: TerminalUpdate,
) {
    val terminal = state.settings.terminal
    PaymentsAppStep(1, status.paymentsApps)
    CheckoutApiStep(2, api, actions, events, update)
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
    )
    SharedKeyStep(4, state, actions, events, update)
}

/** A terminal in the cloud: the Adyen account its API key belongs to, then the terminal, tested together. */
@Composable
private fun ColumnScope.CloudSteps(
    state: SettingsUiState,
    actions: SettingsActions,
    setup: TerminalSetupActions,
    events: SettingsEvents,
    setupEvents: TerminalSetupEvents,
    api: ApiEntry,
    update: TerminalUpdate,
) {
    SetupStep(1, stringResource(R.string.settings_api_cloud))
    SettingNote(stringResource(R.string.settings_api_cloud_hint))
    // The terminal's buttons, next, save the key.
    ApiFields(api, update, onSubmit = {})
    if (api.problem != null || api.keySaved) {
        SettingActions {
            api.problem?.let { ActionMessage(it.text(), isError = true, modifier = Modifier.testTag("apiProblem")) }
            if (api.keySaved) ForgetApiKey(events)
        }
    }
    CloudTerminalStep(
        number = 2,
        poiId = state.settings.terminal.poiIdOverride,
        setup = setup,
        keyTyped = api.key.isNotBlank(),
        testing = actions.connection.running,
        onPoiId = { id -> update { it.copy(poiIdOverride = id) } },
        onFindTerminals = { setupEvents.onTerminalsFind(api.key) },
        onChooseTerminal = setupEvents::onTerminalChoose,
        onTest = {
            if (!actions.connection.running) events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.CLOUD)
        },
    )
}

/** The IP address and POIID of a terminal on the network; a terminal running the app knows both itself. */
@Composable
private fun ColumnScope.NetworkTerminalStep(
    number: Int,
    terminal: TerminalSettings,
    update: TerminalUpdate,
) {
    SetupStep(number, stringResource(R.string.settings_step_terminal))
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
 * The shared key: identifier, passphrase (only kept in memory while typed, and cleared once stored) and version, the
 * button that saves the passphrase typed and tests the connection, and removing the saved passphrase.
 */
@Composable
private fun ColumnScope.SharedKeyStep(
    number: Int,
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
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
    )
    SettingNumberField(stringResource(R.string.settings_key_version), terminal.keyVersion, TerminalSettings.KEY_VERSIONS, { version ->
        update { it.copy(keyVersion = version) }
    }, tag = "keyVersion")
    SettingActions {
        TestButton(
            typed = passphrase.isNotEmpty(),
            save = R.string.settings_save_and_test,
            test = R.string.settings_test_connection,
            running = actions.connection.running,
            onClick = ::saveAndTest,
            tag = "testConnection",
        )
        if (saved) {
            ConfirmedRemoval(
                text = stringResource(R.string.settings_forget_passphrase),
                confirmTitle = stringResource(R.string.settings_forget_passphrase_title),
                confirmMessage = stringResource(R.string.settings_forget_passphrase_message),
                onConfirm = { events.onSecretChange(Secret.TERMINAL_PASSPHRASE, null) },
                icon = Icons.Default.KeyOff,
                modifier = Modifier.testTag("forgetPassphrase"),
            )
        }
        actions.secretError?.let { ActionMessage(it.text(), isError = true) }
    }
}

/**
 * The Checkout API, which every destination needs (captures, adjustments and payment links): its fields, what is still
 * missing, the button that saves the key typed and tests it, removing the saved key, and the outcome of the last test.
 */
@Composable
private fun ColumnScope.CheckoutApiStep(
    number: Int,
    api: ApiEntry,
    actions: SettingsActions,
    events: SettingsEvents,
    update: TerminalUpdate,
) {
    fun saveAndTest() {
        if (!actions.api.running) events.onSaveAndTest(Secret.ADYEN_API_KEY, api.key, SettingsTest.API)
    }
    SetupStep(number, stringResource(R.string.settings_api))
    SettingNote(stringResource(R.string.settings_api_hint))
    ApiFields(api, update, onSubmit = ::saveAndTest)
    SettingActions {
        api.problem?.let { ActionMessage(it.text(), isError = true, modifier = Modifier.testTag("apiProblem")) }
        TestButton(
            typed = api.key.isNotBlank(),
            save = R.string.settings_save_and_test_api,
            test = R.string.settings_test_api,
            running = actions.api.running,
            onClick = ::saveAndTest,
            tag = "testApi",
            icon = Icons.Default.Api,
        )
        if (api.keySaved) ForgetApiKey(events)
        OutcomeMessage(actions.api, Modifier.testTag("apiResult"))
    }
}

/**
 * The Checkout API's merchant account, API key and, once payments are known to go to LIVE, the live URL prefix. The
 * keyboard's Done key on the API key calls [onSubmit].
 */
@Composable
private fun ApiFields(
    api: ApiEntry,
    update: TerminalUpdate,
    onSubmit: () -> Unit,
) {
    SettingTextField(
        stringResource(R.string.settings_merchant_account),
        api.terminal.merchantAccount,
        { value -> update { it.copy(merchantAccount = value.trim()) } },
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "merchantAccount",
    )
    SecretField(
        label = stringResource(R.string.settings_api_key),
        isSet = api.keySaved,
        value = api.key,
        onValueChange = api.onKey,
        onSubmit = onSubmit,
        tag = "apiKey",
    )
    // Until the environment is known, the Checkout API cannot be used anyway (SetupProblem.ENVIRONMENT).
    if (api.environment == TerminalEnvironment.LIVE) {
        SettingTextField(
            stringResource(R.string.settings_live_prefix),
            api.terminal.liveUrlPrefix,
            { value -> update { it.copy(liveUrlPrefix = value.trim()) } },
            supporting = stringResource(R.string.settings_live_prefix_hint),
            autoCorrect = false,
            tag = "livePrefix",
        )
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
 * The terminal in the cloud: its POIID, typed or chosen from the terminals connected to the merchant account
 * ([TerminalSetupActions.connectedTerminals], found with [onFindTerminals] and offered in a list until
 * [onChooseTerminal]), and the one test ([testing] while it runs) of both the terminal and the Checkout API, which
 * first saves the API key when one is [keyTyped].
 */
@Composable
private fun ColumnScope.CloudTerminalStep(
    number: Int,
    poiId: String,
    setup: TerminalSetupActions,
    keyTyped: Boolean,
    testing: Boolean,
    onPoiId: (String) -> Unit,
    onFindTerminals: () -> Unit,
    onChooseTerminal: (poiId: String?) -> Unit,
    onTest: () -> Unit,
) {
    SetupStep(number, stringResource(R.string.settings_step_terminal))
    SettingTextField(
        label = stringResource(R.string.settings_poiid),
        value = poiId,
        onCommit = { onPoiId(it.trim()) },
        placeholder = stringResource(R.string.settings_poiid_hint),
        supporting = stringResource(R.string.settings_cloud_poiid_help),
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
        OutcomeMessage(setup.terminals, Modifier.testTag("terminalsResult"))
        TestButton(
            typed = keyTyped,
            save = R.string.settings_save_and_test,
            test = R.string.settings_test_connection,
            running = testing,
            onClick = onTest,
            tag = "testConnection",
        )
    }
    setup.connectedTerminals?.let { TerminalChoiceDialog(it, onChooseTerminal) }
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
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, PaymentsAppDestination.storeUrl(environment).toUri()))
            } catch (ignored: ActivityNotFoundException) {
            }
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
) {
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(setup.paymentsAppKeyStored) { if (setup.paymentsAppKeyStored) apiKey = "" }
    val boarded = installationId.isNotBlank()
    SetupStep(number, stringResource(R.string.settings_tap_to_pay))
    SettingNote(stringResource(R.string.settings_tap_to_pay_hint))
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
    )
    SettingNote(stringResource(R.string.settings_payments_app_key_hint))
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

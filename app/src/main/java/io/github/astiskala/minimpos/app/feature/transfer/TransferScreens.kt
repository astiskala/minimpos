package io.github.astiskala.minimpos.app.feature.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.settings.ConnectionDestination
import io.github.astiskala.minimpos.app.data.transfer.ReceivedTransfer
import io.github.astiskala.minimpos.app.data.transfer.TransferContents
import io.github.astiskala.minimpos.app.data.transfer.TransferExport
import io.github.astiskala.minimpos.app.feature.settings.SettingSwitch
import io.github.astiskala.minimpos.app.feature.settings.SettingsSections
import io.github.astiskala.minimpos.app.feature.text
import io.github.astiskala.minimpos.app.scan.ScanMode
import io.github.astiskala.minimpos.app.scan.ScannerView
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.BottomActions
import io.github.astiskala.minimpos.app.ui.components.Card
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.HistorySwitchDialog
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.QrImage
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.SharedKeyDialog
import io.github.astiskala.minimpos.app.ui.components.StatusBadge
import io.github.astiskala.minimpos.app.ui.components.StatusKind
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.catalogue.Catalogue
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * Shares this terminal's setup with another one: first the choice of catalogue, settings and secrets, then the QR
 * codes, which play in a loop while the screen stays on and can be paused and stepped through. With secrets, the
 * transfer code to type on the other terminal is shown above the codes. Back from the codes returns to the choice.
 */
@Composable
fun TransferExportScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: TransferExportViewModel = transferExportViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val export = state.export
    BackHandler(enabled = export != null) { vm.hide() }
    MiniScaffold(
        title = stringResource(R.string.transfer_export),
        onBack = { if (export != null) vm.hide() else navigator.back() },
        modifier = modifier,
        bottomBar = {
            if (export == null) {
                BottomActions {
                    PrimaryButton(
                        stringResource(R.string.transfer_show_codes),
                        vm::show,
                        enabled = state.canShow,
                        loading = state.loading,
                        modifier = Modifier.testTag("showCodes"),
                    )
                }
            }
        },
    ) { padding ->
        if (export == null) {
            ExportChoice(state, onChange = vm::setContents, modifier = Modifier.padding(padding))
        } else {
            ExportCodes(export, state.codes, modifier = Modifier.padding(padding))
        }
    }
}

/** What to share: the catalogue, the settings and (when any are set) the secrets. */
@Composable
private fun ExportChoice(
    state: ExportUiState,
    onChange: ((TransferContents) -> TransferContents) -> Unit,
    modifier: Modifier = Modifier,
) {
    val noSecrets = state.secretsAvailable.isEmpty()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp)) {
            Text(
                stringResource(R.string.transfer_choose_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(LocalDimens.current.screenPadding),
            )
            SettingSwitch(
                stringResource(R.string.transfer_part_catalogue),
                state.contents.catalogue,
                { checked -> onChange { it.copy(catalogue = checked) } },
                subtitle = stringResource(R.string.transfer_part_catalogue_hint),
                tag = "shareCatalogue",
            )
            SettingSwitch(
                stringResource(R.string.transfer_part_settings),
                state.contents.settings,
                { checked -> onChange { it.copy(settings = checked) } },
                subtitle = stringResource(R.string.transfer_part_settings_hint),
                tag = "shareSettings",
            )
            SettingSwitch(
                stringResource(R.string.transfer_part_secrets),
                state.sharesSecrets,
                { checked -> onChange { it.copy(secrets = checked) } },
                subtitle = stringResource(if (noSecrets) R.string.transfer_part_secrets_none else R.string.transfer_part_secrets_hint),
                tag = "shareSecrets",
                enabled = !noSecrets,
            )
        }
    }
}

/** The transfer code (when there are secrets), the QR codes in a loop with their controls, and what they hold. */
@Composable
private fun ExportCodes(
    export: TransferExport,
    codes: List<String>,
    modifier: Modifier = Modifier,
) {
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    KeepScreenOn()
    LaunchedEffect(codes.size, playing) {
        while (playing && codes.size > 1) {
            delay(ADVANCE_MILLIS)
            index = (index + 1) % codes.size
        }
    }
    val dimens = LocalDimens.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Text(
            stringResource(R.string.transfer_export_hint),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TransferCodeCard(export.code, Modifier.widthIn(max = 420.dp))
        val code = codes[index.coerceIn(0, codes.lastIndex)]
        QrImage(code, Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("exportQr"))
        if (codes.size > 1) {
            CodeControls(
                index = index,
                count = codes.size,
                playing = playing,
                onStep = { step ->
                    playing = false
                    index = (index + step + codes.size) % codes.size
                },
                onTogglePlay = { playing = !playing },
            )
        }
        TransferSummary(export.catalogue, export.settings, secretNames(export.secrets), Modifier.widthIn(max = 420.dp))
    }
}

/** The transfer code in large type, with what it is for. */
@Composable
private fun TransferCodeCard(
    code: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Text(stringResource(R.string.transfer_code_title), style = MaterialTheme.typography.labelLarge)
        Text(
            code,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.testTag("transferCode"),
        )
        Text(
            stringResource(R.string.transfer_code_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun transferExportViewModel(): TransferExportViewModel {
    val container = LocalAppContainer.current
    val currency = container.currency().code
    return viewModel { TransferExportViewModel(container.setupTransfer, currency) }
}

@Composable
private fun transferImportViewModel(): TransferImportViewModel {
    val container = LocalAppContainer.current
    val currency = container.currency().code
    return viewModel { TransferImportViewModel(container.setupTransfer, currency, container.setupImport) }
}

/**
 * Scans another terminal's transfer codes in any order, then imports them: the catalogue merged or replacing this
 * one, the settings, and with the transfer code the secrets. It warns when the catalogue's currency differs from the
 * one this terminal will use.
 */
@Composable
fun TransferImportScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: TransferImportViewModel = transferImportViewModel(),
) {
    val currency = LocalAppContainer.current.currency().code
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmReplace by remember { mutableStateOf(false) }
    BackHandler(enabled = state == ImportUiState.Importing) {}
    MiniScaffold(
        title = stringResource(R.string.transfer_import),
        onBack = { if (state != ImportUiState.Importing) navigator.back() },
        modifier = modifier,
        bottomBar = {
            ImportBottomBar(
                state,
                onImport = { if (it) confirmReplace = true else vm.import() },
                onBoard = vm::setUpTapToPay,
                onRetry = vm::retryRecovery,
                onDone = {
                    val result = (state as? ImportUiState.Done)?.result
                    if (result?.connection == true) {
                        navigator.replace(
                            Route.SettingsSection(SettingsSections.TERMINAL, helperSetup = true),
                        )
                    } else {
                        navigator.back()
                        if (navigator.current == Route.Onboarding) navigator.home()
                    }
                },
            )
        },
    ) { padding ->
        val callbacks = remember(vm) { ImportContentEvents(vm::onCode, vm::setMode, vm::setCode, vm::restart) }
        ImportContent(state, currency, callbacks, Modifier.padding(padding))
    }
    ImportSelectionDialogs(state as? ImportUiState.Ready, vm::chooseTerminal, vm::chooseBusiness, vm::skipBusinessDetails)
    (state as? ImportUiState.Ready)?.historySwitch?.let {
        HistorySwitchDialog(it.unfinished, { vm.reviewSetup(true) }, { vm.reviewSetup(false) })
    }
    (state as? ImportUiState.Ready)?.sharedKeyOffer?.let {
        SharedKeyDialog(it, { vm.reviewSetup(true) }, { vm.reviewSetup(false) })
    }
    if (confirmReplace) {
        ConfirmDialog(
            title = stringResource(R.string.transfer_replace),
            message = stringResource(R.string.transfer_replace_confirm),
            confirmLabel = stringResource(R.string.transfer_replace),
            destructive = true,
            onConfirm = {
                confirmReplace = false
                if ((state as? ImportUiState.Ready)?.boardingRequired == true) vm.setUpTapToPay() else vm.import()
            },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun ImportSelectionDialogs(
    ready: ImportUiState.Ready?,
    onTerminal: (String?) -> Unit,
    onBusiness: (String?) -> Unit,
    onSkip: () -> Unit,
) {
    ready?.terminalChoices?.let { ids ->
        ImportChoiceDialog(
            stringResource(R.string.settings_choose_terminal),
            ids.map { it to it },
            onTerminal,
            onDismiss = { onTerminal(null) },
        )
    }
    ready?.businessChoices?.let { stores ->
        val choices = stores.map { it.id to listOf(it.name, it.reference, it.id).filter(String::isNotBlank).joinToString("\n") }
        ImportChoiceDialog(
            stringResource(R.string.settings_business_choose),
            choices,
            onBusiness,
            onDismiss = { onBusiness(null) },
            onSkip = onSkip,
        )
    }
}

private class ImportContentEvents(
    val scan: (String) -> Unit,
    val mode: (ImportMode) -> Unit,
    val code: (String) -> Unit,
    val restart: () -> Unit,
)

@Composable
private fun ImportContent(
    state: ImportUiState,
    currency: String,
    events: ImportContentEvents,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ImportUiState.Scanning -> {
            ImportScanning(state, events.scan, modifier)
        }

        is ImportUiState.Ready -> {
            ImportReady(state, currency, events.mode, events.code, events.restart, modifier)
        }

        ImportUiState.Importing -> {
            Column(
                modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.transfer_verifying))
            }
        }

        is ImportUiState.RecoveryFailed -> {
            Column(modifier.padding(LocalDimens.current.screenPadding)) {
                ActionMessage(state.outcome.text(), isError = true)
                Text(stringResource(R.string.setup_transfer_pending))
            }
        }

        is ImportUiState.Done -> {
            ImportDone(state, modifier)
        }
    }
}

/**
 * Import when ready ([onImport] is told whether the catalogue replaces this one, which needs confirming), Done when
 * finished.
 */
@Composable
private fun ImportBottomBar(
    state: ImportUiState,
    onImport: (replaces: Boolean) -> Unit,
    onBoard: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    when (state) {
        is ImportUiState.Ready -> {
            BottomActions {
                PrimaryButton(
                    stringResource(
                        when {
                            state.keyPending -> R.string.settings_shared_key_check_again
                            state.boardingRequired -> R.string.settings_set_up_tap_to_pay
                            else -> R.string.transfer_verify
                        },
                    ),
                    {
                        val replaces = state.received.catalogue != null && state.mode == ImportMode.REPLACE
                        if (state.boardingRequired && !replaces) onBoard() else onImport(replaces)
                    },
                    enabled = state.received.accepts(state.code),
                    modifier = Modifier.testTag("import"),
                )
            }
        }

        is ImportUiState.Done -> {
            BottomActions {
                val label =
                    when {
                        state.result.connection -> R.string.transfer_review_setup
                        else -> R.string.action_done
                    }
                PrimaryButton(stringResource(label), onDone, modifier = Modifier.testTag("importFinished"))
            }
        }

        is ImportUiState.RecoveryFailed -> {
            BottomActions {
                PrimaryButton(stringResource(R.string.result_try_again), onRetry, modifier = Modifier.testTag("resumeImport"))
            }
        }

        is ImportUiState.Scanning, ImportUiState.Importing -> {}
    }
}

@Composable
private fun ImportChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { (id, label) ->
                    TextButton(onClick = { onChoose(id) }, modifier = Modifier.fillMaxWidth().testTag("importChoice_$id")) { Text(label) }
                }
            }
        },
        confirmButton = { onSkip?.let { TextButton(onClick = it) { Text(stringResource(R.string.transfer_skip_business)) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Previous, "code n of m", next and play/pause; stepping pauses the automatic advance. */
@Composable
internal fun CodeControls(
    index: Int,
    count: Int,
    playing: Boolean,
    onStep: (Int) -> Unit,
    onTogglePlay: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = stringResource(R.string.action_previous))
        }
        Text(stringResource(R.string.transfer_code_of, index + 1, count), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onStep(1) }) {
            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = stringResource(R.string.action_next))
        }
        OutlinedButton(
            onClick = onTogglePlay,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(stringResource(if (playing) R.string.action_pause else R.string.action_play))
        }
    }
}

/**
 * What a transfer holds: the catalogue's counts and currency, whether settings are included, and which secrets; and a
 * [connection] when it has one (only the setup helper's codes do).
 */
@Composable
private fun TransferSummary(
    catalogue: Catalogue?,
    settings: Boolean,
    secrets: String?,
    modifier: Modifier = Modifier,
    connection: Boolean = false,
) {
    val none = stringResource(R.string.transfer_not_included)
    Card(modifier) {
        if (catalogue == null) {
            LabeledValue(stringResource(R.string.transfer_part_catalogue), none)
        } else {
            LabeledValue(stringResource(R.string.transfer_products), catalogue.products.size.toString())
            LabeledValue(stringResource(R.string.transfer_categories), catalogue.categories.size.toString())
            LabeledValue(stringResource(R.string.transfer_tax_rates), catalogue.taxRates.size.toString())
            LabeledValue(stringResource(R.string.transfer_currency), catalogue.currencyCode)
        }
        LabeledValue(stringResource(R.string.transfer_part_settings), if (settings) stringResource(R.string.transfer_included) else none)
        TransferDetail(stringResource(R.string.transfer_part_secrets), secrets ?: none)
        if (connection) LabeledValue(stringResource(R.string.transfer_part_connection), stringResource(R.string.transfer_included))
    }
}

/** The names of [secrets], in a fixed order; null when there are none. */
@Composable
@ReadOnlyComposable
private fun secretNames(secrets: Set<Secret>): String? {
    val names =
        Secret.entries.filter { it in secrets }.map {
            stringResource(
                when (it) {
                    Secret.TERMINAL_PASSPHRASE -> R.string.transfer_secret_passphrase
                    Secret.ADYEN_API_KEY -> R.string.transfer_secret_api_key
                    Secret.SMTP_PASSWORD -> R.string.transfer_secret_smtp
                    Secret.PIN_VERIFIER -> R.string.transfer_secret_pin
                    Secret.MANAGER_PIN_VERIFIER -> R.string.manager_pin_title
                    Secret.PAYMENTS_APP_API_KEY -> R.string.transfer_secret_payments_app_key
                },
            )
        }
    return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** The camera, with how many codes of the set have been read and what was wrong with the last one. */
@Composable
private fun ImportScanning(
    state: ImportUiState.Scanning,
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScannerView(mode = ScanMode.QR, continuous = true, onResult = onCode, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.expected > 0) {
                LinearProgressIndicator(progress = {
                    state.received.toFloat() / state.expected
                }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.transfer_received, state.received, state.expected),
                    modifier = Modifier.testTag("importProgress"),
                )
            } else {
                Text(stringResource(R.string.transfer_import_hint))
            }
            state.error?.let {
                Text(
                    stringResource(if (it == ImportError.CORRUPT) R.string.transfer_corrupt else R.string.transfer_not_transfer),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * What was scanned, a warning when the catalogue's currency is not [currency], the choice of merging or replacing the
 * catalogue, what happens to the settings, and the transfer code for the secrets.
 */
@Composable
private fun ImportReady(
    state: ImportUiState.Ready,
    currency: String,
    onMode: (ImportMode) -> Unit,
    onCode: (String) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val received = state.received
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
            Text(stringResource(R.string.transfer_ready), style = MaterialTheme.typography.titleLarge)
            if (received.hasConnection) ConnectionPreview(received)
            val secrets = if (received.hasSecrets) stringResource(R.string.transfer_secrets_sealed) else null
            TransferSummary(received.catalogue, received.hasSettings, secrets, connection = received.hasConnection)
            received.catalogue?.let { catalogue ->
                if (!state.currencyMatches) {
                    Text(
                        stringResource(
                            R.string.transfer_currency_mismatch,
                            catalogue.currencyCode,
                            received.currencyAfterImport(currency),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CatalogueModeChoice(state.mode, onMode)
            }
            if (received.hasSettings) Note(stringResource(R.string.transfer_settings_note))
            if (received.hasConnection) Note(stringResource(R.string.transfer_connection_note))
            if (received.automatic) Note(stringResource(R.string.transfer_automatic_note))
            TransferCodeField(state.code, state.wrongCode, onCode)
            state.outcome?.let { ActionMessage(it.text(), isError = true) }
            if (state.incomplete) ActionMessage(stringResource(R.string.transfer_missing_fields), isError = true)
            if (state.boardingRequired) Text(stringResource(R.string.transfer_board_first))
            if (state.keyPending) Note(stringResource(R.string.transfer_key_not_imported))
            SecondaryButton(stringResource(R.string.transfer_scan_again), onScanAgain)
        }
    }
}

/** Non-secret destination and environment from the helper, reviewed before any saved settings or secrets change. */
@Composable
private fun ConnectionPreview(received: ReceivedTransfer) {
    val destination = received.connectionDestination
    val environment = received.connectionEnvironment
    Card(Modifier.fillMaxWidth().testTag("connectionPreview")) {
        TransferDetail(
            stringResource(R.string.settings_mode),
            stringResource(
                when (destination) {
                    ConnectionDestination.THIS_TERMINAL -> R.string.settings_mode_terminal
                    ConnectionDestination.NETWORK -> R.string.settings_mode_network
                    ConnectionDestination.CLOUD -> R.string.settings_mode_cloud
                    ConnectionDestination.TAP_TO_PAY -> R.string.settings_mode_payments_app
                    null -> R.string.transfer_unchanged
                },
            ),
        )
        TransferDetail(
            stringResource(R.string.settings_environment),
            stringResource(
                when {
                    environment == TerminalEnvironment.LIVE -> {
                        R.string.settings_env_live
                    }

                    environment == TerminalEnvironment.TEST -> {
                        R.string.settings_env_test
                    }

                    destination == ConnectionDestination.THIS_TERMINAL || destination == ConnectionDestination.TAP_TO_PAY -> {
                        R.string.transfer_environment_device
                    }

                    else -> {
                        R.string.transfer_unchanged
                    }
                },
            ),
        )
        if (environment == TerminalEnvironment.LIVE) {
            ActionMessage(stringResource(R.string.transfer_live_warning), isError = true, modifier = Modifier.testTag("importLiveWarning"))
        }
    }
}

/** Merge into this catalogue or replace it, as radio options with what each does. */
@Composable
private fun CatalogueModeChoice(
    selected: ImportMode,
    onSelect: (ImportMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        listOf(
            Triple(ImportMode.MERGE, R.string.transfer_merge, R.string.transfer_merge_hint),
            Triple(ImportMode.REPLACE, R.string.transfer_replace, R.string.transfer_replace_hint),
        ).forEach { (mode, title, hint) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == selected, onClick = { onSelect(mode) }, role = Role.RadioButton)
                    .padding(vertical = 8.dp)
                    .testTag(if (mode == ImportMode.MERGE) "merge" else "replace"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                    Note(stringResource(hint))
                }
            }
        }
    }
}

/** The transfer code the other terminal shows, which opens the secrets; [wrong] says the last one did not. */
@Composable
private fun TransferCodeField(
    code: String,
    wrong: Boolean,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value = code,
    onValueChange = onChange,
    label = { Text(stringResource(R.string.transfer_code_title)) },
    supportingText = {
        val hint = if (wrong) R.string.transfer_code_wrong else R.string.transfer_code_import_hint
        Text(stringResource(hint))
    },
    isError = wrong || (code.isNotEmpty() && !TransferSeal.isValidCode(code)),
    visualTransformation = TransferCodeTransformation,
    singleLine = true,
    keyboardOptions =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
    modifier = Modifier.fillMaxWidth().testTag("transferCodeInput"),
)

internal object TransferCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val grouped = text.text.chunked(4).joinToString("-")
        val offsets =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    val separators = (offset - 1).coerceAtLeast(0) / 4
                    return (offset + separators).coerceAtMost(grouped.length)
                }

                override fun transformedToOriginal(offset: Int): Int = (offset - offset / 5).coerceAtMost(text.length)
            }
        return TransformedText(AnnotatedString(grouped), offsets)
    }
}

/** Small secondary text. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What the import added and updated, under a success badge like the other results. */
@Composable
private fun ImportDone(
    state: ImportUiState.Done,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val result = state.result
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        StatusBadge(StatusKind.SUCCESS)
        Text(
            stringResource(R.string.transfer_done),
            style = dimens.outcomeStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("importDone"),
        )
        Card(Modifier.widthIn(max = 560.dp)) {
            result.catalogue?.let { summary ->
                LabeledValue(stringResource(R.string.transfer_added), summary.productsAdded.toString())
                LabeledValue(stringResource(R.string.transfer_updated), summary.productsUpdated.toString())
                LabeledValue(stringResource(R.string.transfer_new_categories), summary.categoriesAdded.toString())
                LabeledValue(stringResource(R.string.transfer_new_tax_rates), summary.taxRatesAdded.toString())
            }
            if (result.settings) {
                LabeledValue(
                    stringResource(R.string.transfer_part_settings),
                    stringResource(R.string.transfer_imported),
                )
            }
            if (result.connection) {
                LabeledValue(
                    stringResource(R.string.transfer_part_connection),
                    stringResource(R.string.transfer_imported),
                )
            }
            val secrets = secretNames(result.secrets)
            secrets?.let { TransferDetail(stringResource(R.string.transfer_part_secrets), it) }
        }
        if (result.businessWarning) ActionMessage(stringResource(R.string.transfer_business_failed), isError = true)
        if (result.paymentsAppChecked) Note(stringResource(R.string.transfer_tap_checked))
    }
}

@Composable
private fun TransferDetail(
    label: String,
    value: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private const val ADVANCE_MILLIS = 1_800L

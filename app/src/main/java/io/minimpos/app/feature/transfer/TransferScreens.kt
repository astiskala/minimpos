package io.minimpos.app.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.R
import io.minimpos.app.data.repo.ImportMode
import io.minimpos.app.data.repo.ImportSummary
import io.minimpos.app.scan.ScanMode
import io.minimpos.app.scan.ScannerView
import io.minimpos.app.ui.components.BottomActions
import io.minimpos.app.ui.components.Card
import io.minimpos.app.ui.components.ConfirmDialog
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.QrImage
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.StatusBadge
import io.minimpos.app.ui.components.StatusKind
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.core.catalogue.Catalogue
import kotlinx.coroutines.delay

/**
 * Shows the catalogue as a series of QR codes that play in a loop for another terminal to scan; the screen stays
 * on meanwhile and the codes can be paused and stepped through.
 */
@Composable
fun CatalogueExportScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: CatalogueExportViewModel = catalogueExportViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    KeepScreenOn()
    LaunchedEffect(state.codes.size, playing) {
        while (playing && state.codes.size > 1) {
            delay(ADVANCE_MILLIS)
            index = (index + 1) % state.codes.size
        }
    }
    MiniScaffold(title = stringResource(R.string.transfer_export), onBack = navigator::back, modifier = modifier) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@MiniScaffold
        }
        val catalogue = state.catalogue ?: return@MiniScaffold
        val dimens = LocalDimens.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
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
            val code = state.codes[index.coerceIn(0, state.codes.lastIndex)]
            QrImage(code, Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("exportQr"))
            if (state.codes.size > 1) {
                CodeControls(
                    index = index,
                    count = state.codes.size,
                    playing = playing,
                    onStep = { step ->
                        playing = false
                        index = (index + step + state.codes.size) % state.codes.size
                    },
                    onTogglePlay = { playing = !playing },
                )
            }
            CatalogueSummary(catalogue, Modifier.widthIn(max = 420.dp))
        }
    }
}

@Composable
private fun catalogueExportViewModel(): CatalogueExportViewModel {
    val container = LocalAppContainer.current
    val currency = container.currency().code
    return viewModel { CatalogueExportViewModel(container.catalog, currency) }
}

@Composable
private fun catalogueImportViewModel(): CatalogueImportViewModel {
    val container = LocalAppContainer.current
    val currency = container.currency().code
    return viewModel { CatalogueImportViewModel(container.catalog, currency) }
}

/**
 * Scans another terminal's catalogue codes in any order, then replaces or merges the catalogue on this one. It
 * warns when the catalogue's currency differs from this device's.
 */
@Composable
fun CatalogueImportScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: CatalogueImportViewModel = catalogueImportViewModel(),
) {
    val currency = LocalAppContainer.current.currency().code
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmReplace by remember { mutableStateOf(false) }
    MiniScaffold(
        title = stringResource(R.string.transfer_import),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            if (state is ImportUiState.Done) {
                BottomActions {
                    PrimaryButton(stringResource(R.string.action_done), navigator::back, modifier = Modifier.testTag("importFinished"))
                }
            }
        },
    ) { padding ->
        when (val current = state) {
            is ImportUiState.Scanning -> {
                ImportScanning(current, onCode = vm::onCode, modifier = Modifier.padding(padding))
            }

            is ImportUiState.Ready -> {
                ImportReady(
                    state = current,
                    currency = currency,
                    onMerge = { vm.import(ImportMode.MERGE) },
                    onReplace = { confirmReplace = true },
                    onScanAgain = vm::restart,
                    modifier = Modifier.padding(padding),
                )
            }

            ImportUiState.Importing -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is ImportUiState.Done -> {
                ImportDone(current.summary, modifier = Modifier.padding(padding))
            }
        }
    }
    if (confirmReplace) {
        ConfirmDialog(
            title = stringResource(R.string.transfer_replace),
            message = stringResource(R.string.transfer_replace_confirm),
            confirmLabel = stringResource(R.string.transfer_replace),
            destructive = true,
            onConfirm = {
                confirmReplace = false
                vm.import(ImportMode.REPLACE)
            },
            onDismiss = { confirmReplace = false },
        )
    }
}

/** Previous, "code n of m", next and play/pause; stepping pauses the automatic advance. */
@Composable
private fun CodeControls(
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
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
            )
        }
    }
}

/** What a catalogue holds: its counts and currency. */
@Composable
private fun CatalogueSummary(
    catalogue: Catalogue,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        LabeledValue(stringResource(R.string.transfer_products), catalogue.products.size.toString())
        LabeledValue(stringResource(R.string.transfer_categories), catalogue.categories.size.toString())
        LabeledValue(stringResource(R.string.transfer_tax_rates), catalogue.taxRates.size.toString())
        LabeledValue(stringResource(R.string.transfer_currency), catalogue.currencyCode)
    }
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
                    stringResource(if (it == ImportError.CORRUPT) R.string.transfer_corrupt else R.string.transfer_not_catalogue),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The scanned catalogue, a warning when its currency is not [currency], and merging or replacing. */
@Composable
private fun ImportReady(
    state: ImportUiState.Ready,
    currency: String,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
            Text(stringResource(R.string.transfer_ready), style = MaterialTheme.typography.titleLarge)
            CatalogueSummary(state.catalogue)
            if (!state.currencyMatches) {
                Text(
                    stringResource(R.string.transfer_currency_mismatch, state.catalogue.currencyCode, currency),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PrimaryButton(stringResource(R.string.transfer_merge), onMerge, modifier = Modifier.testTag("merge"))
            Text(
                stringResource(R.string.transfer_merge_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(stringResource(R.string.transfer_replace), onReplace, modifier = Modifier.testTag("replace"))
            Text(
                stringResource(R.string.transfer_replace_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(stringResource(R.string.transfer_scan_again), onScanAgain)
        }
    }
}

/** What the import added and updated, under a success badge like the other results. */
@Composable
private fun ImportDone(
    summary: ImportSummary,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
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
            LabeledValue(stringResource(R.string.transfer_added), summary.productsAdded.toString())
            LabeledValue(stringResource(R.string.transfer_updated), summary.productsUpdated.toString())
            LabeledValue(stringResource(R.string.transfer_new_categories), summary.categoriesAdded.toString())
            LabeledValue(stringResource(R.string.transfer_new_tax_rates), summary.taxRatesAdded.toString())
        }
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

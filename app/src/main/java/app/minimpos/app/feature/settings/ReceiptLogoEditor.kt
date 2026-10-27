package app.minimpos.app.feature.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.settings.decodeLogo
import app.minimpos.app.feature.persisting
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ReceiptLogoPreview
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.logo.ArgbImage
import app.minimpos.core.logo.LogoMode
import app.minimpos.core.logo.LogoOptions
import app.minimpos.core.logo.LogoResult
import app.minimpos.core.logo.LogoSize
import app.minimpos.core.logo.MonoBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receipt logo selection and the source-lifetime editor, inside the existing admin-protected receipt settings. */
@Composable
internal fun ColumnScope.ReceiptLogoSettings(cpu: CoroutineDispatcher = Dispatchers.Default) {
    val container = LocalAppContainer.current
    val resolver = LocalContext.current.contentResolver
    val logo by container.receiptLogo.logo.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<ArgbImage?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var decoding by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    decoding = true
                    problem = null
                    source = withContext(cpu) { decodeLogo(resolver, uri) }
                    if (source == null) problem = R.string.receipt_logo_unreadable
                    decoding = false
                }
            }
        }
    SectionHeader(stringResource(R.string.receipt_logo))
    logo?.let { ReceiptLogoPreview(it, Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) }
    ReceiptLogoActions(
        logo,
        decoding,
        problem,
        onChoose = {
            try {
                picker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            } catch (_: ActivityNotFoundException) {
                problem = R.string.receipt_logo_no_picker
            }
        },
        onRemove = { scope.launch { persisting { container.receiptLogo.replace(null) } } },
    )
    source?.let { ReceiptLogoEditorScreen(it, cpu, onClose = { source = null }) }
}

@Composable
private fun ReceiptLogoActions(
    logo: MonoBitmap?,
    decoding: Boolean,
    problem: Int?,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    SettingActions {
        if (logo == null) Description(stringResource(R.string.receipt_logo_none))
        SecondaryButton(
            stringResource(if (logo == null) R.string.receipt_logo_choose else R.string.receipt_logo_change),
            onChoose,
            loading = decoding,
            icon = Icons.Default.Image,
            modifier = Modifier.testTag("chooseReceiptLogo"),
        )
        if (logo != null) {
            ConfirmedRemoval(
                text = stringResource(R.string.receipt_logo_remove),
                confirmTitle = stringResource(R.string.receipt_logo_remove),
                confirmMessage = stringResource(R.string.receipt_logo_remove),
                onConfirm = onRemove,
                modifier = Modifier.testTag("removeReceiptLogo"),
            )
        }
        problem?.let { Notice(stringResource(it), kind = NoticeKind.WARNING) }
    }
}

@Composable
private fun receiptLogoEditorViewModel(
    source: ArgbImage,
    cpu: CoroutineDispatcher,
): ReceiptLogoEditorViewModel {
    val store = LocalAppContainer.current.receiptLogo
    val owner =
        remember(source) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return viewModel(viewModelStoreOwner = owner) { ReceiptLogoEditorViewModel(source, store, cpu) }
}

@Composable
private fun ReceiptLogoEditorScreen(
    source: ArgbImage,
    cpu: CoroutineDispatcher,
    onClose: () -> Unit,
    editor: ReceiptLogoEditorViewModel = receiptLogoEditorViewModel(source, cpu),
) {
    val scope = rememberCoroutineScope()
    val state by editor.state.collectAsStateWithLifecycle()
    var saving by remember(source) { mutableStateOf(false) }
    val onCancel = { if (!saving) onClose() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReceiptLogoEditor(
            state,
            editor::update,
            {
                saving = true
                scope.launch {
                    try {
                        editor.save()
                        onClose()
                    } finally {
                        saving = false
                    }
                }
            },
            onCancel,
            saving,
        )
    }
}

/** Scrollable editor with a pinned Save action at AMS1 size; callbacks own conversion and durable writes. */
@Composable
internal fun ReceiptLogoEditor(
    state: LogoEditorState,
    onUpdate: (LogoOptions) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saving: Boolean = false,
) {
    MiniScaffold(
        stringResource(R.string.receipt_logo),
        onCancel,
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomActions {
                PrimaryButton(
                    stringResource(R.string.action_save),
                    onSave,
                    enabled = state.canSave,
                    loading = saving,
                    modifier = Modifier.testTag("saveReceiptLogo"),
                )
                SecondaryButton(stringResource(R.string.action_cancel), onCancel, enabled = !saving)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val result = state.result
            if (result is LogoResult.Converted) ReceiptLogoPreview(result.bitmap)
            if (result == LogoResult.Blank) Notice(stringResource(R.string.receipt_logo_blank), kind = NoticeKind.WARNING, inset = true)
            LogoControls(state.options, result as? LogoResult.Converted, onUpdate)
        }
    }
}

@Composable
private fun ColumnScope.LogoControls(
    options: LogoOptions,
    result: LogoResult.Converted?,
    onUpdate: (LogoOptions) -> Unit,
) {
    SettingChoice(
        stringResource(R.string.receipt_logo_style),
        listOf(
            LogoMode.AUTO to stringResource(R.string.receipt_logo_auto),
            LogoMode.MARK to stringResource(R.string.receipt_logo_mark),
            LogoMode.PHOTO to stringResource(R.string.receipt_logo_photo),
        ),
        options.mode,
        { onUpdate(options.copy(mode = it, threshold = null)) },
        subtitle =
            if (options.mode == LogoMode.AUTO && result != null) {
                stringResource(if (result.mode == LogoMode.PHOTO) R.string.receipt_logo_photo else R.string.receipt_logo_mark)
            } else {
                null
            },
        tag = "receiptLogoStyle",
    )
    Column(Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) {
        // The thumb follows the finger locally; the preview converts once, when the drag ends.
        var dragged by remember { mutableStateOf<Float?>(null) }
        Text(stringResource(R.string.receipt_logo_darkness))
        Slider(
            dragged ?: (options.threshold ?: 127).toFloat(),
            { dragged = it },
            onValueChangeFinished = {
                dragged?.let { onUpdate(options.copy(threshold = it.toInt())) }
                dragged = null
            },
            valueRange = 0f..255f,
            modifier = Modifier.testTag("receiptLogoDarkness"),
        )
    }
    SettingSwitch(stringResource(R.string.receipt_logo_invert), options.invert == true, { onUpdate(options.copy(invert = it)) })
    SettingSwitch(stringResource(R.string.receipt_logo_trim), options.trim, { onUpdate(options.copy(trim = it)) })
    SettingChoice(
        stringResource(R.string.receipt_logo_size),
        listOf(
            LogoSize.LARGE to stringResource(R.string.receipt_logo_large),
            LogoSize.SMALL to stringResource(R.string.receipt_logo_small),
        ),
        options.size,
        { onUpdate(options.copy(size = it)) },
        tag = "receiptLogoSize",
    )
}

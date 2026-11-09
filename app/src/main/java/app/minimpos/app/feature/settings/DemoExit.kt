package app.minimpos.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.DemoFeature
import app.minimpos.app.data.settings.DemoFeatures
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.TertiaryButton

/**
 * Explicit demo-to-Adyen handoff; navigation never changes destinations, deletes samples or replaces receipt text.
 * The review also offers to turn off or keep the demo's checkout options; only those choices change [settings], through
 * [onUpdate].
 */
@Composable
internal fun DemoExit(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpen: (String) -> Unit,
) {
    var review by rememberSaveable { mutableStateOf(false) }
    SettingActions {
        SecondaryButton(
            stringResource(R.string.demo_exit_title),
            { review = !review },
            icon = Icons.Default.PhonelinkSetup,
            modifier = Modifier.testTag("exitDemo"),
        )
    }
    if (review) {
        SectionHeader(stringResource(R.string.demo_exit_history))
        Notice(stringResource(R.string.demo_exit_history_hint), kind = NoticeKind.WARNING, inset = true)
        SettingNavRow(Icons.Default.Storage, stringResource(R.string.sample_data), stringResource(R.string.demo_exit_samples_hint), {
            onOpen(SettingsSections.DATA)
        }, tag = "reviewDemoSamples")
        SettingNavRow(Icons.Default.Receipt, stringResource(R.string.settings_receipts), stringResource(R.string.demo_exit_receipts_hint), {
            onOpen(SettingsSections.RECEIPTS)
        }, tag = "reviewDemoReceipts")
        DemoFeaturesReview(settings, onUpdate)
        SettingActions {
            SecondaryButton(
                stringResource(R.string.demo_exit_continue),
                { onOpen(SettingsSections.TERMINAL) },
                modifier = Modifier.testTag("continueDemoSetup"),
            )
            Description(stringResource(R.string.demo_exit_unchanged))
        }
    }
}

/**
 * The checkout options the first-run demo turned on that are still as it left them ([DemoFeatures.active]), with the
 * choice to turn them off before real payments (confirmed) or keep them; nothing once none are.
 */
@Composable
internal fun DemoFeaturesReview(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val active = DemoFeatures.active(settings)
    if (active.isEmpty()) return
    var confirm by rememberSaveable { mutableStateOf(false) }
    // One line per feature still at its demo value, below the explanation.
    val lines = listOf(stringResource(R.string.demo_features_hint)) + active.map { stringResource(it.label) }
    SectionHeader(stringResource(R.string.demo_features_title))
    Notice(
        lines.joinToString("\n"),
        Modifier.testTag("demoFeatures"),
        kind = NoticeKind.WARNING,
        inset = true,
    )
    SettingActions {
        SecondaryButton(
            stringResource(R.string.demo_features_turn_off),
            { confirm = true },
            icon = Icons.Default.ToggleOff,
            modifier = Modifier.testTag("turnOffDemoFeatures"),
        )
        TertiaryButton(
            stringResource(R.string.demo_features_keep),
            { onUpdate(DemoFeatures::keep) },
            Modifier.testTag("keepDemoFeatures"),
            icon = Icons.Default.Check,
        )
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.demo_features_turn_off),
            message = stringResource(R.string.demo_features_confirm),
            confirmLabel = stringResource(R.string.demo_features_turn_off_confirm),
            onConfirm = {
                confirm = false
                onUpdate(DemoFeatures::turnOff)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** The name of a demo feature as Settings and the capability hub call it. */
@get:StringRes
private val DemoFeature.label: Int
    get() =
        when (this) {
            DemoFeature.TERMINAL_TIPPING -> R.string.capability_terminal_tipping
            DemoFeature.GIVING -> R.string.capability_giving
            DemoFeature.SVS_GIFT_CARDS -> R.string.demo_feature_svs
        }

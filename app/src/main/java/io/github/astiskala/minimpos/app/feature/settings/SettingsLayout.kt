package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.TertiaryButton
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens

/**
 * The buttons (and their outcome messages) under a group of settings, at the fields' edges and spaced as the buttons
 * of every other screen, so no two of them touch.
 */
@Composable
fun SettingActions(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = dimens.spacing),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        content = content,
    )
}

/**
 * The heading of one step of a setup that is done in order, such as Settings › Terminal: its [number] in a circle,
 * then its [title]. Tagged `step_<number>` for tests.
 * [expanded] adds a disclosure indicator; null means this heading is not expandable.
 */
@Composable
fun SetupStep(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
) {
    val compact = LocalDimens.current.compact
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = 16.dp, end = 16.dp, top = if (compact) 16.dp else 24.dp, bottom = if (compact) 4.dp else 8.dp)
            .testTag("step_$number"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.settings_setup_hide else R.string.settings_setup_review),
            )
        }
    }
}

/**
 * Collapses supplied details during [guided] setup, with a non-secret [summary]. Supplied does not mean tested.
 * The initial expansion is remembered, so saving while editing cannot close fields or discard secret drafts.
 * [hasDraft] prevents an explicit collapse while a secret is unsaved. Missing details always remain expanded.
 */
@Composable
internal fun ColumnScope.SetupDetails(
    number: Int,
    title: String,
    guided: Boolean,
    supplied: Boolean,
    summary: String = stringResource(R.string.settings_setup_supplied),
    hasDraft: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(guided) { mutableStateOf(true) }
    val open = expanded || !supplied || hasDraft
    SetupStep(
        number,
        title,
        modifier =
            if (guided && supplied) {
                Modifier.clickable(enabled = !hasDraft) { expanded = !open }
            } else {
                Modifier
            },
        expanded = if (guided && supplied) open else null,
    )
    if (open) content() else SettingNote(summary, Modifier.testTag("step_${number}_summary"))
}

/**
 * A destructive [TertiaryButton] (removing something saved) that asks first: [onConfirm] runs only once the dialog
 * with [confirmTitle] and [confirmMessage] is confirmed with the button's own [text].
 */
@Composable
fun ConfirmedRemoval(
    text: String,
    confirmTitle: String,
    confirmMessage: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Default.Delete,
    enabled: Boolean = true,
) {
    var asking by remember { mutableStateOf(false) }
    TertiaryButton(text, { asking = true }, modifier, enabled = enabled, destructive = true, icon = icon)
    if (asking) {
        ConfirmDialog(
            title = confirmTitle,
            message = confirmMessage,
            confirmLabel = text,
            destructive = true,
            onConfirm = {
                asking = false
                onConfirm()
            },
            onDismiss = { asking = false },
        )
    }
}

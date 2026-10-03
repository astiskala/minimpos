package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun SetupStep(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
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
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
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
) {
    var asking by remember { mutableStateOf(false) }
    TertiaryButton(text, { asking = true }, modifier, destructive = true, icon = icon)
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

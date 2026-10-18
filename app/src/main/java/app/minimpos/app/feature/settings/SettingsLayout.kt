package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.theme.LocalDimens

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
        modifier.fillMaxWidth().padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = dimens.spacing),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        content = content,
    )
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
    var asking by rememberSaveable { mutableStateOf(false) }
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

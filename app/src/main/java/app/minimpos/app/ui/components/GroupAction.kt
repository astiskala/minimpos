package app.minimpos.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.theme.BorderWidth
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.MinTouchTarget
import app.minimpos.app.ui.theme.Spacing

/**
 * One action of an [ActionGroup].
 *
 * @property text The action's label.
 * @property icon The icon beside the label.
 * @property tag The action's test tag.
 * @property loading Whether the action is running: it shows a spinner instead of [icon] and cannot be pressed again.
 * @property onClick What the action does.
 */
class GroupAction(
    val text: String,
    val icon: ImageVector,
    val tag: String,
    val loading: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Related secondary actions, such as a receipt's print, email, share and show, presented as the rows of one bordered
 * group, so they do not outweigh a screen's outcome and main action and take no gaps between them. The [actions] keep
 * their order; each row is at least [MinTouchTarget] tall.
 */
@Composable
fun ActionGroup(
    actions: List<GroupAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val shape = MaterialTheme.shapes.medium
    Column(modifier.fillMaxWidth().clip(shape).border(BorderWidth, MaterialTheme.colorScheme.outlineVariant, shape)) {
        actions.forEachIndexed { index, action ->
            // Inset to the labels, so the icons read as one column.
            if (index > 0) {
                HorizontalDivider(
                    Modifier.padding(start = Spacing.Large + IconSize.Inline + Spacing.Medium),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val enabled = !action.loading
            val color = contentColor(enabled)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxOf(LocalDimens.current.secondaryButtonHeight, MinTouchTarget))
                    .testTag(action.tag)
                    .clickable(enabled = enabled, role = Role.Button, onClick = action.onClick)
                    .padding(horizontal = Spacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionIcon(action, color)
                Spacer(Modifier.width(Spacing.Medium))
                Text(action.text, style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}

/** The [action]'s icon, or a spinner while it runs, both in [color]. */
@Composable
private fun ActionIcon(
    action: GroupAction,
    color: Color,
) {
    if (action.loading) {
        CircularProgressIndicator(Modifier.size(IconSize.Inline), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
    } else {
        Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(IconSize.Inline))
    }
}

/** Navy like a secondary button's label, faded as Material fades disabled content. */
@Composable
@ReadOnlyComposable
private fun contentColor(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

/** Material's opacity for disabled content. */
private const val DISABLED_ALPHA = 0.38f

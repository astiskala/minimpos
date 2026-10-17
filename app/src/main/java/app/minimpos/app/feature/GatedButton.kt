package app.minimpos.app.feature

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.minimpos.app.data.db.Failure
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.SecondaryButton

/**
 * An action that cannot run until [blocked] is fixed: then the button is disabled and the reason, which says what to
 * do, shows directly under it ([BlockedReason]). Without a reason it is the ordinary [PrimaryButton] (when [primary])
 * or [SecondaryButton], disabled without a reason while input is incomplete ([enabled] false). Every action with a
 * precondition known before it is pressed uses this, so none can be pressed only to report what was already known.
 */
@Composable
fun ColumnScope.GatedButton(
    text: String,
    onClick: () -> Unit,
    blocked: Failure?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val active = enabled && blocked == null
    if (primary) {
        PrimaryButton(text, onClick, modifier, enabled = active, loading = loading, icon = icon)
    } else {
        SecondaryButton(text, onClick, modifier, enabled = active, loading = loading, icon = icon)
    }
    blocked?.let { BlockedReason(it.text()) }
}

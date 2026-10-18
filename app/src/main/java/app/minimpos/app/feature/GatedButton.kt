package app.minimpos.app.feature

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.minimpos.app.data.db.Failure
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate

/**
 * An action that cannot run until [blocked] is fixed: then the button is disabled and the reason, which says what to
 * do, shows directly under it ([BlockedReason]). Without a reason it is the ordinary [PrimaryButton] (when [primary])
 * or [SecondaryButton], disabled without a reason while input is incomplete ([enabled] false). Every action with a
 * precondition known before it is pressed uses this, so none can be pressed only to report what was already known.
 *
 * An action that reaches Adyen or a terminal ([requiresSetup]) goes through the [app.minimpos.app.ui.components.SetupGate]
 * first: while payments still need setting up it stays pressable and explains that instead, without a [blocked] reason
 * (which would only name a part of the same setup).
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
    requiresSetup: Boolean = false,
) {
    val gate = if (requiresSetup) currentSetupGate() else null
    val held = gate?.pending == true
    val active = enabled && (held || blocked == null)
    val click = gate?.let { { it.run(onClick) } } ?: onClick
    if (primary) {
        PrimaryButton(text, click, modifier, enabled = active, loading = loading, icon = icon)
    } else {
        SecondaryButton(text, click, modifier, enabled = active, loading = loading, icon = icon)
    }
    if (!held) blocked?.let { BlockedReason(it.text()) }
}

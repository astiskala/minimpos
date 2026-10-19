package app.minimpos.app.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.ui.components.ResultDialog

/** Shows a manual action's outcome in the shared result popup; progress and dismissed results show nothing. */
@Composable
fun OutcomeDialog(
    @StringRes title: Int,
    state: ActionState,
    tag: String,
    onDismiss: () -> Unit,
) {
    val outcome = state.outcome ?: return
    ResultDialog(stringResource(title), outcome.text(), state.isError, tag, onDismiss)
}

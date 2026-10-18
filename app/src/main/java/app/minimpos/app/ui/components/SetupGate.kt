package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.ui.theme.LocalDimens

/**
 * Holds back every action that reaches Adyen or a terminal while payments still need setting up
 * ([app.minimpos.app.terminal.TerminalState.needsSetup]). Such an action looks as usual; pressing it runs it through
 * [run], which then explains, in the one dialog [SetupGateHost] shows, that payments must be set up first, with the way
 * to Settings › Terminal ([openSetup]). Status checks of unfinished operations never go through the gate. One per
 * process, held by the container and followed by the host; main thread only, as its state is Compose state.
 */
@Stable
class SetupGate {
    /** Whether the explanation is showing. */
    var explaining by mutableStateOf(false)
        private set

    /** Whether actions are held back now, as the host last saw the terminal status; reading it follows changes. */
    var pending by mutableStateOf(false)
        private set

    private var onSetUp: () -> Unit = {}

    /** Runs [action] when payments are set up; otherwise explains what to do first and leaves it undone. */
    fun run(action: () -> Unit) {
        if (pending) explaining = true else action()
    }

    /** Closes the explanation and opens Settings › Terminal, where what is still missing is shown. */
    fun openSetup() {
        explaining = false
        onSetUp()
    }

    /** Closes the explanation without doing anything. */
    fun dismiss() {
        explaining = false
    }

    /** Takes whether payments [needsSetup] and how to open Settings › Terminal ([onSetUp]) from the host. */
    internal fun follow(
        needsSetup: Boolean,
        onSetUp: () -> Unit,
    ) {
        pending = needsSetup
        this.onSetUp = onSetUp
    }
}

/** The app's [SetupGate]; every screen asks this one, whose explanation [SetupGateHost] shows. */
@Composable
@ReadOnlyComposable
fun currentSetupGate(): SetupGate = LocalAppContainer.current.setupGate

/**
 * Keeps the [SetupGate] following the terminal status around [content], and shows its explanation while asked:
 * "Finish setting up payments" calls [onSetUp], which opens Settings › Terminal.
 */
@Composable
fun SetupGateHost(
    onSetUp: () -> Unit,
    content: @Composable () -> Unit,
) {
    val container = LocalAppContainer.current
    val terminal by container.terminalStatus.state
        .collectAsStateWithLifecycle()
    val gate = container.setupGate
    val needsSetup = terminal.needsSetup
    SideEffect { gate.follow(needsSetup, onSetUp) }
    content()
    if (gate.explaining) {
        ConfirmDialog(
            title = stringResource(R.string.setup_required_title),
            message = stringResource(R.string.setup_required_message),
            confirmLabel = stringResource(R.string.setup_required_action),
            onConfirm = gate::openSetup,
            onDismiss = gate::dismiss,
        )
    }
}

/**
 * Shown in place of content that only makes sense once payments are set up (such as Settings › Adyen capabilities):
 * the same generic explanation as the gate's dialog, and "Finish setting up payments". Tagged `setupRequired`.
 */
@Composable
fun SetupRequired(modifier: Modifier = Modifier) {
    val gate = currentSetupGate()
    val dimens = LocalDimens.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = dimens.spacing).testTag("setupRequired"),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Text(stringResource(R.string.setup_required_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.setup_required_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(
            stringResource(R.string.setup_required_action),
            gate::openSetup,
            icon = Icons.Default.Settings,
            modifier = Modifier.testTag("finishSetup"),
        )
    }
}

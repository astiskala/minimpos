package app.minimpos.app.feature.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.db.Failure
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.theme.LocalDimens
import java.time.LocalDate

/**
 * Reconciliation from Home: checks the terminal's totals against Mini mPOS (rechecking unresolved terminal operations
 * first), closes the terminal's period with Manager approval, and prints today's local report. Where the destination
 * cannot report totals (Tap to Pay) or prints nothing, its buttons say why.
 */
@Composable
fun ReconciliationScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = historyViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val terminal by LocalAppContainer.current.terminalStatus.state
        .collectAsStateWithLifecycle()
    var closeApproval by remember { mutableStateOf(false) }
    if (closeApproval) {
        return ManagerApproval(
            onApprove = {
                closeApproval = false
                vm.reconcileTerminal(closePeriod = true)
            },
            onCancel = { closeApproval = false },
        )
    }
    val totalsBlocked = terminal.totalsBlocked?.let(Failure::NotSetUp)
    val dimens = LocalDimens.current
    MiniScaffold(title = stringResource(R.string.home_reconciliation), onBack = navigator::back, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
        ) {
            Text(
                stringResource(R.string.reconciliation_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GatedButton(
                stringResource(R.string.history_reconcile),
                { vm.reconcileTerminal(closePeriod = false) },
                blocked = totalsBlocked,
                loading = state.reconcile.running,
                icon = Icons.Default.Sync,
                primary = true,
                modifier = Modifier.padding(top = dimens.spacing).testTag("reconcileTerminal"),
            )
            OutcomeMessage(state.reconcile)
            state.reconciliation?.let { report ->
                ReconciliationCard(report, state.reconcile.running, { closeApproval = true }, vm::dismissReconciliation)
            }
            GatedButton(
                stringResource(R.string.reconciliation_print_today),
                { vm.printDay(LocalDate.now()) },
                blocked = terminal.printBlocked?.let(Failure::NotSetUp),
                loading = state.print.running,
                icon = Icons.Default.Print,
                modifier = Modifier.padding(top = dimens.spacing).testTag("printToday"),
            )
            OutcomeMessage(state.print)
        }
    }
}

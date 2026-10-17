package app.minimpos.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.db.Failure
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.history.CurrencyReconciliation
import app.minimpos.app.history.TerminalReconciliationReport
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.currentLocale
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import java.time.LocalDate

/**
 * Reconciliation from Home, in the order of the end of a day: check the terminal's totals against Mini mPOS (rechecking
 * unresolved terminal payments first), close the terminal's period once they match (confirmed, with Manager approval),
 * and print today's local report. Each step explains itself; where the destination reports no totals (Tap to Pay) or
 * prints nothing, its button says why.
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
    var confirmClose by remember { mutableStateOf(false) }
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
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.reconciliation_close_confirm_title),
            message = stringResource(R.string.reconciliation_close_confirm_message),
            confirmLabel = stringResource(R.string.reconciliation_close_period),
            onConfirm = {
                confirmClose = false
                closeApproval = true
            },
            onDismiss = { confirmClose = false },
        )
    }
    val totalsBlocked = terminal.totalsBlocked?.let(Failure::NotSetUp)
    val report = state.reconciliation
    MiniScaffold(title = stringResource(R.string.home_reconciliation), onBack = navigator::back, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = LocalDimens.current.spacing),
        ) {
            Step(R.string.reconciliation_totals_header, R.string.reconciliation_check_hint) {
                GatedButton(
                    stringResource(R.string.reconciliation_check),
                    { vm.reconcileTerminal(closePeriod = false) },
                    blocked = totalsBlocked,
                    loading = state.reconcile.running,
                    icon = Icons.Default.Sync,
                    primary = report == null,
                    modifier = Modifier.testTag("reconcileTerminal"),
                )
                OutcomeMessage(state.reconcile)
                report?.let { ReconciliationResult(it) }
            }
            Step(R.string.reconciliation_end_header, R.string.reconciliation_close_hint) {
                GatedButton(
                    stringResource(R.string.reconciliation_close_period),
                    { confirmClose = true },
                    blocked = totalsBlocked,
                    enabled = !state.reconcile.running && report?.closedPeriod != true,
                    icon = Icons.Default.EventAvailable,
                    primary = report?.allMatch == true && !report.closedPeriod,
                    modifier = Modifier.testTag("closeReconciliationPeriod"),
                )
            }
            Step(R.string.reconciliation_report_header, R.string.reconciliation_report_hint) {
                GatedButton(
                    stringResource(R.string.reconciliation_print_today),
                    { vm.printDay(LocalDate.now()) },
                    blocked = terminal.printBlocked?.let(Failure::NotSetUp),
                    loading = state.print.running,
                    icon = Icons.Default.Print,
                    modifier = Modifier.testTag("printToday"),
                )
                OutcomeMessage(state.print)
            }
        }
    }
}

/** One step of the screen: its heading, what it does, then its [content], padded like the screen. */
@Composable
private fun Step(
    title: Int,
    hint: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionHeader(stringResource(title))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/**
 * The terminal's answer: its period, whether everything matches (and what to do when not), then Mini mPOS's and the
 * terminal's sales and refunds side by side for each currency, with any unresolved payments and offline warnings.
 */
@Composable
private fun ReconciliationResult(report: TerminalReconciliationReport) {
    val colors = LocalStatusColors.current
    Card(Modifier.fillMaxWidth().testTag("reconciliationCard")) {
        Text(
            stringResource(
                if (report.closedPeriod) R.string.reconciliation_closed_title else R.string.reconciliation_title,
                report.poiReconciliationId ?: "—",
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (report.allMatch) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (report.allMatch) colors.success else colors.warning,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (report.allMatch) R.string.reconciliation_matched else R.string.reconciliation_mismatch),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("reconciliationStatus"),
            )
        }
        if (!report.allMatch) {
            Text(
                stringResource(R.string.reconciliation_mismatch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TotalsTable(report.currencies)
        if (report.unresolvedCount > 0) Warning(stringResource(R.string.reconciliation_unresolved, report.unresolvedCount), colors.warning)
        if (report.offlineSaleCount > 0 || report.unconfirmedBatchCount > 0) {
            Warning(
                stringResource(R.string.reconciliation_offline_warning, report.offlineSaleCount, report.unconfirmedBatchCount),
                colors.warning,
            )
        }
    }
}

/** Mini mPOS and terminal columns, with a row each for sales and (when there are any) refunds per currency. */
@Composable
private fun TotalsTable(currencies: List<CurrencyReconciliation>) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        TableRow(
            "",
            stringResource(R.string.reconciliation_column_local),
            stringResource(R.string.reconciliation_column_terminal),
            header = true,
        )
        HorizontalDivider()
        currencies.forEach { row ->
            val money = MoneyFormatter(CurrencySpec.of(row.currency), locale)
            val sales = stringResource(R.string.reconciliation_sales)
            val refunds = stringResource(R.string.reconciliation_refunds)
            val label = { text: String -> if (currencies.size > 1) "$text (${row.currency})" else text }
            TableRow(
                label(sales),
                money.format(row.localSalesMinor),
                money.format(row.terminalSalesMinor),
                differs = row.localSalesMinor != row.terminalSalesMinor,
            )
            if (row.localRefundsMinor > 0 || row.terminalRefundsMinor > 0) {
                TableRow(
                    label(refunds),
                    money.format(row.localRefundsMinor),
                    money.format(row.terminalRefundsMinor),
                    differs = row.localRefundsMinor != row.terminalRefundsMinor,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    label: String,
    local: String,
    terminal: String,
    header: Boolean = false,
    differs: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val valueColor = if (differs) LocalStatusColors.current.warning else Color.Unspecified
    val weight = if (header || differs) FontWeight.Medium else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(local, style = style, color = valueColor, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(terminal, style = style, color = valueColor, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Warning(
    text: String,
    color: Color,
) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

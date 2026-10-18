package app.minimpos.app.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.minimpos.app.R
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.EmailReceiptDialog
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument

/** What the receipt buttons of [ReceiptActions] ask of one stored sale or refund; [TransactionActions] answers them. */
interface ReceiptEvents {
    /** Prints [copy] of the receipt. */
    fun print(copy: ReceiptCopy = ReceiptCopy.CUSTOMER)

    /** Emails the receipt to [to], a valid address. */
    fun email(to: String)

    /** Hands the receipt to Android's share sheet (phones and tablets only). */
    fun share()
}

/** When [ReceiptActions] offers the merchant copy of a sale. */
enum class MerchantCopy {
    /** Never: a refund or an unsigned link receipt has one copy only. */
    NONE,

    /** Once the customer copy has printed and the merchant copy is due, as on the result just after a payment. */
    WHEN_DUE,

    /** Always beside the customer copy, as on a sale's history detail. */
    ALWAYS,
}

/**
 * The receipt actions every result and detail screen offers, always in this order: Print receipt (with the merchant
 * copy as [merchantCopy] says), Email receipt (asking for the address, starting with [email]), Share receipt (on
 * phones and tablets) and Show/Hide receipt with the receipt itself. Each appears only while [state] offers it;
 * printing reaches the terminal, so it goes through the setup gate. Outcomes show under their buttons.
 */
@Composable
fun ColumnScope.ReceiptActions(
    state: TransactionActionsState,
    events: ReceiptEvents,
    merchantCopy: MerchantCopy,
    modifier: Modifier = Modifier,
    email: String = "",
) {
    val gate = currentSetupGate()
    var askEmail by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(false) }
    if (state.canPrint) {
        SecondaryButton(
            stringResource(R.string.result_print),
            { gate.run { events.print(ReceiptCopy.CUSTOMER) } },
            loading = state.print.running,
            icon = Icons.Default.Print,
            modifier = Modifier.testTag("print"),
        )
        val due = merchantCopy == MerchantCopy.WHEN_DUE && state.merchantCopyPending
        if (due) Text(stringResource(R.string.result_tear_off), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (due || merchantCopy == MerchantCopy.ALWAYS) {
            SecondaryButton(
                stringResource(R.string.result_print_merchant),
                { gate.run { events.print(ReceiptCopy.MERCHANT) } },
                loading = state.print.running,
                icon = Icons.Default.Print,
                modifier = Modifier.testTag("printMerchant"),
            )
        }
        OutcomeMessage(state.print)
    }
    if (state.canEmail) {
        SecondaryButton(
            stringResource(R.string.result_email),
            { askEmail = true },
            loading = state.email.running,
            icon = Icons.Default.Email,
            modifier = Modifier.testTag("emailReceipt"),
        )
        OutcomeMessage(state.email)
    }
    if (state.canShare) {
        SecondaryButton(
            stringResource(R.string.result_share),
            events::share,
            icon = Icons.Default.Share,
            modifier = Modifier.testTag("shareReceipt"),
        )
    }
    ReceiptToggle(state.receipt?.takeIf { shown }, { shown = !shown }, modifier)
    if (askEmail) {
        EmailReceiptDialog(
            onSend = {
                askEmail = false
                events.email(it)
            },
            onDismiss = { askEmail = false },
            initial = email,
        )
    }
}

/**
 * "Show receipt" / "Hide receipt" ([modifier] applies to this button) and, while shown, the [receipt] itself (null
 * while hidden).
 */
@Composable
fun ColumnScope.ReceiptToggle(
    receipt: ReceiptDocument?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryButton(
        stringResource(if (receipt != null) R.string.result_hide_receipt else R.string.result_show_receipt),
        onToggle,
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        modifier = modifier.testTag("toggleReceipt"),
    )
    receipt?.let { ReceiptPreview(it, Modifier.align(Alignment.CenterHorizontally)) }
}

/**
 * "Check again" for a transaction whose outcome is unknown, asked the way it was sent, and why the last check could
 * not establish it ([TransactionActionsState.stillUnknown]). Never held back by the setup gate: an unfinished
 * operation must stay recoverable. Tagged `checkAgain`.
 */
@Composable
fun ColumnScope.StatusCheck(
    state: TransactionActionsState,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryButton(
        stringResource(R.string.result_check_again),
        onRecheck,
        loading = state.rechecking,
        icon = Icons.Default.Refresh,
        modifier = modifier.testTag("checkAgain"),
    )
    state.stillUnknown?.let { Text(stillUnconfirmed(it), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
}

/**
 * The next step a result screen offers beside Home, such as "New sale" or "Try again".
 *
 * @property text The button's label.
 * @property tag The button's test tag.
 * @property onClick What it does.
 */
class ResultAction(
    val text: String,
    val tag: String,
    val onClick: () -> Unit,
)

/**
 * The bottom bar of every result screen: Home ([onHome]) beside the [next] step, as tall as each other, or Home alone
 * when there is none. History details have no bottom bar.
 */
@Composable
fun ResultBar(
    onHome: () -> Unit,
    next: ResultAction?,
    modifier: Modifier = Modifier,
) {
    BottomActions(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                stringResource(R.string.action_home),
                onHome,
                icon = Icons.Default.Home,
                modifier = Modifier.weight(1f).heightIn(min = LocalDimens.current.buttonHeight).testTag("home"),
            )
            next?.let { PrimaryButton(it.text, it.onClick, modifier = Modifier.weight(1f).testTag(it.tag)) }
        }
    }
}

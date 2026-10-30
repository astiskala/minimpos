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
import app.minimpos.app.feature.lock.rememberManagerAction
import app.minimpos.app.ui.components.ActionGroup
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.EmailReceiptDialog
import app.minimpos.app.ui.components.GroupAction
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.components.ResultDialog
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.receipt.ReceiptCopy

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
 * phones and tablets) and Show/Hide receipt with the receipt itself, as one [ActionGroup] ([modifier] applies to it).
 * Each appears only while [state] offers it; printing reaches the terminal, so it goes through the setup gate.
 * Outcomes show under the group.
 */
@Composable
fun ColumnScope.ReceiptActions(
    state: TransactionActionsState,
    events: ReceiptEvents,
    merchantCopy: MerchantCopy,
    modifier: Modifier = Modifier,
    email: String = "",
) {
    val approve = rememberManagerAction()
    var askEmail by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(false) }
    val due = state.canPrint && merchantCopy == MerchantCopy.WHEN_DUE && state.merchantCopyPending
    val receipt = state.receipt?.takeIf { shown }
    val actions =
        receiptActions(state, events, merchantCopy, due, receipt != null, approve, onEmail = { askEmail = true }, onToggle = {
            shown =
                !shown
        })
    // Tear off the customer copy before printing the merchant copy.
    if (due) Text(stringResource(R.string.result_tear_off), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    ActionGroup(actions, modifier)
    if (state.canPrint) OutcomeMessage(state.print)
    if (state.canEmail) OutcomeMessage(state.email)
    receipt?.let { ReceiptPreview(it, Modifier.align(Alignment.CenterHorizontally)) }
    if (askEmail) {
        EmailReceiptDialog(
            onSend = {
                askEmail = false
                val to = it
                approve(setOf(ManagerAction.RECEIPT)) { events.email(to) }
            },
            onDismiss = { askEmail = false },
            initial = email,
        )
    }
}

/**
 * The [ActionGroup] entries of [ReceiptActions], in their fixed order: print (and the merchant copy when [due] or
 * always offered), email ([onEmail] asks for the address), share and show/hide receipt ([shown] picks the label,
 * [onToggle] switches it). Each asks [approve] for the receipt's Manager permission; printing also passes the setup
 * gate.
 */
@Composable
private fun receiptActions(
    state: TransactionActionsState,
    events: ReceiptEvents,
    merchantCopy: MerchantCopy,
    due: Boolean,
    shown: Boolean,
    approve: (Set<ManagerAction>, () -> Unit) -> Unit,
    onEmail: () -> Unit,
    onToggle: () -> Unit,
): List<GroupAction> {
    val gate = currentSetupGate()
    val printLabel = stringResource(R.string.result_print)
    val merchantLabel = stringResource(R.string.result_print_merchant)
    val emailLabel = stringResource(R.string.result_email)
    val shareLabel = stringResource(R.string.result_share)
    val toggleLabel = stringResource(if (shown) R.string.result_hide_receipt else R.string.result_show_receipt)
    val print = { copy: ReceiptCopy -> approve(setOf(ManagerAction.RECEIPT)) { gate.run { events.print(copy) } } }
    return buildList {
        if (state.canPrint) {
            add(GroupAction(printLabel, Icons.Default.Print, "print", loading = state.print.running) { print(ReceiptCopy.CUSTOMER) })
            if (due || merchantCopy == MerchantCopy.ALWAYS) {
                add(
                    GroupAction(merchantLabel, Icons.Default.Print, "printMerchant", loading = state.print.running) {
                        print(ReceiptCopy.MERCHANT)
                    },
                )
            }
        }
        if (state.canEmail) {
            add(
                GroupAction(emailLabel, Icons.Default.Email, "emailReceipt", loading = state.email.running) {
                    approve(setOf(ManagerAction.RECEIPT), onEmail)
                },
            )
        }
        if (state.canShare) {
            add(GroupAction(shareLabel, Icons.Default.Share, "shareReceipt") { approve(setOf(ManagerAction.RECEIPT), events::share) })
        }
        add(GroupAction(toggleLabel, Icons.AutoMirrored.Filled.ReceiptLong, "toggleReceipt", onClick = onToggle))
    }
}

/**
 * "Check again" for an unknown outcome, asked the way it was sent. An unresolved answer opens a dismissible result
 * popup ([onDismiss]); a resolved answer updates the transaction's existing result screen. Never setup-gated:
 * unfinished operations must stay recoverable. Tagged `checkAgain`.
 */
@Composable
fun ColumnScope.StatusCheck(
    state: TransactionActionsState,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecondaryButton(
        stringResource(R.string.result_check_again),
        onRecheck,
        loading = state.rechecking,
        icon = Icons.Default.Refresh,
        modifier = modifier.testTag("checkAgain"),
    )
    state.stillUnknown?.let {
        ResultDialog(stringResource(R.string.result_check_again), stillUnconfirmed(it), true, "statusCheckResult", onDismiss)
    }
}

/**
 * The next step a result screen offers beside Home, such as "New sale" or "Try again".
 *
 * @property text The button's label.
 * @property tag The button's test tag.
 * @property enabled Whether its prerequisites are ready.
 * @property onClick What it does.
 */
class ResultAction(
    val text: String,
    val tag: String,
    val enabled: Boolean = true,
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
            next?.let { PrimaryButton(it.text, it.onClick, enabled = it.enabled, modifier = Modifier.weight(1f).testTag(it.tag)) }
        }
    }
}

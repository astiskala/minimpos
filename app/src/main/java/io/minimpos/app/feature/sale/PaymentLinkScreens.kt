package io.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.R
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.feature.OutcomeMessage
import io.minimpos.app.feature.lock.ManagerApproval
import io.minimpos.app.feature.outcomeNote
import io.minimpos.app.share.ShareEffect
import io.minimpos.app.ui.components.BottomActions
import io.minimpos.app.ui.components.ConfirmDialog
import io.minimpos.app.ui.components.EmailReceiptDialog
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.OutcomeHeader
import io.minimpos.app.ui.components.OutcomeNote
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.ProcessingContent
import io.minimpos.app.ui.components.QrImage
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.TertiaryButton
import io.minimpos.app.ui.components.rememberMoneyFormatter
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.core.money.MoneyFormatter

/**
 * The payment link of sale [saleId]: while Adyen makes it, a spinner; then the link as a QR code for the shopper to
 * scan and its address, with sharing (on phones and tablets), emailing and printing it as an unpaid receipt, checking
 * whether it was paid (also every few seconds on its own) and cancelling it. Once paid it shows the receipt like any
 * result. A link just made at checkout ([fresh]) leads back to a new sale; one opened from history back to it.
 */
@Composable
fun PaymentLinkScreen(
    saleId: String,
    fresh: Boolean,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: PaymentLinkViewModel = paymentLinkViewModel(saleId, fresh),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LinkDialog?>(null) }
    var approval by remember { mutableStateOf(false) }
    if (approval) {
        return ManagerApproval(onApprove = {
            approval = false
            dialog = LinkDialog.CANCEL
        }, onCancel = { approval = false })
    }
    val leave = { if (fresh) navigator.popTo(Route.Sale) else navigator.back() }
    BackHandler { leave() }
    MiniScaffold(
        title = stringResource(R.string.link_title),
        onBack = leave,
        modifier = modifier,
        bottomBar = {
            val sale = state.sale
            if (sale != null && !state.creating) {
                PaymentLinkBottomBar(
                    sale,
                    onHome = navigator::home,
                    onNewSale = { navigator.popTo(Route.Sale) },
                    onTryAgain = { navigator.replace(Route.Checkout(SaleKind.SALE)) },
                )
            }
        },
    ) { padding ->
        val sale = state.sale
        if (sale == null || state.creating) {
            ProcessingContent(
                amount = sale?.let { rememberMoneyFormatter(it.currency).format(it.totalMinor) },
                message = stringResource(R.string.link_creating),
                modifier = Modifier.padding(padding),
            )
            return@MiniScaffold
        }
        PaymentLinkContent(
            state,
            LinkEvents(
                onShare = vm.transaction::share,
                onEmail = { dialog = LinkDialog.EMAIL },
                onPrint = { vm.transaction.print() },
                onCheck = vm::check,
                onCancel = { approval = true },
            ),
            Modifier.padding(padding),
        )
    }
    state.sale?.let { sale ->
        PaymentLinkDialogs(sale, dialog, onEmail = vm.transaction::email, onCancel = vm::cancel, onDismiss = { dialog = null })
    }
    ShareEffect(state.transaction.share, vm.transaction::shared)
}

/** The dialogs the payment link screen opens. */
private enum class LinkDialog { EMAIL, CANCEL }

/**
 * What the payment link screen's buttons do.
 *
 * @property onShare Shares the link (or, once paid, the receipt).
 * @property onEmail Asks for the address to email it to.
 * @property onPrint Prints it.
 * @property onCheck Asks Adyen whether it was paid.
 * @property onCancel Asks to confirm cancelling it.
 */
private class LinkEvents(
    val onShare: () -> Unit,
    val onEmail: () -> Unit,
    val onPrint: () -> Unit,
    val onCheck: () -> Unit,
    val onCancel: () -> Unit,
)

/** The outcome, then the link to scan or what can be done with the sale now. */
@Composable
private fun PaymentLinkContent(
    state: PaymentLinkUiState,
    events: LinkEvents,
    modifier: Modifier = Modifier,
) {
    val sale = state.sale ?: return
    val dimens = LocalDimens.current
    val money = rememberMoneyFormatter(sale.currency)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            LinkOutcome(sale, money)
            val link = state.openLink
            if (link != null) {
                OpenLink(link)
                OpenLinkActions(state, events)
            } else {
                SettledLinkActions(state, events)
            }
        }
    }
}

/** The status, the amount and what it means: until when the link works, that it was paid online, or why it failed. */
@Composable
private fun LinkOutcome(
    sale: SaleEntity,
    money: MoneyFormatter,
) {
    val formatDateTime = LocalAppContainer.current::formatDateTime
    OutcomeHeader(statusKind(sale), statusTitle(sale), money.format(sale.amountMinor), titleTag = "linkStatus") {
        when {
            sale.status == SaleStatus.AWAITING_PAYMENT -> {
                sale.paymentLinkExpiresAt?.let { OutcomeNote(stringResource(R.string.link_valid_until, formatDateTime(it))) }
            }

            sale.status == SaleStatus.APPROVED -> {
                OutcomeNote(stringResource(R.string.link_paid_note))
            }

            else -> {
                sale.outcomeNote()?.let { OutcomeNote(it) }
            }
        }
    }
}

/** The link as a QR code the shopper scans with their phone, and its address, which can be selected and copied. */
@Composable
private fun ColumnScope.OpenLink(link: String) {
    Text(
        stringResource(R.string.link_scan_hint),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    QrImage(link, Modifier.fillMaxWidth(QR_WIDTH).widthIn(max = 320.dp).testTag("linkQr"))
    SelectionContainer {
        Text(
            link,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("linkUrl"),
        )
    }
}

/** Delivering the open link (sharing, emailing, printing), checking whether it was paid, and cancelling it. */
@Composable
private fun ColumnScope.OpenLinkActions(
    state: PaymentLinkUiState,
    events: LinkEvents,
) {
    val transaction = state.transaction
    if (transaction.canShare) {
        SecondaryButton(
            stringResource(R.string.link_share),
            events.onShare,
            icon = Icons.Default.Share,
            modifier = Modifier.testTag("shareLink"),
        )
    }
    if (transaction.canEmail) {
        SecondaryButton(
            stringResource(R.string.link_email),
            events.onEmail,
            loading = transaction.email.running,
            icon = Icons.Default.Email,
            modifier = Modifier.testTag("emailLink"),
        )
        OutcomeMessage(transaction.email)
    }
    if (transaction.canPrint) {
        SecondaryButton(
            stringResource(R.string.link_print),
            events.onPrint,
            loading = transaction.print.running,
            icon = Icons.Default.Print,
            modifier = Modifier.testTag("printLink"),
        )
        OutcomeMessage(transaction.print)
    }
    CheckButton(state, events.onCheck)
    TertiaryButton(stringResource(R.string.link_cancel), events.onCancel, destructive = true, modifier = Modifier.testTag("cancelLink"))
    OutcomeMessage(state.cancel)
}

/**
 * For a link that is no longer open: checking again after an unknown outcome, or, once paid, the receipt's printing,
 * emailing and sharing.
 */
@Composable
private fun ColumnScope.SettledLinkActions(
    state: PaymentLinkUiState,
    events: LinkEvents,
) {
    val sale = state.sale ?: return
    if (sale.status == SaleStatus.UNKNOWN) CheckButton(state, events.onCheck)
    if (sale.status != SaleStatus.APPROVED) return
    val transaction = state.transaction
    if (transaction.canPrint) {
        SecondaryButton(
            stringResource(R.string.result_print),
            events.onPrint,
            loading = transaction.print.running,
            icon = Icons.Default.Print,
        )
        OutcomeMessage(transaction.print)
    }
    if (transaction.canEmail) {
        SecondaryButton(
            stringResource(R.string.result_email),
            events.onEmail,
            loading = transaction.email.running,
            icon = Icons.Default.Email,
        )
        OutcomeMessage(transaction.email)
    }
    if (transaction.canShare) ShareReceiptButton(events.onShare)
}

/** "Check payment" with the outcome of the last check. */
@Composable
private fun ColumnScope.CheckButton(
    state: PaymentLinkUiState,
    onCheck: () -> Unit,
) {
    SecondaryButton(
        stringResource(R.string.link_check),
        onCheck,
        loading = state.check.running,
        icon = Icons.Default.Refresh,
        modifier = Modifier.testTag("checkLink"),
    )
    OutcomeMessage(state.check)
}

/** Home beside "New sale", or "Try again" for a link Adyen did not make (the cart is still there). */
@Composable
private fun PaymentLinkBottomBar(
    sale: SaleEntity,
    onHome: () -> Unit,
    onNewSale: () -> Unit,
    onTryAgain: () -> Unit,
) {
    BottomActions {
        Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing), verticalAlignment = Alignment.CenterVertically) {
            HomeButton(onHome, Modifier.weight(1f))
            if (sale.status == SaleStatus.FAILED) {
                PrimaryButton(stringResource(R.string.result_try_again), onTryAgain, modifier = Modifier.weight(1f).testTag("tryAgain"))
            } else {
                PrimaryButton(stringResource(R.string.result_new_sale), onNewSale, modifier = Modifier.weight(1f).testTag("newSaleAfter"))
            }
        }
    }
}

/** The [dialog] open over the link of [sale], if any; either closes ([onDismiss]) before [onEmail] or [onCancel] runs. */
@Composable
private fun PaymentLinkDialogs(
    sale: SaleEntity,
    dialog: LinkDialog?,
    onEmail: (to: String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        LinkDialog.EMAIL -> {
            EmailReceiptDialog(
                onSend = {
                    onDismiss()
                    onEmail(it)
                },
                onDismiss = onDismiss,
                initial = sale.shopperEmail.orEmpty(),
            )
        }

        LinkDialog.CANCEL -> {
            ConfirmDialog(
                title = stringResource(R.string.link_cancel_title),
                message = stringResource(R.string.link_cancel_message, rememberMoneyFormatter(sale.currency).format(sale.totalMinor)),
                confirmLabel = stringResource(R.string.link_cancel),
                destructive = true,
                dismissLabel = stringResource(R.string.link_cancel_keep),
                onConfirm = {
                    onDismiss()
                    onCancel()
                },
                onDismiss = onDismiss,
            )
        }

        null -> {}
    }
}

/** The QR code's share of the screen's width, so it is easy to scan without filling a tablet. */
private const val QR_WIDTH = 0.75f

@Composable
private fun paymentLinkViewModel(
    saleId: String,
    fresh: Boolean,
): PaymentLinkViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = saleId) {
        PaymentLinkViewModel(saleId, container.storedPayments, container.receipts, container.links, fresh)
    }
}

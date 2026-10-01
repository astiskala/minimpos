package io.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.minimpos.app.R
import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.payment.CheckoutForm
import io.minimpos.app.payment.TransactionState
import io.minimpos.app.refund.PaymentHold
import io.minimpos.app.ui.components.ActionMessage
import io.minimpos.app.ui.components.BottomActions
import io.minimpos.app.ui.components.Card
import io.minimpos.app.ui.components.EmailReceiptDialog
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.OutcomeHeader
import io.minimpos.app.ui.components.OutcomeNote
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.ProcessingContent
import io.minimpos.app.ui.components.ReceiptPreview
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.StatusBadge
import io.minimpos.app.ui.components.StatusKind
import io.minimpos.app.ui.components.TertiaryButton
import io.minimpos.app.ui.components.TextInputDialog
import io.minimpos.app.ui.components.currentLocale
import io.minimpos.app.ui.components.rememberMoneyFormatter
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.navigation.Route
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.money.MoneyFormatter
import io.minimpos.core.payment.PaymentMethods
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.terminal.client.RetryAdvice
import java.util.Locale

/**
 * The last step before paying: the amount, the optional merchant reference, customer reference and email fields,
 * and saving the card. Pay starts the payment and opens [PaymentScreen]. With [preAuthorisation] the amount is only
 * held, which the screen explains.
 */
@Composable
fun CheckoutScreen(
    navigator: Navigator,
    preAuthorisation: Boolean,
    modifier: Modifier = Modifier,
    vm: CheckoutViewModel = checkoutViewModel(preAuthorisation),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.currency)
    val dimens = LocalDimens.current

    MiniScaffold(
        title = stringResource(R.string.checkout_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            BottomActions {
                PrimaryButton(
                    text =
                        stringResource(
                            if (preAuthorisation) R.string.pre_auth_charge else R.string.checkout_pay,
                            money.format(state.totals.amounts.gross),
                        ),
                    enabled = state.canPay,
                    onClick = { if (vm.pay()) navigator.replace(Route.Payment(preAuthorisation)) },
                    modifier = Modifier.testTag("pay"),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                Text(
                    money.format(state.totals.amounts.gross),
                    style = dimens.amountStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("checkoutAmount"),
                )
                if (preAuthorisation) {
                    Text(
                        stringResource(R.string.checkout_pre_auth_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("preAuthNote"),
                    )
                }
                CartSummary(state, money)
                CheckoutFields(state, onUpdate = vm::update)
                CheckoutSwitches(state, onUpdate = vm::update)
            }
        }
    }
}

/** The cart's lines, the tax (when it is charged) and the total. */
@Composable
private fun CartSummary(
    state: CheckoutUiState,
    money: MoneyFormatter,
) {
    Card {
        state.totals.lines.forEach { priced ->
            LabeledValue("${priced.line.quantity} × ${priced.line.name}", money.format(priced.amounts.gross))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (state.payment.chargeTax) LabeledValue(stringResource(R.string.checkout_tax), money.format(state.totals.amounts.tax))
        LabeledValue(stringResource(R.string.checkout_total), money.format(state.totals.amounts.gross), emphasize = true)
    }
}

/** The fields the payment settings ask for; [onUpdate] applies a change to the form. */
@Composable
private fun ColumnScope.CheckoutFields(
    state: CheckoutUiState,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
) {
    val payment = state.payment
    if (payment.askTransactionReference) {
        OutlinedTextField(
            value = state.form.transactionReference,
            onValueChange = { value -> onUpdate { it.copy(transactionReference = value) } },
            label = { Text(stringResource(R.string.checkout_transaction_reference)) },
            placeholder = { Text(stringResource(R.string.checkout_auto)) },
            isError = !state.referenceValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("transactionReference"),
        )
    }
    if (state.showCustomerReference) {
        OutlinedTextField(
            value = state.form.customerReference,
            onValueChange = { value -> onUpdate { it.copy(customerReference = value) } },
            label = { Text(stringResource(R.string.checkout_customer_reference)) },
            supportingText = {
                if (!state.customerReferenceValid) {
                    Text(
                        stringResource(R.string.checkout_customer_reference_short, ShopperReferences.MIN_LENGTH),
                    )
                }
            },
            isError = !state.customerReferenceValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("customerReference"),
        )
    }
    if (state.showEmail) {
        OutlinedTextField(
            value = state.form.email,
            onValueChange = { value -> onUpdate { it.copy(email = value) } },
            label = { Text(stringResource(R.string.checkout_email)) },
            supportingText = {
                if (!state.emailValid) {
                    Text(stringResource(R.string.email_invalid_address))
                } else if (payment.captureEmailBefore && payment.autoSendEmail) {
                    Text(stringResource(R.string.checkout_email_hint))
                }
            },
            isError = !state.emailValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
    }
}

/** Saving the card, when a shopper reference allows it, and tipping on the receipt, when a printer is available. */
@Composable
private fun ColumnScope.CheckoutSwitches(
    state: CheckoutUiState,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
) {
    val payment = state.payment
    if (state.canTokenize) {
        SaveCardSwitch(
            checked = state.tokenize,
            byEmail = payment.shopperReferenceSource == ShopperReferenceSource.EMAIL,
            onChange = { checked -> onUpdate { it.copy(tokenize = checked) } },
        )
    }
    if (state.canTipOnReceipt) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_tip_on_receipt),
            hint = stringResource(R.string.checkout_tip_on_receipt_hint),
            checked = state.tipOnReceipt,
            onChange = { checked -> onUpdate { it.copy(tipOnReceipt = checked) } },
            tag = "tipOnReceipt",
        )
    }
}

/** "Save card", explaining what the saved card is filed under ([byEmail]: the email, else the customer reference). */
@Composable
private fun SaveCardSwitch(
    checked: Boolean,
    byEmail: Boolean,
    onChange: (Boolean) -> Unit,
) = LabeledSwitch(
    title = stringResource(R.string.checkout_save_card),
    hint = stringResource(if (byEmail) R.string.checkout_save_card_email_hint else R.string.checkout_save_card_hint),
    checked = checked,
    onChange = onChange,
    tag = "tokenize",
)

/** A checkout switch with its [title] and an explaining [hint]; the switch is tagged [tag]. */
@Composable
private fun LabeledSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun checkoutViewModel(preAuthorisation: Boolean): CheckoutViewModel {
    val container = LocalAppContainer.current
    val kind = kindOf(preAuthorisation)
    return viewModel(key = kind.name) {
        CheckoutViewModel(
            container.session(kind),
            container.payments,
            container.settingsState,
            container.terminalStatus.state,
            container::currency,
            kind,
        )
    }
}

private fun kindOf(preAuthorisation: Boolean) = if (preAuthorisation) SaleKind.PRE_AUTHORISATION else SaleKind.SALE

/**
 * Waits while the shopper pays on the terminal (whose own payment screen usually covers this one), then opens the
 * result. The cancel button, and Back, ask the terminal to cancel the payment once it has been sent. [preAuthorisation]
 * says which cart is being paid.
 */
@Composable
fun PaymentScreen(
    navigator: Navigator,
    preAuthorisation: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val state by container.payments.state.collectAsStateWithLifecycle()
    val cart by container.session(kindOf(preAuthorisation)).cart.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(container.currency(settings))

    LaunchedEffect(state) {
        when (val current = state) {
            is TransactionState.Finished -> navigator.replace(Route.SaleResult(current.id))
            TransactionState.Idle -> navigator.popTo(Route.ringUp(preAuthorisation))
            is TransactionState.Processing -> Unit
        }
    }
    BackHandler(enabled = state is TransactionState.Processing) { container.payments.cancel() }

    val processing = state as? TransactionState.Processing
    MiniScaffold(title = stringResource(R.string.payment_title), onBack = null, modifier = modifier) { padding ->
        ProcessingContent(
            amount = money.format(cart.totals(settings.payment.taxMode, settings.payment.chargeTax).amounts.gross),
            message = stringResource(if (processing?.cancelling == true) R.string.payment_cancelling else R.string.payment_follow_terminal),
            modifier = Modifier.padding(padding),
        ) {
            SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = container.payments::cancel,
                enabled = processing?.serviceId != null && !processing.cancelling,
                modifier = Modifier.widthIn(max = 320.dp).testTag("cancelPayment"),
            )
        }
    }
}

/**
 * The outcome of sale [saleId]: approved with printing and emailing, or not with Adyen's retry advice, a status
 * check for unknown outcomes and cancelling a busy terminal's transaction.
 */
@Composable
fun SaleResultScreen(
    saleId: String,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: SaleResultViewModel = saleResultViewModel(saleId),
) {
    val container = LocalAppContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    var showReceipt by remember { mutableStateOf(false) }
    val record = state.record
    val ringUp = Route.ringUp(state.preAuthorisation)

    fun done(route: Route) {
        vm.finish()
        navigator.popTo(route)
    }
    BackHandler { done(ringUp) }
    val dimens = LocalDimens.current

    MiniScaffold(
        title = stringResource(R.string.result_title),
        onBack = { done(ringUp) },
        modifier = modifier,
        bottomBar = {
            if (record != null) {
                SaleResultBottomBar(state, onHome = { done(Route.Home) }, onNewSale = { done(ringUp) }, onTryAgain = {
                    vm.finish()
                    navigator.replace(Route.Checkout(state.preAuthorisation))
                })
            }
        },
    ) { padding ->
        if (record == null) return@MiniScaffold
        val money = rememberMoneyFormatter(record.sale.currency)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                SaleOutcome(state, money)
                SalePaymentCard(record.sale)
                if (state.approved) {
                    ApprovedSaleActions(
                        state = state,
                        receipt = if (showReceipt) container.receiptFactory.sale(record, state.settings.receipt) else null,
                        onPrint = vm::print,
                        onPrintMerchantCopy = vm::printMerchantCopy,
                        onEnterTip = { navigator.push(Route.Tip(saleId)) },
                        onEmail = vm::email,
                        onToggleReceipt = { showReceipt = !showReceipt },
                    )
                } else {
                    UnapprovedSaleActions(
                        state = state,
                        onRecheck = vm::recheck,
                        onAbortBusy = vm::abortBusyTransaction,
                        onBackToSale = { done(ringUp) },
                    )
                }
            }
        }
    }
}

/** The outcome badge and title, the amount, and for unsuccessful payments why and what to do next. */
@Composable
private fun SaleOutcome(
    state: SaleResultUiState,
    money: MoneyFormatter,
) {
    val sale = state.record?.sale ?: return
    OutcomeHeader(statusKind(sale), statusTitle(sale), money.format(sale.amountMinor), titleTag = "resultStatus") {
        sale.message?.takeIf { sale.status != SaleStatus.APPROVED }?.let { OutcomeNote(it) }
        if (state.approved && state.preAuthorisation && !sale.captured) OutcomeNote(stringResource(R.string.checkout_pre_auth_note))
        HoldNotes(sale, money, afterPayment = true)
        state.advice?.let { advice ->
            Text(
                adviceText(advice),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("retryAdvice"),
            )
        }
    }
}

/**
 * Home, beside "New sale" (or "New pre-auth" after a pre-authorisation) after an approved payment or "Try again"
 * otherwise, which goes back to checkout with the same cart.
 */
@Composable
private fun SaleResultBottomBar(
    state: SaleResultUiState,
    onHome: () -> Unit,
    onNewSale: () -> Unit,
    onTryAgain: () -> Unit,
) {
    BottomActions {
        Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing), verticalAlignment = Alignment.CenterVertically) {
            HomeButton(onHome, Modifier.weight(1f))
            if (state.approved) {
                PrimaryButton(
                    stringResource(if (state.preAuthorisation) R.string.result_new_pre_auth else R.string.result_new_sale),
                    onNewSale,
                    modifier = Modifier.weight(1f).testTag("newSaleAfter"),
                )
            } else {
                PrimaryButton(stringResource(R.string.result_try_again), onTryAgain, modifier = Modifier.weight(1f).testTag("tryAgain"))
            }
        }
    }
}

/** Goes back to the home screen from a result, next to the result's main action and as tall as it. */
@Composable
fun HomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = SecondaryButton(
    stringResource(R.string.action_home),
    onClick,
    icon = Icons.Default.Home,
    modifier = modifier.heightIn(min = LocalDimens.current.buttonHeight).testTag("home"),
)

/**
 * For a payment that was not approved: checking an unknown outcome again, cancelling the transaction a busy terminal is
 * working on, and going back to the sale.
 */
@Composable
private fun ColumnScope.UnapprovedSaleActions(
    state: SaleResultUiState,
    onRecheck: () -> Unit,
    onAbortBusy: () -> Unit,
    onBackToSale: () -> Unit,
) {
    if (state.record?.sale?.status == SaleStatus.UNKNOWN) {
        SecondaryButton(
            stringResource(R.string.result_check_again),
            onRecheck,
            loading = state.rechecking,
            icon = Icons.Default.Refresh,
        )
    }
    if (state.busyServiceId != null) {
        SecondaryButton(
            stringResource(R.string.result_abort_busy),
            onAbortBusy,
            loading = state.abort.running,
            modifier = Modifier.testTag("abortBusy"),
        )
    }
    state.abort.message?.let { ActionMessage(it, state.abort.isError) }
    SecondaryButton(
        stringResource(if (state.preAuthorisation) R.string.result_back_to_pre_auth else R.string.result_back_to_sale),
        onBackToSale,
    )
}

/**
 * The reference, card (and the wallet it was in), PSP reference, and whether the card was saved when that was asked
 * for.
 */
@Composable
private fun SalePaymentCard(sale: SaleEntity) {
    Card {
        LabeledValue(stringResource(R.string.detail_reference), sale.merchantReference)
        LabeledValue(
            stringResource(R.string.detail_card),
            listOfNotNull(sale.paymentBrand?.uppercase(), sale.maskedPan).joinToString(" ").ifBlank {
                null
            },
        )
        LabeledValue(stringResource(R.string.detail_wallet), PaymentMethods.wallet(sale.paymentMethodVariant)?.displayName)
        LabeledValue(stringResource(R.string.detail_psp), sale.pspReference)
        if (sale.tokenizationRequested) {
            LabeledValue(
                stringResource(R.string.detail_token),
                sale.storedPaymentMethodId ?: stringResource(R.string.detail_token_not_created),
            )
        }
    }
}

/**
 * Printing (then the merchant copy, when one is due), entering the tip of a sale awaiting one, emailing and showing the
 * receipt of an approved sale ([receipt] is null while hidden). The email button asks for the address, starting with
 * the one captured at checkout.
 */
@Composable
private fun ColumnScope.ApprovedSaleActions(
    state: SaleResultUiState,
    receipt: ReceiptDocument?,
    onPrint: () -> Unit,
    onPrintMerchantCopy: () -> Unit,
    onEnterTip: () -> Unit,
    onEmail: (to: String) -> Unit,
    onToggleReceipt: () -> Unit,
) {
    var askEmail by remember { mutableStateOf(false) }
    if (state.printerAvailable) {
        SecondaryButton(
            stringResource(R.string.result_print),
            onPrint,
            loading = state.print.running,
            icon = Icons.Default.Print,
            modifier = Modifier.testTag("print"),
        )
        if (state.merchantCopyPending) {
            Text(stringResource(R.string.result_tear_off), textAlign = TextAlign.Center)
            SecondaryButton(stringResource(R.string.result_print_merchant), onPrintMerchantCopy, icon = Icons.Default.Print)
        }
        state.print.message?.let { ActionMessage(it, state.print.isError) }
    }
    if (state.record?.sale?.let(PaymentHold::canEnterTip) == true) EnterTipButton(onEnterTip)
    if (state.settings.email.isConfigured && state.settings.payment.effectiveEmailCapture != EmailCapture.OFF) {
        SecondaryButton(
            stringResource(R.string.result_email),
            { askEmail = true },
            loading = state.email.running,
            icon = Icons.Default.Email,
            modifier = Modifier.testTag("emailReceipt"),
        )
        state.email.message?.let { ActionMessage(it, state.email.isError) }
    }
    ReceiptToggle(receipt, onToggleReceipt)
    if (askEmail) {
        EmailReceiptDialog(
            onSend = {
                askEmail = false
                onEmail(it)
            },
            onDismiss = { askEmail = false },
            initial =
                state.record
                    ?.sale
                    ?.shopperEmail
                    .orEmpty(),
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
    TertiaryButton(
        stringResource(if (receipt != null) R.string.result_hide_receipt else R.string.result_show_receipt),
        onToggle,
        modifier = modifier.testTag("toggleReceipt"),
    )
    receipt?.let { ReceiptPreview(it, Modifier.align(Alignment.CenterHorizontally)) }
}

@Composable
private fun saleResultViewModel(saleId: String): SaleResultViewModel {
    val container = LocalAppContainer.current
    val printed = stringResource(R.string.result_printed)
    val emailed = stringResource(R.string.result_emailed)
    val abortSent = stringResource(R.string.result_abort_sent)
    return viewModel(key = saleId) {
        SaleResultViewModel(
            saleId = saleId,
            sales = container.sales,
            receipts = container.receipts,
            payments = container.payments,
            sessions = container::session,
            settings = container.settingsState,
            terminal = container.terminalStatus.state,
            messages = ResultMessages(printed, emailed, abortSent),
        )
    }
}

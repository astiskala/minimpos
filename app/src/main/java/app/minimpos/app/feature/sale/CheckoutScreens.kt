package app.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.MerchantCopy
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.ReceiptActions
import app.minimpos.app.feature.ResultAction
import app.minimpos.app.feature.ResultBar
import app.minimpos.app.feature.StatusCheck
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.outcomeNote
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.standing
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutForm
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.share.ShareEffect
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SwitchRow
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.payment.PaymentMethods
import app.minimpos.core.payment.ScanWallet
import app.minimpos.core.shopper.ShopperReferences
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.RetryAdvice

/**
 * The last step before paying: the amount, the optional merchant reference, customer reference and email fields,
 * and saving the card, for a payment of [kind]. Pay starts the payment and opens [PaymentScreen]; when payment links
 * are offered, "Create payment link" creates one instead and opens [PaymentLinkScreen]. For a pre-authorisation the
 * amount is only held, which the screen explains.
 */
@Composable
fun CheckoutScreen(
    navigator: Navigator,
    kind: SaleKind,
    modifier: Modifier = Modifier,
    vm: CheckoutViewModel = checkoutViewModel(kind),
) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    var tokenStep by remember { mutableStateOf(TokenStep.NONE) }
    if (tokenStep == TokenStep.APPROVE) {
        return ManagerApproval(onApprove = { tokenStep = TokenStep.CHOOSE }, onCancel = { tokenStep = TokenStep.NONE })
    }
    TokenSteps(tokenStep, tokens, onStep = { tokenStep = it }) { card ->
        tokenStep = TokenStep.NONE
        if (vm.chargeToken(card)) navigator.replace(Route.TokenCharge)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    CheckoutContent(
        state = state,
        navigator = navigator,
        kind = kind,
        actions = CheckoutCallbacks(vm::pay, vm::scanWallet, vm::sendLink, vm::update),
        tokens = tokens.isNotEmpty(),
        onChargeToken = { tokenStep = state.tokenStart() },
        modifier = modifier,
    )
}

/**
 * What the checkout's actions do: start the payment, prepare wallet scanning, create a link (its sale's ID, or null)
 * and update the form.
 */
private class CheckoutCallbacks(
    val pay: () -> Boolean,
    val scanWallet: () -> Boolean,
    val sendLink: () -> String?,
    val update: ((CheckoutForm) -> CheckoutForm) -> Unit,
)

/** A token charge takes no tip, so it is confirmed first when a tip was asked for; else it goes to Manager approval. */
private fun Checkout.tokenStart(): TokenStep = if (tipOnReceipt || askGratuity) TokenStep.CONFIRM_NO_TIP else TokenStep.APPROVE

/** The checkout itself: its details, the payment actions and their confirmations and approvals. */
@Composable
private fun CheckoutContent(
    state: Checkout,
    navigator: Navigator,
    kind: SaleKind,
    actions: CheckoutCallbacks,
    tokens: Boolean,
    onChargeToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val money = rememberMoneyFormatter(state.currency)
    var confirmLink by remember { mutableStateOf(false) }
    var confirmWallet by remember { mutableStateOf(false) }
    var confirmSpecialEntry by remember { mutableStateOf(false) }
    var approveSpecialEntry by remember { mutableStateOf(false) }
    if (approveSpecialEntry) {
        return ManagerApproval(
            onApprove = {
                approveSpecialEntry = false
                if (actions.pay()) navigator.replace(Route.Payment(kind))
            },
            onCancel = { approveSpecialEntry = false },
        )
    }
    SpecialEntryConfirmation(confirmSpecialEntry, state.effectiveCardEntry, onConfirm = {
        confirmSpecialEntry = false
        approveSpecialEntry = true
    }, onDismiss = { confirmSpecialEntry = false })
    val scanWallet = {
        if (actions.scanWallet()) navigator.replace(Route.WalletScan)
        Unit
    }
    WalletTipConfirmation(confirmWallet, onConfirm = {
        confirmWallet = false
        scanWallet()
    }, onDismiss = { confirmWallet = false })
    val createLink = {
        actions.sendLink()?.let { navigator.replace(Route.PaymentLink(it, fresh = true)) }
        Unit
    }
    LinkTipConfirmation(confirmLink, onConfirm = {
        confirmLink = false
        createLink()
    }, onDismiss = { confirmLink = false })

    MiniScaffold(
        title = stringResource(R.string.checkout_title),
        onBack = navigator::back,
        modifier = modifier,
        bottomBar = {
            CheckoutActions(
                state,
                money,
                onPay = {
                    if (state.requiresSpecialEntryApproval) {
                        confirmSpecialEntry = true
                    } else if (actions.pay()) {
                        navigator.replace(Route.Payment(kind))
                    }
                },
                onSendLink = { if (state.tipOnReceipt || state.askGratuity) confirmLink = true else createLink() },
                onScanWallet = { if (state.tipOnReceipt || state.askGratuity) confirmWallet = true else scanWallet() },
                tokens = tokens,
                onChargeToken = onChargeToken,
            )
        },
    ) { padding ->
        CheckoutDetails(state, money, actions.update, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun WalletTipConfirmation(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        ConfirmDialog(
            title = stringResource(R.string.checkout_scan_wallet),
            message = stringResource(R.string.wallet_without_tip),
            confirmLabel = stringResource(R.string.checkout_scan_wallet),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SpecialEntryConfirmation(
    visible: Boolean,
    mode: CardEntryOverride,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        val label = stringResource(if (mode == CardEntryOverride.MOTO) R.string.checkout_moto else R.string.checkout_mke)
        ConfirmDialog(
            title = label,
            message = stringResource(R.string.checkout_special_entry_warning),
            confirmLabel = label,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun CheckoutDetails(
    state: Checkout,
    money: MoneyFormatter,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
            Text(
                money.format(state.totals.amounts.gross),
                style = dimens.amountStyle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("checkoutAmount"),
            )
            if (state.preAuthorisation) {
                Text(
                    stringResource(R.string.checkout_pre_auth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("preAuthNote"),
                )
            }
            CartSummary(state, money)
            CheckoutFields(state, onUpdate)
            CheckoutSwitches(state, onUpdate)
        }
    }
}

@Composable
private fun LinkTipConfirmation(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        ConfirmDialog(
            title = stringResource(R.string.checkout_send_link),
            message = stringResource(R.string.checkout_link_without_tip),
            confirmLabel = stringResource(R.string.checkout_send_link),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Pay on the terminal and, under it, Scan wallet when offered, Charge token when the shopper has [tokens] saved, and
 * Create payment link when links are offered. Each reaches Adyen or a terminal, so each goes through the setup gate.
 */
@Composable
private fun CheckoutActions(
    state: Checkout,
    money: MoneyFormatter,
    onPay: () -> Unit,
    onSendLink: () -> Unit,
    onScanWallet: () -> Unit,
    tokens: Boolean,
    onChargeToken: () -> Unit,
) {
    val gate = currentSetupGate()
    val pay = { gate.run(onPay) }
    val sendLink = { gate.run(onSendLink) }
    val scanWallet = { gate.run(onScanWallet) }
    val chargeToken = { gate.run(onChargeToken) }
    BottomActions {
        PrimaryButton(
            text =
                stringResource(
                    if (state.preAuthorisation) R.string.pre_auth_charge else R.string.checkout_pay,
                    money.format(state.totals.amounts.gross),
                ),
            enabled = state.canPay,
            onClick = pay,
            modifier = Modifier.testTag("pay"),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = state.offersScanWallet && state.canSendLink && !tokens
            if (maxWidth >= 480.dp && sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
                    WalletCheckoutButton(state.canScanWallet, scanWallet, Modifier.weight(1f))
                    LinkCheckoutButton(sendLink, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
                    if (state.offersScanWallet) WalletCheckoutButton(state.canScanWallet, scanWallet)
                    if (tokens) TokenCheckoutButton(state.canPay, chargeToken)
                    if (state.canSendLink) LinkCheckoutButton(sendLink)
                }
            }
        }
    }
}

@Composable
private fun WalletCheckoutButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = SecondaryButton(
    stringResource(R.string.checkout_scan_wallet),
    onClick,
    enabled = enabled,
    icon = Icons.Default.QrCodeScanner,
    modifier = modifier.testTag("scanWallet"),
)

@Composable
private fun TokenCheckoutButton(
    enabled: Boolean,
    onClick: () -> Unit,
) = SecondaryButton(
    stringResource(R.string.checkout_charge_token),
    onClick,
    enabled = enabled,
    icon = Icons.Default.CreditCard,
    modifier = Modifier.testTag("chargeToken"),
)

/** Where charging a saved token from checkout stands: nothing, confirming no tip, Manager approval, choosing a token. */
private enum class TokenStep { NONE, CONFIRM_NO_TIP, APPROVE, CHOOSE }

/**
 * The confirmation that a token charge takes no tip ([TokenStep.CONFIRM_NO_TIP]) and the choice of one of [tokens]
 * ([TokenStep.CHOOSE]); Manager approval comes between them, from the screen.
 */
@Composable
private fun TokenSteps(
    step: TokenStep,
    tokens: List<StoredCard>,
    onStep: (TokenStep) -> Unit,
    onChoose: (StoredCard) -> Unit,
) {
    when (step) {
        TokenStep.CONFIRM_NO_TIP -> {
            ConfirmDialog(
                title = stringResource(R.string.checkout_charge_token),
                message = stringResource(R.string.checkout_token_without_tip),
                confirmLabel = stringResource(R.string.checkout_charge_token),
                onConfirm = { onStep(TokenStep.APPROVE) },
                onDismiss = { onStep(TokenStep.NONE) },
            )
        }

        TokenStep.CHOOSE -> {
            AlertDialog(
                onDismissRequest = { onStep(TokenStep.NONE) },
                title = { Text(stringResource(R.string.checkout_choose_token)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        tokens.forEach { card ->
                            ListItem(
                                headlineContent = { Text(cardLabel(card.id, card.brand, card.lastFour)) },
                                supportingContent =
                                    card.expiryMonth?.let { month ->
                                        card.expiryYear?.let { year -> { Text(stringResource(R.string.saved_card_expires, month, year)) } }
                                    },
                                leadingContent = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                                modifier = Modifier.clickable { onChoose(card) }.testTag("token_${card.id}"),
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { onStep(TokenStep.NONE) }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        // Approval is shown in place of the screen.
        TokenStep.NONE, TokenStep.APPROVE -> {}
    }
}

@Composable
private fun LinkCheckoutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = SecondaryButton(
    stringResource(R.string.checkout_send_link),
    onClick,
    icon = Icons.Default.Link,
    modifier = modifier.testTag("sendLink"),
)

/** The cart's lines, the tax (when it is charged) and the total. */
@Composable
private fun CartSummary(
    state: Checkout,
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
    state: Checkout,
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
    state: Checkout,
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
    if (state.canTipOnTerminal) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_tip_on_terminal),
            hint = stringResource(R.string.checkout_tip_on_terminal_hint),
            checked = state.askGratuity,
            onChange = { checked ->
                onUpdate { it.copy(askGratuity = checked, tipOnReceipt = if (checked) false else it.tipOnReceipt) }
            },
            tag = "askGratuity",
        )
    }
    if (state.canTipOnReceipt) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_tip_on_receipt),
            hint = stringResource(R.string.checkout_tip_on_receipt_hint),
            checked = state.tipOnReceipt,
            onChange = { checked ->
                onUpdate { it.copy(tipOnReceipt = checked, askGratuity = if (checked) false else it.askGratuity) }
            },
            tag = "tipOnReceipt",
        )
    }
    if (state.canAskGiving) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_ask_giving),
            hint = stringResource(R.string.checkout_ask_giving_hint),
            checked = state.askGiving,
            onChange = { checked -> onUpdate { it.copy(askGiving = checked) } },
            tag = "askGiving",
        )
    }
    SpecialEntrySwitches(state, onUpdate)
}

@Composable
private fun ColumnScope.SpecialEntrySwitches(
    state: Checkout,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
) {
    if (state.canUseMoto) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_moto),
            hint = stringResource(R.string.checkout_moto_hint),
            checked = state.effectiveCardEntry == CardEntryOverride.MOTO,
            onChange = { checked ->
                onUpdate { it.copy(cardEntryOverride = if (checked) CardEntryOverride.MOTO else CardEntryOverride.NORMAL) }
            },
            tag = "motoSwitch",
        )
    }
    if (state.canUseMke) {
        LabeledSwitch(
            title = stringResource(R.string.checkout_mke),
            hint = stringResource(R.string.checkout_mke_hint),
            checked = state.effectiveCardEntry == CardEntryOverride.MKE,
            onChange = { checked ->
                onUpdate { it.copy(cardEntryOverride = if (checked) CardEntryOverride.MKE else CardEntryOverride.NORMAL) }
            },
            tag = "mkeSwitch",
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
    SwitchRow(title, checked, onChange, subtitle = hint, modifier = Modifier.testTag(tag))
}

@Composable
private fun checkoutViewModel(kind: SaleKind): CheckoutViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = kind.name) {
        CheckoutViewModel(
            container.session(kind),
            container.payments,
            container.settingsState,
            container.terminalStatus.state,
            container::currency,
            container.links,
            walletPayments = container.walletPayments,
            discovery = container.walletDiscovery,
            charges = container.savedCardCharges.takeIf { kind == SaleKind.SALE },
        )
    }
}

/**
 * Waits while the shopper pays on the terminal (whose own payment screen usually covers this one), then opens the
 * result. The cancel button, and Back, ask the terminal to cancel the payment once it has been sent. [kind] says
 * which cart is being paid.
 */
@Composable
fun PaymentScreen(
    navigator: Navigator,
    kind: SaleKind,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val state by container.payments.state.collectAsStateWithLifecycle()
    val cart by container.session(kind).cart.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(container.currency(settings))
    // The session is cleared as soon as the payment is approved, so the amount shown is the one the payment started with.
    val paying = rememberSaveable { cart.totals(settings.payment.taxMode, settings.payment.chargeTax).amounts.gross }

    LaunchedEffect(state) {
        when (val current = state) {
            is TransactionState.Finished -> navigator.replace(Route.SaleResult(current.id))
            TransactionState.Idle -> navigator.popTo(Route.ringUp(kind))
            is TransactionState.Processing -> Unit
        }
    }
    BackHandler(enabled = state is TransactionState.Processing) { container.payments.cancel() }

    val processing = state as? TransactionState.Processing
    MiniScaffold(title = stringResource(R.string.payment_title), onBack = null, modifier = modifier) { padding ->
        ProcessingContent(
            amount = money.format(paying),
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
    val state by vm.state.collectAsStateWithLifecycle()
    val record = state.record
    val money = rememberMoneyFormatter(record?.sale?.currency ?: "EUR")
    val ringUp = Route.ringUp(state.kind)
    val gate = currentSetupGate()

    fun done(route: Route) {
        vm.finish()
        navigator.popTo(route)
    }
    BackHandler { done(ringUp) }

    MiniScaffold(
        title = stringResource(R.string.result_title),
        onBack = { done(ringUp) },
        modifier = modifier,
        bottomBar = {
            if (record != null) {
                ResultBar(
                    onHome = { done(Route.Home) },
                    next =
                        saleResultNext(
                            state,
                            money,
                            onNewSale = { done(ringUp) },
                            onPayRemaining = { gate.run { if (vm.payRemaining()) navigator.replace(Route.Payment(state.kind)) } },
                        ) {
                            vm.finish()
                            navigator.replace(Route.Checkout(state.kind))
                        },
                )
            }
        },
    ) { padding ->
        if (record == null) return@MiniScaffold
        SaleResultColumn(padding) {
            SaleOutcome(state, money)
            SalePaymentCard(record.sale)
            if (state.approved) {
                if (PaymentAction.ENTER_TIP in state.actions) EnterTipButton({ gate.run { navigator.push(Route.Tip(saleId)) } })
                ReceiptActions(state.transaction, vm.transaction, MerchantCopy.WHEN_DUE, email = record.sale.shopperEmail.orEmpty())
            } else {
                UnapprovedSaleActions(state, vm.transaction::recheck, vm::abortBusyTransaction) { done(ringUp) }
            }
        }
    }
    ShareEffect(state.transaction.share, vm.transaction::shared)
}

@Composable
private fun SaleResultColumn(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
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
            content = content,
        )
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
        sale.outcomeNote()?.takeIf { sale.status != SaleStatus.APPROVED }?.let { OutcomeNote(it) }
        if (state.preAuthorisation && sale.standing.held) OutcomeNote(stringResource(R.string.checkout_pre_auth_note))
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
 * What the result offers beside Home: "Pay remaining" for a partly paid sale, "New sale" (or "New pre-auth" after a
 * pre-authorisation) after an approved payment, or "Try again" (back to checkout with the same cart) after one that
 * failed and may be retried; null after one that must not be.
 */
@Composable
private fun saleResultNext(
    state: SaleResultUiState,
    money: MoneyFormatter,
    onNewSale: () -> Unit,
    onPayRemaining: () -> Unit,
    onTryAgain: () -> Unit,
): ResultAction? {
    val sale = state.record?.sale
    return when {
        sale?.isPartiallyPaid == true -> {
            ResultAction(stringResource(R.string.result_pay_remaining, money.format(sale.remainingMinor)), "payRemaining", onPayRemaining)
        }

        state.approved -> {
            ResultAction(
                stringResource(if (state.preAuthorisation) R.string.result_new_pre_auth else R.string.result_new_sale),
                "newSaleAfter",
                onNewSale,
            )
        }

        sale?.status in setOf(SaleStatus.DECLINED, SaleStatus.CANCELLED, SaleStatus.FAILED) && state.advice != RetryAdvice.DO_NOT_RETRY -> {
            ResultAction(stringResource(R.string.result_try_again), "tryAgain", onTryAgain)
        }

        else -> {
            null
        }
    }
}

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
    if (PaymentAction.CHECK_AGAIN in state.actions) StatusCheck(state.transaction, onRecheck)
    if (state.busyServiceId != null) {
        SecondaryButton(
            stringResource(R.string.result_abort_busy),
            onAbortBusy,
            loading = state.abort.running,
            modifier = Modifier.testTag("abortBusy"),
        )
    }
    OutcomeMessage(state.abort)
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
        LabeledValue(
            stringResource(R.string.detail_wallet),
            ScanWallet.reported(sale.paymentMethodVariant ?: sale.paymentBrand)?.displayName
                ?: PaymentMethods.wallet(sale.paymentMethodVariant)?.displayName,
        )
        sale.requestedWallet
            ?.takeUnless { it == ScanWallet.reported(sale.paymentMethodVariant ?: sale.paymentBrand)?.brand }
            ?.let { LabeledValue(stringResource(R.string.wallet_requested), PaymentMethods.brandName(it)) }
        LabeledValue(stringResource(R.string.detail_psp), sale.pspReference)
        if (sale.tokenizationRequested) {
            LabeledValue(
                stringResource(R.string.detail_token),
                sale.storedPaymentMethodId ?: stringResource(R.string.detail_token_not_created),
            )
        }
    }
}

@Composable
private fun saleResultViewModel(saleId: String): SaleResultViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = saleId) {
        SaleResultViewModel(saleId, container.storedPayments, container.receipts, container.payments, container.operations)
    }
}

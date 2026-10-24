package app.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.text
import app.minimpos.app.payment.TokenDeletionOffer
import app.minimpos.app.payment.TokenDeletionState
import app.minimpos.app.payment.TokenDeletionStatus
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.ChosenCard
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.terminal.checkout.StoredCard
import kotlinx.coroutines.launch

/**
 * Tokens: the shopper reference (the customer reference or email, whichever Settings › Payments
 * makes it from), finding saved tokens, charging one in its own cart or confirming its removal at Adyen.
 * While cards cannot be found, the button says why.
 */
@Composable
fun SavedCardScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: SavedCardViewModel = savedCardViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dimens = LocalDimens.current
    val container = LocalAppContainer.current
    val gate = currentSetupGate()
    val managerConfigured by container.managerPin.pinConfigured.collectAsStateWithLifecycle(initialValue = false)
    var confirmation by remember { mutableStateOf<TokenDeletionOffer?>(null) }
    var approval by remember { mutableStateOf<TokenDeletionOffer?>(null) }
    approval?.let { offer ->
        return ManagerApproval(
            onApprove = {
                approval = null
                gate.run { vm.delete(offer) }
            },
            onCancel = { approval = null },
        )
    }
    confirmation?.let { offer ->
        TokenDeletionDialog(offer, onConfirm = {
            confirmation = null
            approval = offer
        }, onDismiss = { confirmation = null })
    }
    val deleting = state.deletion.status in setOf(TokenDeletionStatus.DELETING, TokenDeletionStatus.CHECKING, TokenDeletionStatus.UNKNOWN)
    MiniScaffold(title = stringResource(R.string.saved_card_title), onBack = navigator::back, modifier = modifier) { padding ->
        val lookup = state.lookup
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "search", contentType = "search") {
                Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth()) {
                    TokenSearch(state, managerConfigured, vm::setReference, vm::find) { vm.recheckDeletion() }
                }
            }
            items(lookup.cards.orEmpty(), key = { it.id }, contentType = { "card" }) { card ->
                TokenRow(
                    card,
                    usable = lookup.chargeable(card) && !deleting,
                    removable = managerConfigured && !deleting && state.charge !is TransactionState.Processing,
                    onChoose = { navigator.push(Route.SavedCardSale(it.chosen())) },
                    onDelete = { confirmation = vm.removal(it) },
                    modifier = Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TokenDeletionDialog(
    offer: TokenDeletionOffer,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.token_delete_title),
        message =
            stringResource(
                R.string.token_delete_confirm,
                cardLabel(offer.card.id, offer.card.brand, offer.card.lastFour),
                offer.card.id,
                offer.reference,
                offer.context
                    ?.merchantAccount
                    ?.ifBlank { "—" }
                    .orEmpty(),
                offer.context?.historyEnvironment.orEmpty(),
            ),
        confirmLabel = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ColumnScope.TokenSearch(
    state: SavedCardUiState,
    managerConfigured: Boolean,
    onReference: (String) -> Unit,
    onFind: () -> Unit,
    onRecheck: () -> Unit,
) {
    val lookup = state.lookup
    val gate = currentSetupGate()
    Text(
        stringResource(R.string.saved_card_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReferenceField(state, onReference) { gate.run(onFind) }
    GatedButton(
        stringResource(R.string.saved_card_find),
        onFind,
        blocked = lookup.blocked,
        enabled = state.shopperReference != null && lookup.reference == state.shopperReference,
        loading = lookup.finding,
        requiresSetup = true,
        icon = Icons.Default.Search,
        modifier = Modifier.padding(top = LocalDimens.current.spacing).testTag("findCards"),
    )
    if (!managerConfigured) Text(stringResource(R.string.token_delete_manager), style = MaterialTheme.typography.bodySmall)
    TokenDeletionOutcome(state.deletion, onRecheck)
    lookup.failure?.let { ActionMessage(it.text(), isError = true) }
    if (lookup.cards?.isEmpty() == true) {
        ActionMessage(
            stringResource(R.string.saved_card_none),
            isError = false,
            Modifier.testTag("noSavedCards"),
        )
    }
}

@Composable
private fun ColumnScope.TokenDeletionOutcome(
    outcome: TokenDeletionState,
    onRecheck: () -> Unit,
) {
    if (outcome.status != TokenDeletionStatus.IDLE) {
        val message =
            when (outcome.status) {
                TokenDeletionStatus.DELETED -> R.string.token_deleted
                TokenDeletionStatus.FAILED -> R.string.token_delete_failed
                TokenDeletionStatus.UNKNOWN -> R.string.token_delete_unknown
                TokenDeletionStatus.IDLE, TokenDeletionStatus.DELETING, TokenDeletionStatus.CHECKING -> R.string.token_deleting
            }
        ActionMessage(
            stringResource(message, outcome.card?.id.orEmpty(), outcome.reference.orEmpty()),
            isError = outcome.status in setOf(TokenDeletionStatus.FAILED, TokenDeletionStatus.UNKNOWN),
            modifier = Modifier.testTag("tokenDeletionOutcome"),
        )
        outcome.failure?.let { ActionMessage(it.text(), isError = true) }
        if (outcome.status == TokenDeletionStatus.UNKNOWN) {
            GatedButton(
                stringResource(R.string.token_delete_recheck),
                onRecheck,
                blocked = null,
                requiresSetup = true,
                modifier = Modifier.testTag("recheckTokenDeletion"),
            )
        }
    }
}

@Composable
private fun ReferenceField(
    state: SavedCardUiState,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val email = state.source == ShopperReferenceSource.EMAIL
    val form = state.checkout.form
    OutlinedTextField(
        value = if (email) form.email else form.customerReference,
        onValueChange = onChange,
        label = { Text(stringResource(if (email) R.string.checkout_email else R.string.checkout_customer_reference)) },
        singleLine = true,
        enabled = state.lookup.blocked == null,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (email) KeyboardType.Email else KeyboardType.Text,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(top = LocalDimens.current.spacing).testTag("savedCardReference"),
    )
}

/** A listed token with independent Charge and Delete actions; charge eligibility never hides removal. */
@Composable
internal fun TokenRow(
    card: StoredCard,
    usable: Boolean,
    removable: Boolean,
    onChoose: (StoredCard) -> Unit,
    onDelete: (StoredCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = currentSetupGate()
    Column(modifier.testTag("savedCard_${card.id}")) {
        ListItem(
            headlineContent = { Text(cardLabel(card.id, card.brand, card.lastFour)) },
            supportingContent = {
                val details =
                    listOfNotNull(
                        card.holderName,
                        card.expiryMonth?.let { month -> card.expiryYear?.let { stringResource(R.string.saved_card_expires, month, it) } },
                        stringResource(R.string.saved_card_unsupported).takeUnless { usable },
                    )
                if (details.isNotEmpty()) Text(details.joinToString(" · "))
            },
            leadingContent = { Icon(Icons.Default.CreditCard, contentDescription = null) },
        )
        GatedButton(
            stringResource(R.string.token_charge),
            { onChoose(card) },
            blocked = null,
            enabled = usable,
            requiresSetup = true,
            modifier = Modifier.testTag("chargeToken_${card.id}"),
        )
        TertiaryButton(
            stringResource(R.string.action_delete),
            { gate.run { onDelete(card) } },
            enabled = removable,
            destructive = true,
            modifier = Modifier.testTag("deleteToken_${card.id}"),
        )
    }
}

/** The card as the operator recognises it: brand and last four digits, as far as Adyen sent them, else its token ID. */
internal fun cardLabel(
    id: String,
    brand: String?,
    lastFour: String?,
): String = listOfNotNull(brand?.uppercase(), lastFour?.let { "•••• $it" }).joinToString(" ").ifEmpty { id }

private fun StoredCard.chosen() = ChosenCard(id, brand, lastFour)

private fun ChosenCard.stored(recurringModel: String) = StoredCard(id, brand, lastFour, recurringModels = setOf(recurringModel))

/**
 * Charge saved card, last step: the sale rung up, the card and shopper reference, then the charge, shown as processing
 * like any payment and followed to the sale's result. Manager approval guards the route and is checked again before the
 * charge is sent.
 */
@Composable
fun SavedCardReviewScreen(
    card: ChosenCard,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: SavedCardViewModel = savedCardViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.checkout.currency)
    val charge = state.charge
    LaunchedEffect(charge) {
        // Leaving the result acknowledges the charge, as for any payment.
        if (charge is TransactionState.Finished) navigator.replace(Route.SaleResult(charge.id))
    }
    val charging = charge is TransactionState.Processing
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val wait = stringResource(R.string.processing_wait)
    // A stored charge continues outside this screen; retain its result observer until the answer arrives.
    BackHandler(enabled = charging) { scope.launch { snackbar.showSnackbar(wait) } }
    val total = money.format(state.checkout.totals.amounts.gross)
    MiniScaffold(
        title = stringResource(R.string.saved_card_title),
        onBack = navigator::back.takeUnless { charging },
        modifier = modifier,
        snackbarHostState = snackbar,
        bottomBar = {
            if (!charging) {
                BottomActions {
                    GatedButton(
                        stringResource(R.string.saved_card_charge, total),
                        { vm.charge(card.stored(state.recurringModel)) },
                        blocked = state.lookup.selectionProblem(card.stored(state.recurringModel)),
                        enabled = state.checkout.canPay && state.shopperReference != null,
                        primary = true,
                        requiresSetup = true,
                        modifier = Modifier.testTag("chargeSavedCard"),
                    )
                }
            }
        },
    ) { padding ->
        if (charging) {
            ProcessingContent(total, stringResource(R.string.saved_card_charging), Modifier.padding(padding))
            return@MiniScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(LocalDimens.current.screenPadding),
            horizontalAlignment = Alignment.Start,
        ) {
            LabeledValue(stringResource(R.string.saved_card_card), cardLabel(card.id, card.brand, card.lastFour))
            LabeledValue(stringResource(R.string.detail_shopper_reference), state.shopperReference)
            state.checkout.totals.lines
                .forEach { LabeledValue("${it.line.quantity} × ${it.line.name}", money.format(it.amounts.gross)) }
            LabeledValue(stringResource(R.string.checkout_total), total, emphasize = true)
            Text(
                stringResource(R.string.saved_card_review_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LocalDimens.current.spacing),
            )
        }
    }
}

@Composable
private fun savedCardViewModel(): SavedCardViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "savedCard") {
        SavedCardViewModel(
            container.savedCardSession,
            container.operations.paymentProgress,
            container.savedCardLookup,
            container.settingsState,
            container::currency,
        )
    }
}

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.minimpos.app.feature.FollowProgress
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.lock.ManagerApproval
import app.minimpos.app.feature.lock.rememberManagerAction
import app.minimpos.app.feature.text
import app.minimpos.app.payment.SavedCardLookupState
import app.minimpos.app.payment.TokenDeletionOffer
import app.minimpos.app.payment.TokenDeletionState
import app.minimpos.app.payment.TokenDeletionStatus
import app.minimpos.app.payment.TokenizationRequest
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.InfoDialog
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.ResultDialog
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.ChosenCard
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction
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
    val managerAvailable = remember { container.managerApproval.forAction(ManagerAction.TOKEN_DELETE).available }
    val managerConfigured by managerAvailable.collectAsStateWithLifecycle(initialValue = false)
    var confirmation by remember { mutableStateOf<TokenDeletionOffer?>(null) }
    var approval by remember { mutableStateOf<TokenDeletionOffer?>(null) }
    approval?.let { offer ->
        return ManagerApproval(
            actions = setOf(ManagerAction.TOKEN_DELETE),
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
    TokenResultDialogs(state, vm::acknowledgeLookup, vm::acknowledgeDeletion) { gate.run(vm::recheckDeletion) }
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
                    removing = state.deletion.status == TokenDeletionStatus.DELETING && state.deletion.card?.id == card.id,
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
    Description(stringResource(R.string.saved_card_intro))
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
    if (!managerConfigured) Notice(stringResource(R.string.token_delete_manager), Modifier.padding(top = LocalDimens.current.spacing))
    UnconfirmedDeletion(state, onRecheck)
}

/**
 * An unconfirmed deletion, once its popup is closed: tokens stay unusable until a fresh listing settles it, so the
 * warning and its check remain on the screen.
 */
@Composable
private fun ColumnScope.UnconfirmedDeletion(
    state: SavedCardUiState,
    onRecheck: () -> Unit,
) {
    val outcome = state.deletion
    val checking = outcome.status == TokenDeletionStatus.CHECKING
    if (!checking && !(outcome.status == TokenDeletionStatus.UNKNOWN && state.deletionAcknowledged)) return
    val spacing = LocalDimens.current.spacing
    Notice(
        stringResource(R.string.token_delete_unknown, outcome.card?.id.orEmpty(), outcome.reference.orEmpty()),
        Modifier.padding(top = spacing).testTag("tokenDeletionUnconfirmed"),
        kind = NoticeKind.WARNING,
    )
    GatedButton(
        stringResource(R.string.token_delete_recheck),
        onRecheck,
        blocked = null,
        loading = checking,
        requiresSetup = true,
        modifier = Modifier.padding(top = spacing).testTag("recheckTokenDeletion"),
    )
}

/**
 * Popups reporting a finished lookup or deletion, one at a time: no tokens found (neutral), a lookup failure, a
 * deletion that is confirmed, failed or unconfirmed. An unconfirmed deletion's popup offers [onRecheck] too.
 */
@Composable
private fun TokenResultDialogs(
    state: SavedCardUiState,
    onLookupDismiss: (SavedCardLookupState) -> Unit,
    onDeletionDismiss: (TokenDeletionState) -> Unit,
    onRecheck: () -> Unit,
) {
    val lookup = state.lookup
    val failure = lookup.failure
    val answered = !state.lookupAcknowledged && !lookup.finding
    when {
        answered && failure != null -> {
            ResultDialog(stringResource(R.string.saved_card_find), failure.text(), true, "savedCardLookupFailed", {
                onLookupDismiss(lookup)
            })
        }

        answered && lookup.cards?.isEmpty() == true -> {
            InfoDialog(stringResource(R.string.saved_card_find), stringResource(R.string.saved_card_none), "noSavedCards") {
                onLookupDismiss(lookup)
            }
        }

        !state.deletionAcknowledged -> {
            TokenDeletionResult(state.deletion, onDeletionDismiss, onRecheck)
        }
    }
}

/** A finished deletion's popup: confirmed or failed, or unconfirmed with [onRecheck]; nothing while one runs. */
@Composable
private fun TokenDeletionResult(
    deletion: TokenDeletionState,
    onDismiss: (TokenDeletionState) -> Unit,
    onRecheck: () -> Unit,
) {
    val card = deletion.card?.id.orEmpty()
    val reference = deletion.reference.orEmpty()
    val details = listOfNotNull(deletion.failure?.text())
    when (deletion.status) {
        TokenDeletionStatus.DELETED, TokenDeletionStatus.FAILED -> {
            val deleted = deletion.status == TokenDeletionStatus.DELETED
            ResultDialog(
                stringResource(R.string.token_deletion_title),
                stringResource(if (deleted) R.string.token_deleted else R.string.token_delete_failed, card, reference),
                isError = !deleted,
                tag = "tokenDeletionOutcome",
                onDismiss = { onDismiss(deletion) },
                details = details,
            )
        }

        TokenDeletionStatus.UNKNOWN -> {
            ConfirmDialog(
                stringResource(R.string.token_deletion_title),
                (listOf(stringResource(R.string.token_delete_unknown, card, reference)) + details).joinToString("\n\n"),
                confirmLabel = stringResource(R.string.token_delete_recheck),
                onConfirm = onRecheck,
                onDismiss = { onDismiss(deletion) },
                dismissLabel = stringResource(R.string.action_close),
            )
        }

        TokenDeletionStatus.IDLE, TokenDeletionStatus.DELETING, TokenDeletionStatus.CHECKING -> {}
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

/**
 * A listed token with independent Charge and Delete actions; charge eligibility never hides removal. Delete shows a
 * spinner while [removing] this token.
 */
@Composable
internal fun TokenRow(
    card: StoredCard,
    usable: Boolean,
    removable: Boolean,
    onChoose: (StoredCard) -> Unit,
    onDelete: (StoredCard) -> Unit,
    modifier: Modifier = Modifier,
    removing: Boolean = false,
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
            loading = removing,
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

private fun ChosenCard.stored() =
    StoredCard(id, brand, lastFour, recurringModels = setOf(TokenizationRequest.recurringProcessingModel.value))

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
    val approveCharge = rememberManagerAction()
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.checkout.currency)
    val charge = state.charge
    // Leaving the result acknowledges the charge, as for any payment.
    FollowProgress(charge, { navigator.replace(Route.SaleResult(it)) })
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
                SavedCardChargeBar(state, card, total) {
                    approveCharge(setOf(ManagerAction.SALE, ManagerAction.SAVED_CARD_CHARGE)) {
                        vm.charge(card.stored())
                    }
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
            Notice(stringResource(R.string.saved_card_review_note), Modifier.padding(top = LocalDimens.current.spacing))
        }
    }
}

@Composable
private fun SavedCardChargeBar(
    state: SavedCardUiState,
    card: ChosenCard,
    total: String,
    onCharge: () -> Unit,
) {
    BottomActions {
        GatedButton(
            stringResource(R.string.saved_card_charge, total),
            onCharge,
            blocked = state.lookup.selectionProblem(card.stored()),
            enabled = state.checkout.canPay && state.shopperReference != null,
            primary = true,
            requiresSetup = true,
            modifier = Modifier.testTag("chargeSavedCard"),
        )
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

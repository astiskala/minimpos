package app.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.text
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.ProcessingContent
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.ChosenCard
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.terminal.checkout.StoredCard
import kotlinx.coroutines.launch

/**
 * Charge saved card, first step: the shopper reference (the customer reference or email, whichever Settings › Payments
 * makes it from), finding the cards saved for it, and choosing one, which goes on to ring up the sale in its own cart.
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
    MiniScaffold(title = stringResource(R.string.saved_card_title), onBack = navigator::back, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
        ) {
            Text(
                stringResource(R.string.saved_card_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReferenceField(state, vm::setReference, vm::find)
            val lookup = state.lookup
            GatedButton(
                stringResource(R.string.saved_card_find),
                vm::find,
                blocked = lookup.blocked,
                enabled = state.shopperReference != null,
                loading = lookup.finding,
                requiresSetup = true,
                icon = Icons.Default.Search,
                modifier = Modifier.padding(top = dimens.spacing).testTag("findCards"),
            )
            lookup.failure?.let { ActionMessage(it.text(), isError = true) }
            lookup.cards?.let { cards -> CardList(cards, lookup::chargeable) { navigator.push(Route.SavedCardSale(it.chosen())) } }
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

@Composable
private fun ColumnScope.CardList(
    cards: List<StoredCard>,
    chargeable: (StoredCard) -> Boolean,
    onChoose: (StoredCard) -> Unit,
) {
    if (cards.isEmpty()) {
        ActionMessage(stringResource(R.string.saved_card_none), isError = false, Modifier.testTag("noSavedCards"))
        return
    }
    cards.forEach { card ->
        val usable = chargeable(card)
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = usable) { onChoose(card) }
                    .testTag("savedCard_${card.id}"),
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

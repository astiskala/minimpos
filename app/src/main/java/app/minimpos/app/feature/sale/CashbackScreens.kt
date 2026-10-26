package app.minimpos.app.feature.sale

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.cashAwaitingHandover
import app.minimpos.app.feature.cashbackFacts
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutForm
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SwitchRow
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route

/** Cash requested with a purchase; an unavailable or conflicting selection stays visible and blocks Pay. */
@Composable
internal fun ColumnScope.CashbackChoices(
    state: Checkout,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
) {
    if (!state.payment.cashback.enabled && state.form.cashBackMinor == 0L) return
    val money = rememberMoneyFormatter(state.currency)
    var input by remember { mutableStateOf(money.formatPlain(state.form.cashBackMinor)) }
    Text(stringResource(R.string.cashback_title))
    OutlinedTextField(
        input,
        { value ->
            input = value
            onUpdate { it.copy(cashBackMinor = state.currency.parseMinor(value) ?: -1) }
        },
        label = { Text(stringResource(R.string.cashback_requested)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.testTag("cashbackAmount"),
    )
    LabeledValue(stringResource(R.string.cashback_card_charge), money.format(state.requestedCardMinor))
    SwitchRow(
        stringResource(
            R.string.cashback_card_confirm,
            state.payment.cashback.card
                ?.name
                .orEmpty(),
        ),
        state.form.cashbackConfirmation == state.payment.cashback,
        { value -> onUpdate { it.copy(cashbackConfirmation = state.payment.cashback.takeIf { value }) } },
    )
    if (!state.managerConfigured) OutcomeNote(stringResource(R.string.gift_card_manager_note))
    if (!state.cashbackAvailable) OutcomeNote(stringResource(R.string.cashback_unavailable))
    if (!state.cashbackReady) OutcomeNote(stringResource(R.string.cashback_conflicts))
}

/** The same cash facts and explicit handover entry on results and historical details. */
@Composable
internal fun ColumnScope.CashbackHandover(
    sale: SaleEntity,
    navigator: Navigator,
) {
    if (sale.requestedCashBackMinor == null) return
    LocalContext.current.cashbackFacts(sale).forEach { Text(it) }
    if (sale.cashAwaitingHandover && sale.cashBackMinor != null) {
        SecondaryButton(stringResource(R.string.cashback_handover), {
            navigator.push(
                Route.CashbackHandover(sale.id),
            )
        }, modifier = Modifier.testTag("cashbackHandover"))
    }
}

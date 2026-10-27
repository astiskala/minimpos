package app.minimpos.app.feature.sale

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.feature.mexicanPaymentLabel
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutForm
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.terminal.client.MexicanPayment

/** Explicit shopper choice, retained visibly when another option or changed setup prevents sending it. */
@Composable
internal fun ColumnScope.MexicanPaymentChoices(
    state: Checkout,
    onUpdate: ((CheckoutForm) -> CheckoutForm) -> Unit,
) {
    if (!state.payment.mexicanInstallments && state.form.mexicanPayment == null) return
    Text(stringResource(R.string.mexican_title))
    Text(stringResource(R.string.mexican_hint))
    if (state.offersMexicanRouting || state.form.mexicanPayment != null) {
        val selected = state.form.mexicanPayment
        Text(selected?.let { stringResource(mexicanPaymentLabel(it)) } ?: stringResource(R.string.mexican_automatic))
        SecondaryButton(
            stringResource(R.string.mexican_automatic),
            { onUpdate { it.copy(mexicanPayment = null) } },
            modifier = Modifier.testTag("mexicanAutomatic"),
        )
        MexicanPayment.entries.forEach { choice ->
            SecondaryButton(
                stringResource(mexicanPaymentLabel(choice)),
                { onUpdate { it.copy(mexicanPayment = choice) } },
                modifier = Modifier.testTag("mexican_${choice.name}"),
            )
        }
    }
    if (!state.offersMexicanRouting) Text(stringResource(R.string.mexican_unavailable))
    if (!state.mexicanRoutingReady) Text(stringResource(R.string.mexican_conflicts))
}

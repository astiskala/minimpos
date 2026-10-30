package app.minimpos.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.minimpos.app.R
import app.minimpos.app.ui.components.PickerDialog
import app.minimpos.app.ui.components.currentLocale
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.Spacing
import app.minimpos.core.money.AdyenCurrencies
import app.minimpos.core.money.AdyenCurrency
import java.util.Currency
import java.util.Locale

/** The localised currency name, falling back to Adyen's for codes the device does not recognise. */
internal fun AdyenCurrency.localName(locale: Locale): String =
    if (locale.language == "en") name else runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(name)

private fun AdyenCurrency.label(locale: Locale) = "$code – ${localName(locale)}"

/** Currency codes matching [query] in English or [locale], with the automatic choice first when the query is blank. */
internal fun currencyOptions(
    query: String,
    locale: Locale,
): List<String> {
    val term = query.trim()
    return listOf("").filter { term.isEmpty() } +
        AdyenCurrencies.all.values
            .filter {
                it.code.contains(term, ignoreCase = true) ||
                    it.name.contains(term, ignoreCase = true) ||
                    it.localName(locale).contains(term, ignoreCase = true)
            }.map { it.code }
            .sorted()
}

/**
 * Settings › Payments › Currency: any currency in Adyen's table, or Automatic ([automatic], the device country's own
 * currency). [selectedCode] is blank for Automatic.
 */
@Composable
internal fun CurrencySetting(
    selectedCode: String,
    automatic: String,
    onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()
    val autoLabel = stringResource(R.string.settings_currency_auto, AdyenCurrencies[automatic]?.label(locale) ?: automatic)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                open = true
            }.padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding)
            .testTag("currency"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_currency), style = MaterialTheme.typography.bodyLarge)
            Text(
                AdyenCurrencies[selectedCode]?.label(locale) ?: autoLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.settings_currency_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (open) {
        CurrencyPickerDialog(
            selectedCode = AdyenCurrencies[selectedCode]?.code.orEmpty(),
            autoLabel = autoLabel,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/** A searchable list of Adyen's currencies, opened scrolled to the current choice. A blank code means Automatic. */
@Composable
private fun CurrencyPickerDialog(
    selectedCode: String,
    autoLabel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val locale = currentLocale()
    // Currency codes, with "" (Automatic) first while not searching.
    val options = remember(query, locale) { currencyOptions(query, locale) }
    val initialIndex = remember { options.indexOf(selectedCode).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    PickerDialog(
        title = stringResource(R.string.settings_currency),
        query = query,
        onQueryChange = { query = it.take(MAX_QUERY_LENGTH) },
        searchHint = stringResource(R.string.settings_currency_search),
        searchTag = "currencySearch",
        noMatches = stringResource(R.string.settings_currency_none),
        isEmpty = options.isEmpty(),
        onDismiss = onDismiss,
        listState = listState,
        selectableOptions = true,
        options = {
            items(options, key = { it }) { code ->
                CurrencyOption(AdyenCurrencies[code]?.label(locale) ?: autoLabel, code == selectedCode, code.ifEmpty { "auto" }) {
                    onSelect(code)
                }
            }
        },
    )
}

/** One radio row of the picker, tagged `currency_<tagSuffix>` for tests. */
@Composable
private fun CurrencyOption(
    label: String,
    selected: Boolean,
    tagSuffix: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = Spacing.XLarge, vertical = Spacing.Medium)
            .testTag("currency_$tagSuffix"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.Medium))
        Text(label)
    }
}

private const val MAX_QUERY_LENGTH = 30

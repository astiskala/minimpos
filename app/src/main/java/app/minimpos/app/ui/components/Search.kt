package app.minimpos.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.minimpos.app.R

/** Whether a search field is shown: not at all, always, or because it was just opened (and gets the focus). */
enum class SearchMode {
    /** Not shown; on request, the app-bar icon opens it. */
    HIDDEN,

    /** Shown without taking the focus. */
    SHOWN,

    /** Just opened from the app bar, so it takes the focus (and the keyboard). */
    REQUESTED,
}

/**
 * Where a screen's search field stands.
 *
 * @property mode Whether the field is shown.
 * @property onRequest Whether the field is only shown once asked for from the app bar ([SearchToggle]).
 * @property toggle Opens the field, or closes and clears it.
 */
class SearchControl(
    val mode: SearchMode,
    val onRequest: Boolean,
    val toggle: () -> Unit,
)

/**
 * Where the search field is shown for the current [query]: never while there is nothing worth searching
 * ([available] false) and the query is empty, and with [onRequest] (screens short of height) only once it is asked for,
 * so it takes no room from the list; otherwise always. Closing it clears the query with [onQuery].
 */
@Composable
fun rememberSearchControl(
    available: Boolean,
    onRequest: Boolean,
    query: String,
    onQuery: (String) -> Unit,
): SearchControl {
    var requested by rememberSaveable { mutableStateOf(false) }
    val mode =
        when {
            query.isNotEmpty() -> SearchMode.SHOWN
            !available -> SearchMode.HIDDEN
            onRequest -> if (requested) SearchMode.REQUESTED else SearchMode.HIDDEN
            else -> SearchMode.SHOWN
        }
    return SearchControl(mode, onRequest && available) {
        // Closing the search also clears it, so everything shows again.
        if (mode != SearchMode.HIDDEN) onQuery("")
        requested = mode == SearchMode.HIDDEN
    }
}

/** The app-bar icon that opens the search field ([label] describes it) or, while it is shown, closes it. */
@Composable
fun SearchToggle(
    mode: SearchMode,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = mode != SearchMode.HIDDEN
    IconButton(onClick = onToggle, modifier = modifier.testTag("openSearch")) {
        Icon(
            if (open) Icons.Default.SearchOff else Icons.Default.Search,
            contentDescription = if (open) stringResource(R.string.sale_search_close) else label,
        )
    }
}

/** A one-line search field with a clear button; with [focus] it takes the focus (and so the keyboard) as it appears. */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    focus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) { if (focus) focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    onQuery("")
                }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear)) }
            }
        },
        singleLine = true,
        // The list filters while typing, so the keyboard's search key only has to get out of the way.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.focusRequester(focusRequester).testTag("search"),
    )
}

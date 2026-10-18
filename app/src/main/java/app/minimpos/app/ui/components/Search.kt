package app.minimpos.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
 * @property text What the field holds, saved with the screen; [SearchField] edits it.
 * @property toggle Opens the field, or closes and clears it.
 */
class SearchControl(
    val mode: SearchMode,
    val onRequest: Boolean,
    val text: TextFieldState,
    val toggle: () -> Unit,
) {
    /** Empties the field, and so the query, such as when the screen shows everything again. */
    fun clear() = text.clearText()
}

/**
 * Where the search field is shown: never while there is nothing worth searching ([available] false) and the field is
 * empty, and with [onRequest] (screens short of height) only once it is asked for, so it takes no room from the list;
 * otherwise always. The field keeps its own text, so typing never waits for the screen's asynchronous filter, and every
 * change, closing included (which clears it), reaches [onQuery] on the main thread.
 */
@Composable
fun rememberSearchControl(
    available: Boolean,
    onRequest: Boolean,
    onQuery: (String) -> Unit,
): SearchControl {
    val text = rememberTextFieldState()
    val latestOnQuery by rememberUpdatedState(onQuery)
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { latestOnQuery(it) } }
    var requested by rememberSaveable { mutableStateOf(false) }
    // Read through derived state, so the screen recomposes when the field empties or fills, not on every keystroke.
    val hasQuery by remember(text) { derivedStateOf { text.text.isNotEmpty() } }
    val mode =
        when {
            hasQuery -> SearchMode.SHOWN
            !available -> SearchMode.HIDDEN
            onRequest -> if (requested) SearchMode.REQUESTED else SearchMode.HIDDEN
            else -> SearchMode.SHOWN
        }
    return SearchControl(mode, onRequest && available, text) {
        // Closing the search also clears it, so everything shows again.
        if (mode != SearchMode.HIDDEN) text.clearText()
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

/**
 * A one-line search field over [state], labelled [label], with a clear button; with [focus] it takes the focus (and so
 * the keyboard) as it appears.
 */
@Composable
fun SearchField(
    state: TextFieldState,
    label: String,
    focus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) { if (focus) focusRequester.requestFocus() }
    OutlinedTextField(
        state = state,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (state.text.isNotEmpty()) {
                IconButton(onClick = { state.clearText() }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        // The list filters while typing, so the keyboard's search key only has to get out of the way.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { keyboard?.hide() },
        modifier = modifier.focusRequester(focusRequester).testTag("search"),
    )
}

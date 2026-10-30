package app.minimpos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.minimpos.app.R
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.Spacing

/**
 * A dialog for choosing one of many [options] with a search field, such as a currency or one of an account's
 * terminals: [title], an optional [content] such as a note under it, the field, the lazy list and a Cancel button.
 *
 * The caller keeps the query ([query], [onQueryChange], which should limit its length) and lists the matching options in
 * [options], tagging each for tests. [searchTag] tags the field, and [searchHint] is its hint; keep it to one line, as the
 * field is. While [isEmpty] (nothing matches), [noMatches] is shown after [options]. [selectableOptions] groups
 * radio rows.
 * current choice. [onDismiss] is called by Cancel and by dismissing the dialog.
 *
 * It is not an [androidx.compose.material3.AlertDialog]: that dialog's default width keeps a text field from settling in
 * tests.
 */
@Composable
fun PickerDialog(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    searchHint: String,
    searchTag: String,
    noMatches: String,
    isEmpty: Boolean,
    options: LazyListScope.() -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    selectableOptions: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val compact = LocalDimens.current.compact
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            // The same container colour as the app's other (AlertDialog) dialogs.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(if (compact) Spacing.Medium else Spacing.XLarge)
                    .widthIn(max = LocalDimens.current.contentMaxWidth),
        ) {
            Column(
                Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT)
                    .padding(vertical = if (compact) Spacing.Medium else PICKER_REGULAR_PADDING),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Spacing.XLarge))
                content()
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.XLarge).testTag(searchTag),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).then(if (selectableOptions) Modifier.selectableGroup() else Modifier),
                ) {
                    options()
                    if (isEmpty) {
                        item {
                            Text(
                                noMatches,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.XLarge, vertical = Spacing.Medium),
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = Spacing.Medium)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private val PICKER_MAX_HEIGHT = 600.dp

/** The padding above and below the dialog's content on a regular screen; between two steps of [Spacing]. */
private val PICKER_REGULAR_PADDING = 20.dp

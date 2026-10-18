package app.minimpos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.ui.theme.LocalDimens

/**
 * The heading of one step of a screen done in order, such as Settings › Terminal or Reconciliation: its [number] in a
 * circle, which turns into a tick once the step is [done], then its [title]. A later step is only shown once the steps
 * it needs are done, and a shown step stays editable. Tagged `step_<number>` (with `step_<number>_done` on the badge
 * once done) for tests.
 */
@Composable
fun StepHeader(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    done: Boolean = false,
) {
    val compact = LocalDimens.current.compact
    val doneLabel = stringResource(R.string.step_done)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                heading()
                if (done) contentDescription = "$number. $title, $doneLabel"
            }.padding(start = 16.dp, end = 16.dp, top = if (compact) 16.dp else 24.dp, bottom = if (compact) 4.dp else 8.dp)
            .testTag("step_$number"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .then(if (done) Modifier.testTag("step_${number}_done") else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    }
}

/**
 * A step's body under its [StepHeader], at the screen's text edges: what it does ([hint], muted), then its [content]
 * in a fixed order (fields, the main action, its outcome, then removals), spaced like every other screen's buttons.
 */
@Composable
fun StepBody(
    hint: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalDimens.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
        hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

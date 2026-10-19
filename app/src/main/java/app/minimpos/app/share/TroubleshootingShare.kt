package app.minimpos.app.share

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R

/** Shares only an explicitly requested, already redacted text report; no file or automatic upload. */
@Composable
fun TroubleshootingShareEffect(
    report: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.diagnostics_share_report)
    val consume by rememberUpdatedState(onDone)
    LaunchedEffect(report) {
        if (report != null) {
            openTroubleshootingReport(context, report, title)
            consume()
        }
    }
}

/** Opens Android's chooser for [report], which must already have passed the diagnostic allowlist. */
internal fun openTroubleshootingReport(
    context: Context,
    report: String,
    title: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

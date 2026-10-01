package io.minimpos.app.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.minimpos.app.R
import io.minimpos.app.ui.components.ActionMessage

/** How the screens word this outcome; the one place view-model outcomes become text. */
@Composable
@ReadOnlyComposable
fun ActionOutcome.text(): String =
    when (this) {
        ActionOutcome.Printed -> {
            stringResource(R.string.result_printed)
        }

        is ActionOutcome.Emailed -> {
            stringResource(R.string.result_emailed, to)
        }

        ActionOutcome.AbortSent -> {
            stringResource(R.string.result_abort_sent)
        }

        is ActionOutcome.NotCaptured -> {
            stringResource(R.string.capture_failed, reason)
        }

        is ActionOutcome.Connected -> {
            val printer = stringResource(if (hasPrinter) R.string.home_printer_yes else R.string.home_printer_no)
            "${stringResource(R.string.settings_connection_ok)} (${globalStatus ?: "OK"}, $printer)"
        }

        is ActionOutcome.ConnectionFailed -> {
            "${stringResource(R.string.settings_connection_failed)}: $reason"
        }

        ActionOutcome.ApiWorks -> {
            stringResource(R.string.settings_api_ok)
        }

        is ActionOutcome.TestEmailSent -> {
            stringResource(R.string.settings_test_email_sent, to)
        }

        is ActionOutcome.SecretNotStored -> {
            stringResource(R.string.settings_secret_not_stored, reason.orEmpty())
        }

        is ActionOutcome.Failed -> {
            message
        }
    }

/** The outcome of [state] under its button, in the error colour for a failure; nothing while there is none. */
@Composable
fun OutcomeMessage(
    state: ActionState,
    modifier: Modifier = Modifier,
) {
    state.outcome?.let { ActionMessage(it.text(), state.isError, modifier) }
}

package app.minimpos.app.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.payment.WalletScanProblem
import app.minimpos.app.terminal.WalletDiscoveryFailure
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.currentLocale
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart

/** How the screens word this outcome; the one place view-model outcomes become text. */
@Composable
@ReadOnlyComposable
fun ActionOutcome.text(): String =
    when (this) {
        ActionOutcome.Printed -> stringResource(R.string.result_printed)
        is ActionOutcome.Emailed -> stringResource(R.string.result_emailed, to)
        ActionOutcome.AbortSent -> stringResource(R.string.result_abort_sent)
        ActionOutcome.LinkNotPaid -> stringResource(R.string.link_not_paid)
        is ActionOutcome.CaptureFailed -> captureText()
        is ActionOutcome.Connected -> connectedText()
        is ActionOutcome.ConnectionFailed -> "${stringResource(R.string.settings_connection_failed)}: ${failure.text()}"
        is ActionOutcome.NotSetUp -> problem.text()
        ActionOutcome.NoAnswer -> stringResource(R.string.setup_no_response)
        ActionOutcome.ApiWorks -> stringResource(R.string.settings_api_ok)
        is ActionOutcome.TapToPayReady -> stringResource(R.string.result_tap_to_pay_ready, installationId)
        ActionOutcome.TapToPayRemoved -> stringResource(R.string.result_tap_to_pay_removed)
        is ActionOutcome.TestEmailSent -> stringResource(R.string.settings_test_email_sent, to)
        ActionOutcome.Missing -> stringResource(R.string.error_not_found)
        is ActionOutcome.Unconfirmed -> stringResource(R.string.sentences, stringResource(R.string.action_unconfirmed), failure.text())
        is ActionOutcome.Failed -> failure.text()
    }

/** Local scan failures, containing no scanned data or provider payload. */
@Composable
@ReadOnlyComposable
internal fun WalletScanProblem.text(): String =
    stringResource(
        when (this) {
            WalletScanProblem.INVALID_CODE -> R.string.wallet_invalid_code
            WalletScanProblem.SCAN_FAILED -> R.string.wallet_scan_failed
            WalletScanProblem.CHECKOUT_CHANGED -> R.string.wallet_checkout_changed
            WalletScanProblem.BUSY -> R.string.wallet_busy
        },
    )

/** Optional wallet discovery diagnostics; only Settings displays these failures. */
@Composable
@ReadOnlyComposable
internal fun WalletDiscoveryFailure.text(): String =
    stringResource(
        when (this) {
            WalletDiscoveryFailure.AUTHENTICATION -> R.string.wallet_discovery_auth
            WalletDiscoveryFailure.PERMISSION -> R.string.wallet_discovery_permission
            WalletDiscoveryFailure.UNAVAILABLE -> R.string.wallet_discovery_unavailable
            WalletDiscoveryFailure.UNREADABLE -> R.string.wallet_discovery_unreadable
        },
    )

/** Why a status check left a transaction's outcome unconfirmed, with what to do about it. */
@Composable
@ReadOnlyComposable
fun stillUnconfirmed(failure: Failure): String = stringResource(R.string.still_unconfirmed, failure.text())

/** How the screens word this setup problem: what to enter, install or fix. */
@Composable
@ReadOnlyComposable
fun SetupProblem.text(): String = stringResource(textRes)

/**
 * Why this sale's payment (or payment link) did not succeed, as the screens say it: the app's [SaleEntity.reason] in
 * the current language, then what Adyen or the terminal said ([SaleEntity.message]); null when neither is stored.
 */
@Composable
@ReadOnlyComposable
fun SaleEntity.outcomeNote(): String? =
    note(
        reason?.text(if (paymentLink) R.string.link_unknown_outcome else R.string.payment_unknown_outcome, R.string.payment_interrupted),
        message,
    )

/**
 * Why this sale's latest capture or adjustment did not go through, worded as [outcomeNote]; null when it did. Its
 * standing already says when the outcome is unconfirmed, so only what prevented the confirmation is added.
 */
@Composable
@ReadOnlyComposable
fun SaleEntity.modificationNote(): String? =
    note(
        when (val reason = modificationReason) {
            null -> null
            is StoredReason.NotDone -> reason.failure.text()
            is StoredReason.Unconfirmed -> reason.failure?.text()
            StoredReason.Interrupted -> stringResource(R.string.capture_interrupted)
        },
        modificationMessage,
    )

/** Why this refund was not accepted, worded as [SaleEntity.outcomeNote]; null when nothing is stored. */
@Composable
@ReadOnlyComposable
fun RefundEntity.outcomeNote(): String? = note(reason?.text(R.string.refund_unknown_outcome, R.string.payment_interrupted), message)

/** The app's [worded] reason, with what Adyen or the terminal said ([said]) in brackets after it. */
private fun note(
    worded: String?,
    said: String?,
): String? = worded?.let { noted(it, said) } ?: said

/** [worded], with [details] in brackets after it when there are any. */
private fun noted(
    worded: String,
    details: String?,
): String = details?.let { "$worded ($it)" } ?: worded

/**
 * The title and text of Home's setup card while this is missing: on a terminal ([onTerminal]) whose shared key is
 * missing, a prompt to finish terminal setup; otherwise "finish setting up" with what is missing.
 */
@Composable
@ReadOnlyComposable
fun SetupProblem.setupCard(onTerminal: Boolean): Pair<String, String> =
    if (onTerminal && this in SHARED_KEY_PROBLEMS) {
        stringResource(R.string.home_setup_title) to stringResource(R.string.home_setup_text)
    } else {
        stringResource(R.string.home_setup_title_remote) to text()
    }

/** What is missing of the shared key, which a terminal running the app needs first. */
private val SHARED_KEY_PROBLEMS =
    setOf(SetupProblem.KEY_IDENTIFIER, SetupProblem.PASSPHRASE, SetupProblem.KEY_VERSION, SetupProblem.UNREADABLE_PASSPHRASE)

/**
 * This stored reason in the current language: an unconfirmed outcome as [unknown] followed by what prevented the
 * confirmation, an interruption as [interrupted].
 */
@Composable
@ReadOnlyComposable
private fun StoredReason.text(
    @StringRes unknown: Int,
    @StringRes interrupted: Int,
): String =
    when (this) {
        is StoredReason.NotDone -> {
            failure.text()
        }

        is StoredReason.Unconfirmed -> {
            failure?.let { stringResource(R.string.sentences, stringResource(unknown), it.text()) }
                ?: stringResource(unknown)
        }

        StoredReason.Interrupted -> {
            stringResource(interrupted)
        }
    }

/** Why an action did not happen or was not confirmed, as one sentence with what to do and any details in brackets. */
@Composable
@ReadOnlyComposable
fun Failure.text(): String =
    when (this) {
        is Failure.NotSetUp -> problem.text()
        is Failure.Remote -> fault.text()
        is Failure.Device -> stringResource(fault.deviceRes)
        is Failure.Email -> noted(stringResource(fault.emailRes), reply?.text)
    }

/** This fault as one sentence with what to do, followed by its details (host, terminal, Adyen's words, codes). */
@Composable
@ReadOnlyComposable
private fun Fault.text(): String = noted(stringResource(faultRes), details().joinToString(", ").ifEmpty { null })

/** The facts that identify this fault for the operator or support, in brackets after its sentence; Adyen's words verbatim. */
@Composable
@ReadOnlyComposable
private fun Fault.details(): List<String> =
    when (this) {
        is Fault.Unreachable -> listOf(host)

        is Fault.UnknownHost -> listOf(host)

        is Fault.Untrusted -> listOf(host)

        is Fault.KeyRejected -> listOfNotNull(said?.text)

        is Fault.TerminalRejected -> listOfNotNull(said?.text)

        is Fault.TerminalOffline -> listOfNotNull(poiId, said?.text)

        is Fault.AppRefused -> listOfNotNull(said?.text)

        is Fault.Permission -> listOfNotNull(role)

        is Fault.NotFound -> listOfNotNull(poiId)

        is Fault.AdyenRejected -> adyenDetails(said, http, errorCode)

        is Fault.AdyenUnavailable -> adyenDetails(said, http, errorCode)

        is Fault.UnreadableReply -> listOfNotNull(said?.text)

        is Fault.NoAnswerFromTerminal -> listOfNotNull(poiId, said?.text)

        is Fault.TerminalHttp -> listOf(httpStatus(code))

        is Fault.Credential,
        is Fault.Malformed,
        Fault.NotStarted,
        Fault.Unsupported,
        Fault.NoLateReply,
        Fault.NoRecord,
        Fault.RequestNotEncrypted,
        Fault.ListTooLarge,
        Fault.TimedOut,
        Fault.ConnectionLost,
        Fault.ReplyUnverified,
        Fault.Abandoned,
        Fault.StillInProgress,
        -> emptyList()
    }

/** Adyen's error message, then its HTTP status and error code. */
@Composable
@ReadOnlyComposable
private fun adyenDetails(
    said: ExternalText?,
    http: Int,
    errorCode: String?,
): List<String> = listOfNotNull(said?.text, httpStatus(http), errorCode?.let { stringResource(R.string.fault_error_code, it) })

/** An HTTP status as support staff read it, such as `HTTP 422`. */
private fun httpStatus(code: Int) = "HTTP $code"

/** The string resource that words this fault. */
@get:StringRes
private val Fault.faultRes: Int
    get() =
        when (this) {
            is Fault.Unreachable -> if (terminal) R.string.fault_unreachable_terminal else R.string.fault_unreachable_adyen
            is Fault.UnknownHost -> if (terminal) R.string.fault_unknown_host_terminal else R.string.fault_unknown_host_adyen
            is Fault.Untrusted -> R.string.fault_untrusted
            is Fault.KeyRejected -> R.string.fault_key_rejected
            is Fault.TerminalRejected -> R.string.fault_terminal_rejected
            is Fault.TerminalOffline -> R.string.fault_terminal_offline
            Fault.NotStarted -> R.string.fault_not_started
            is Fault.AppRefused -> R.string.fault_app_refused
            Fault.Unsupported -> R.string.fault_unsupported
            Fault.NoLateReply -> R.string.fault_no_late_reply
            Fault.NoRecord -> R.string.fault_no_record
            Fault.RequestNotEncrypted -> R.string.fault_request_not_encrypted
            is Fault.Credential -> if (key == ApiKey.ADYEN) R.string.fault_credential_adyen else R.string.fault_credential_payments_app
            is Fault.Permission -> if (key == ApiKey.ADYEN) R.string.fault_permission_adyen else R.string.fault_permission_payments_app
            is Fault.NotFound -> R.string.fault_not_found
            is Fault.AdyenRejected -> R.string.fault_adyen_rejected
            Fault.ListTooLarge -> R.string.fault_list_too_large
            Fault.TimedOut -> R.string.fault_timed_out
            Fault.ConnectionLost -> R.string.fault_connection_lost
            Fault.ReplyUnverified -> R.string.fault_reply_unverified
            is Fault.UnreadableReply -> R.string.fault_unreadable_reply
            is Fault.Malformed -> part.partRes
            is Fault.NoAnswerFromTerminal -> R.string.fault_no_answer_from_terminal
            Fault.Abandoned -> R.string.fault_abandoned
            is Fault.TerminalHttp -> R.string.fault_terminal_http
            is Fault.AdyenUnavailable -> R.string.fault_adyen_unavailable
            Fault.StillInProgress -> R.string.fault_still_in_progress
        }

/** The string resource that words this unusable part of a Management answer. */
@get:StringRes
private val MalformedPart.partRes: Int
    get() =
        when (this) {
            MalformedPart.SETTINGS -> R.string.setup_terminal_settings_unreadable
            MalformedPart.KEY -> R.string.setup_shared_key_incomplete
            MalformedPart.KEY_VERSION -> R.string.setup_shared_key_invalid
        }

/** The string resource that words this device failure. */
@get:StringRes
private val DeviceFault.deviceRes: Int
    get() =
        when (this) {
            DeviceFault.SECURE_STORAGE -> R.string.device_secure_storage
            DeviceFault.DATABASE -> R.string.device_database
            DeviceFault.FILE_STORAGE -> R.string.device_file_storage
            DeviceFault.UNEXPECTED -> R.string.device_unexpected
        }

/** The string resource that words this email failure. */
@get:StringRes
private val EmailFault.emailRes: Int
    get() =
        when (this) {
            EmailFault.NOT_CONFIGURED -> R.string.email_not_configured
            EmailFault.INVALID_ADDRESS -> R.string.email_invalid_address
            EmailFault.AUTHENTICATION -> R.string.email_authentication
            EmailFault.UNREACHABLE -> R.string.email_unreachable
            EmailFault.REJECTED -> R.string.email_rejected
        }

/** The string resource that words this setup problem. */
@get:StringRes
private val SetupProblem.textRes: Int
    get() =
        when (this) {
            SetupProblem.POI_ID -> R.string.setup_poiid
            SetupProblem.HOST -> R.string.setup_host
            SetupProblem.KEY_IDENTIFIER -> R.string.setup_key_identifier
            SetupProblem.PASSPHRASE -> R.string.setup_passphrase
            SetupProblem.KEY_VERSION -> R.string.setup_key_version
            SetupProblem.MERCHANT_ACCOUNT -> R.string.setup_merchant_account
            SetupProblem.API_KEY -> R.string.setup_api_key
            SetupProblem.ENVIRONMENT -> R.string.setup_environment
            SetupProblem.TERMINAL_ENVIRONMENT -> R.string.setup_terminal_environment
            SetupProblem.LIVE_PREFIX -> R.string.setup_live_prefix
            SetupProblem.UNREADABLE_PASSPHRASE -> R.string.setup_unreadable_passphrase
            SetupProblem.UNREADABLE_API_KEY -> R.string.setup_unreadable_api_key
            SetupProblem.API_REQUIRED -> R.string.setup_api_required
            SetupProblem.CLOUD_API_KEY -> R.string.setup_cloud_api_key
            SetupProblem.PAYMENTS_APP_MISSING -> R.string.setup_payments_app_missing
            SetupProblem.PAYMENTS_APP_AMBIGUOUS -> R.string.setup_payments_app_ambiguous
            SetupProblem.PAYMENTS_APP_NOT_BOARDED -> R.string.setup_payments_app_not_boarded
            SetupProblem.PAYMENTS_APP_ON_TERMINAL -> R.string.setup_payments_app_on_terminal
            SetupProblem.PAYMENTS_APP_API_KEY -> R.string.setup_payments_app_api_key
            SetupProblem.UNREADABLE_PAYMENTS_APP_KEY -> R.string.setup_unreadable_payments_app_key
            SetupProblem.MANAGER_APPROVAL -> R.string.manager_pin_enter
            SetupProblem.PAYMENT_CONTEXT -> R.string.payment_context_mismatch
            SetupProblem.SETUP_NOT_VERIFIED -> R.string.setup_not_verified
            SetupProblem.TRANSFER_PENDING -> R.string.setup_transfer_pending
            SetupProblem.TERMINAL_ACCESS -> R.string.setup_terminal_access
            SetupProblem.MERCHANT_MISMATCH -> R.string.setup_merchant_mismatch
            SetupProblem.MANAGEMENT_PERMISSION -> R.string.setup_management_permission
            SetupProblem.MANAGEMENT_AUTHENTICATION -> R.string.setup_management_authentication
            SetupProblem.MANAGEMENT_UNAVAILABLE -> R.string.setup_management_unavailable
            SetupProblem.MANAGEMENT_UNREADABLE -> R.string.setup_management_unreadable
            SetupProblem.TERMINAL_SETTINGS_UNREADABLE -> R.string.setup_terminal_settings_unreadable
            SetupProblem.SHARED_KEY_INCOMPLETE -> R.string.setup_shared_key_incomplete
            SetupProblem.SHARED_KEY_INVALID -> R.string.setup_shared_key_invalid
            SetupProblem.STORE_ACCESS -> R.string.setup_store_access
            SetupProblem.SETUP_CHANGED -> R.string.setup_changed
            SetupProblem.KEY_CONNECTION_PENDING -> R.string.setup_key_connection_pending
            SetupProblem.KEY_RECOVERY_UNREADABLE -> R.string.setup_key_recovery_unreadable
            SetupProblem.KEY_CREATION_UNCONFIRMED -> R.string.setup_key_creation_unconfirmed
        }

/** Why a tip, capture or adjustment did not go through, named after what was sent; a refusal says the amount. */
@Composable
@ReadOnlyComposable
private fun ActionOutcome.CaptureFailed.captureText(): String =
    when (this) {
        is ActionOutcome.NotCaptured -> {
            stringResource(if (step == CaptureStep.ADJUSTMENT) R.string.adjust_failed else R.string.capture_failed, failure.text())
        }

        is ActionOutcome.CaptureUnconfirmed -> {
            val unconfirmed = if (step == CaptureStep.ADJUSTMENT) R.string.adjust_unconfirmed else R.string.capture_unconfirmed
            stringResource(R.string.sentences, stringResource(unconfirmed), failure.text())
        }

        is ActionOutcome.CaptureRefused -> {
            val amount = MoneyFormatter(CurrencySpec.of(currency), currentLocale()).format(amountMinor)
            noted(stringResource(if (step == CaptureStep.TIP) R.string.tip_refused else R.string.capture_refused, amount), said?.text)
        }

        ActionOutcome.CaptureNotAllowed -> {
            stringResource(R.string.capture_not_allowed)
        }
    }

/** A successful connection test, with the terminal's status and whether it has a printer. */
@Composable
@ReadOnlyComposable
private fun ActionOutcome.Connected.connectedText(): String {
    if (setupOnly) return stringResource(R.string.settings_payments_app_setup_checked)
    val printer = stringResource(if (hasPrinter) R.string.home_printer_yes else R.string.home_printer_no)
    return "${stringResource(R.string.settings_connection_ok)} (${globalStatus ?: "OK"}, $printer)"
}

/** The outcome of [state] under its button, in the error colour for a failure; nothing while there is none. */
@Composable
fun OutcomeMessage(
    state: ActionState,
    modifier: Modifier = Modifier,
) {
    state.outcome?.let { ActionMessage(it.text(), state.isError, modifier) }
}

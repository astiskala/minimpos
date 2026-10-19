package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.client.Decline
import app.minimpos.terminal.transport.Fault
import java.time.Instant

/**
 * Allowlisted support report. Arbitrary messages are entirely redacted rather than relying on incomplete secret/PII
 * patterns. Only validated protocol diagnostics are extracted; no raw callbacks, keys, receipts or shopper fields.
 */
internal class TapToPayReport(
    private val check: TapToPayCheck,
    private val terminal: TerminalSettings,
    private val device: DeviceInfo,
    private val version: String,
) {
    private val identifier = Regex("[A-Za-z0-9@._ -]{1,120}")
    private val errorCode = Regex("[0-9]{1,5}(?:_[0-9]{1,5})?")
    private val traceParent = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}")
    private val requestId = Regex("[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Formats a reading and optional historical [sale]/[decline], with UTC [timestamp] in epoch milliseconds. */
    fun render(
        timestamp: Long,
        sale: SaleEntity?,
        decline: Decline?,
    ): String =
        buildString {
            append("timestamp=").append(Instant.ofEpochMilli(timestamp)).append('\n')
            field("environment", check.environment?.name)
            field("merchant", terminal.merchantAccount)
            field("store", terminal.storeId)
            field("installationId", check.installationId ?: terminal.paymentsAppInstallationId)
            field("credential.id", check.credential?.id)
            field("credential.username", check.credential?.username)
            field("credential.active", check.credential?.active?.toString())
            field("boardingCredentialAssociated", check.associated.toString())
            field("boardingCredential.id", check.boardingIdentity?.credentialId)
            field("boardingCredential.username", check.boardingIdentity?.username)
            field("appVersion", version)
            field("androidVersion", device.osVersion)
            field("deviceModel", device.model)
            field("paymentsAppVersion", check.environment?.let(device::paymentsAppVersion))
            field("clientKeyCheck", check.clientKey.name)
            append("credentialCheckedAt=").append(check.checkedAt?.let(Instant::ofEpochMilli) ?: "not-checked").append('\n')
            field("boardingStatus", check.boarding.name)
            append("boardingCheckedAt=").append(check.boardingAt?.let(Instant::ofEpochMilli) ?: "not-checked").append('\n')
            field("failureStage", sale?.failureStage() ?: "setup")
            if (sale != null) {
                append("paymentTimestamp=").append(Instant.ofEpochMilli(sale.createdAt)).append('\n')
                field("paymentOutcome", sale.status.name)
                field("paymentEnvironment", sale.context?.environment)
                field("paymentMerchant", sale.context?.merchantAccount)
                field("paymentInstallationId", sale.context?.poiId)
                field("ServiceID", sale.serviceId)
                field("SaleID", sale.context?.saleId)
                field("ErrorCondition", decline?.errorCondition)
                append("message=redacted\n")
                support(sale.diagnosticText())
            }
        }

    private fun StringBuilder.field(
        name: String,
        value: String?,
    ) {
        append(name).append('=').append(value?.takeIf { identifier.matches(it) } ?: "unavailable").append('\n')
    }

    private fun StringBuilder.support(text: String) {
        text.lineSequence().forEach { line ->
            val value = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "errorCode" -> {
                    if (errorCode.matches(value)) field("errorCode", value)
                }

                "requestId" -> {
                    if (requestId.matches(value)) field("requestId", value)
                }

                "traceparent" -> {
                    val match = traceParent.matchEntire(value)
                    if (match != null && match.groupValues[1].any { it != '0' } && match.groupValues[2].any { it != '0' }) {
                        append("traceparent=").append(value).append('\n')
                        append("traceId=").append(match.groupValues[1]).append('\n')
                    }
                }
            }
        }
    }

    private fun SaleEntity.remoteFault(): Fault? {
        val failure =
            when (val stored = reason) {
                is StoredReason.NotDone -> stored.failure
                is StoredReason.Unconfirmed -> stored.failure
                StoredReason.Interrupted, null -> null
            }
        return (failure as? Failure.Remote)?.fault
    }

    private fun SaleEntity.failureStage(): String =
        when (remoteFault()) {
            is Fault.AppRefused -> "callback-error"
            is Fault.UnreadableReply -> "response-decoding"
            null -> "payment-response"
            is Fault.NotSent, is Fault.MaybeSent -> "payment-handoff-or-callback"
        }

    private fun SaleEntity.diagnosticText(): String =
        message ?: when (val fault = remoteFault()) {
            is Fault.AppRefused -> fault.said?.text
            is Fault.UnreadableReply -> fault.said?.text
            is Fault.NotSent, is Fault.MaybeSent, null -> null
        }.orEmpty()
}

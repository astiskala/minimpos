package io.minimpos.terminal.client

import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.applicationinfo.CommonField
import com.adyen.model.applicationinfo.ExternalPlatform
import com.adyen.model.applicationinfo.MerchantDevice
import io.minimpos.terminal.parse.ReceiptField
import java.math.BigDecimal

/**
 * The two ends of every Terminal API conversation, copied into each nexo `MessageHeader`. A request only reaches the
 * right terminal if [poiId] matches it, and the terminal answers (and later finds transactions for status checks) by
 * the [saleId] plus the request's ServiceID.
 *
 * @property saleId Identifies this POS towards the terminal (`MessageHeader.SaleID`). Any stable text works; keep it
 *   unchanged, because transaction status checks only find transactions started with the same SaleID.
 * @property poiId The terminal's POIID, `<model>-<serial number>` (e.g. `AMS1-000168223606144`), as shown on the
 *   terminal and in the Customer Area.
 */
data class TerminalIdentity(
    val saleId: String,
    val poiId: String,
)

/**
 * The POS software, sent to Adyen as `SaleToAcquirerData.applicationInfo` on every payment and refund (see "Pass
 * application information"), so transactions can be traced to it. The Adyen library adds `adyenLibrary` itself.
 *
 * Values are passed through [clean] only when converted by [toApplicationInfo], so the same input always produces the
 * same request; Adyen asks for identical formatting across requests.
 *
 * @property name The application's name (`merchantApplication.name`).
 * @property version The application's version (`merchantApplication.version`).
 * @property integrator Who built the integration (`externalPlatform.integrator`).
 * @property osName The operating system the POS runs on (`merchantDevice.os`), e.g. `Android`.
 * @property osVersion The operating system's version (`merchantDevice.osVersion`).
 * @property platformName The platform the payments come from (`externalPlatform.name`); a standalone app is its own
 *   platform, hence the default of [name].
 * @property platformVersion The platform's version (`externalPlatform.version`); defaults to [version].
 */
data class PosApplication(
    val name: String,
    val version: String,
    val integrator: String,
    val osName: String,
    val osVersion: String,
    val platformName: String = name,
    val platformVersion: String = version,
) {
    /**
     * Builds the Adyen library's [ApplicationInfo] with every value passed through [clean]. A value that cleans to
     * nothing is left null, so the field is omitted from the request rather than sent empty.
     */
    fun toApplicationInfo(): ApplicationInfo =
        ApplicationInfo().apply {
            externalPlatform =
                ExternalPlatform().apply {
                    name = clean(platformName)
                    version = clean(platformVersion)
                    integrator = clean(this@PosApplication.integrator)
                }
            merchantApplication =
                CommonField().apply {
                    name = clean(this@PosApplication.name)
                    version = clean(this@PosApplication.version)
                }
            merchantDevice =
                MerchantDevice().apply {
                    os = clean(osName)
                    osVersion = clean(this@PosApplication.osVersion)
                }
        }

    /**
     * The values Adyen receives, as [toApplicationInfo] sends them (cleaned, plus the library's own name and version),
     * for display.
     */
    fun summary(): ApplicationSummary {
        val info = toApplicationInfo()
        return ApplicationSummary(
            application = "${info.merchantApplication.name} ${info.merchantApplication.version}",
            platform = "${info.externalPlatform.name} ${info.externalPlatform.version}",
            integrator = info.externalPlatform.integrator,
            device = "${info.merchantDevice.os} ${info.merchantDevice.osVersion}",
            library = "${info.adyenLibrary.name} ${info.adyenLibrary.version}",
        )
    }

    /** Adyen's formatting rules for application info values. */
    companion object {
        /** The longest application info value Adyen accepts, in characters. */
        const val MAX_LENGTH = 40
        private val DISALLOWED = Regex("[^A-Za-z0-9 ._-]")
        private val SPACES = Regex(" {2,}")

        /**
         * Adyen's rules for application info values: at most 40 characters, starting with a letter or digit, using
         * letters, digits, dashes, underscores and spaces (plus dots, which Adyen's own version examples use).
         * Disallowed characters become spaces, runs of spaces collapse to one, and anything before the first letter or
         * digit is dropped. Returns null when nothing is left.
         */
        fun clean(value: String): String? =
            value
                .replace(DISALLOWED, " ")
                .replace(SPACES, " ")
                .trim()
                .dropWhile { !it.isLetterOrDigit() }
                .take(MAX_LENGTH)
                .trim()
                .ifEmpty { null }
    }
}

/**
 * The application info of a [PosApplication] as Adyen receives it, each value formatted for display.
 *
 * @property application `merchantApplication` name and version.
 * @property platform `externalPlatform` name and version.
 * @property integrator `externalPlatform.integrator`; null when it cleaned to nothing.
 * @property device `merchantDevice` operating system and version.
 * @property library `adyenLibrary` name and version, which the Adyen library fills in itself.
 */
data class ApplicationSummary(
    val application: String,
    val platform: String,
    val integrator: String?,
    val device: String,
    val library: String,
)

/** How a card stored with a payment may be charged later: Adyen's `recurringProcessingModel`. */
enum class RecurringModel(
    /** The value Adyen expects, e.g. `CardOnFile`. */
    val value: String,
) {
    /** Regular payments of a fixed or variable amount on a fixed schedule. */
    SUBSCRIPTION("Subscription"),

    /** Payments the shopper starts later with the stored card (one-click). */
    CARD_ON_FILE("CardOnFile"),

    /** Payments the merchant starts later without a fixed schedule, such as automatic top-ups. */
    UNSCHEDULED_CARD_ON_FILE("UnscheduledCardOnFile"),
}

/** Which kind of transaction a ServiceID names, for [TerminalClient.status] and [TerminalClient.abort]. */
enum class TransactionKind {
    /** A card payment (nexo message category `Payment`). */
    PAYMENT,

    /** A referenced refund (nexo message category `Reversal`). */
    REFUND,
}

/**
 * What to charge in a card payment, see [TerminalClient.pay]. The shopper, tender option and metadata fields travel in
 * `SaleToAcquirerData`, together with the [PosApplication] info.
 */
data class PaymentParams(
    /**
     * The amount to charge in major units of [currency] (e.g. `12.50` for EUR 12.50), which is what nexo's
     * `RequestedAmount` expects. Callers holding minor units must convert with the currency's Adyen decimals.
     */
    val amount: BigDecimal,
    /** The ISO 4217 currency code, e.g. `EUR`. */
    val currency: String,
    /**
     * The POS's own reference for the sale, sent as `SaleTransactionID.TransactionID`; it appears as the merchant
     * reference in the Customer Area and is echoed back in [TransactionDetails.merchantReference].
     */
    val merchantReference: String,
    /** With [recurringProcessingModel], asks Adyen to store the card for this shopper. */
    val shopperReference: String? = null,
    /** The shopper's email address, sent to Adyen with the shopper's details; null sends none. */
    val shopperEmail: String? = null,
    /**
     * How a card stored for [shopperReference] may be charged later (e.g. card on file or subscription). Null means the
     * card is not stored.
     */
    val recurringProcessingModel: RecurringModel? = null,
    /** e.g. `ReceiptHandler`, so the POS prints receipts instead of the terminal. Sent comma-separated. */
    val tenderOptions: List<String> = emptyList(),
    /** Free-form key/value pairs stored with the payment at Adyen; an empty map sends none. */
    val metadata: Map<String, String> = emptyMap(),
    /** Requests the card alias (TokenRequestedType=Customer); tokenization itself is driven by the shopper fields. */
    val requestCardAlias: Boolean = false,
    /**
     * Only holds [amount] on the card: Adyen's authorisation type `PreAuth` with manual capture, so the payment can be
     * adjusted and is captured (or cancelled) later instead of being captured automatically. False takes a normal
     * payment with the account's default authorisation type and capture.
     */
    val preAuthorisation: Boolean = false,
)

/**
 * A refund of an earlier card payment, see [TerminalClient.refund]. The Terminal API calls this a reversal: it refers to
 * the original payment by the terminal's transaction ID and timestamp, so no card needs to be presented.
 *
 * Constructing it with an [amount] but no [currency] throws [IllegalArgumentException].
 */
data class RefundParams(
    /** The original payment's `POIData.POITransactionID.TransactionID` ([TransactionDetails.poiTransactionId]). */
    val originalTransactionId: String,
    /** The original payment's `POIData.POITransactionID.TimeStamp`, exactly as received. */
    val originalTimestamp: String,
    /** The POS's own reference for this refund (not the original payment's), sent as its `SaleTransactionID`. */
    val merchantReference: String,
    /** Null refunds the full original amount; otherwise a partial refund in [currency], in major units. */
    val amount: BigDecimal? = null,
    /** The original payment's ISO 4217 currency code; required for a partial refund and not sent for a full one. */
    val currency: String? = null,
) {
    init {
        require(amount == null || currency != null) { "Partial refunds need the original currency" }
    }
}

/**
 * The card details Adyen stored during a payment that asked for tokenization, read from the `AdditionalResponse`.
 *
 * @property storedPaymentMethodId The token for charging the stored card later (`tokenization.storedPaymentMethodId`,
 *   or the older `recurring.recurringDetailReference`).
 * @property shopperReference The shopper the card was stored for, as sent in [PaymentParams.shopperReference]; null if
 *   the terminal did not echo it.
 * @property operationType What happened to the stored card (`tokenization.store.operationType`, e.g. `created`); null
 *   if not reported.
 * @property cardAlias Adyen's alias for the card number, which identifies the same card across payments without
 *   exposing it; null if not reported.
 */
data class Tokenization(
    val storedPaymentMethodId: String?,
    val shopperReference: String?,
    val operationType: String?,
    val cardAlias: String?,
)

/**
 * The terminal's answer to a payment or reversal, flattened for the app. Fields the terminal did not send are null (or
 * empty); the card fields and [tokenization] are only filled for payments. Amounts are in major units, as in nexo.
 */
data class TransactionDetails(
    /** True when `Response.Result` is `Success`: the payment was approved or the refund accepted. */
    val success: Boolean,
    /** `Response.ErrorCondition` as sent by the terminal, e.g. `Refusal` or `Cancel`. */
    val errorCondition: String?,
    /** The best human-readable explanation: `refusalReason`, `message`, `errors` or `warnings`. */
    val message: String?,
    /** Why the payment was declined (`refusalReason` in the `AdditionalResponse`, e.g. `Not enough balance`). */
    val refusalReason: String?,
    /**
     * The terminal's transaction ID (`POIData.POITransactionID.TransactionID`, `<tender reference>.<PSP reference>`).
     * Together with [poiTimestamp] it identifies the payment for a later refund.
     */
    val poiTransactionId: String?,
    /** The terminal's transaction timestamp in XML date-time format, to pass back as [RefundParams.originalTimestamp]. */
    val poiTimestamp: String?,
    /**
     * Adyen's unique reference for the transaction, from the `AdditionalResponse` or else the part of
     * [poiTransactionId] after the dot.
     */
    val pspReference: String?,
    /** The merchant reference the terminal reports: the payment's `SaleTransactionID`, or a refund's `merchantReference`. */
    val merchantReference: String?,
    /** The authorised amount of a payment, or the reversed amount of a refund (null if the terminal omitted it). */
    val amount: BigDecimal?,
    /** The ISO 4217 currency code of a payment's [amount]. */
    val currency: String?,
    /** The card scheme, e.g. `mc` or `visa`. */
    val paymentBrand: String?,
    /** The card number with most digits hidden, e.g. `541333 **** 9999`. */
    val maskedPan: String?,
    /** How the card was read: the nexo entry mode (e.g. `Contactless`) or Adyen's `posEntryMode` (e.g. `CLESS_CHIP`). */
    val entryMode: String?,
    /** The issuer's authorisation code for an approved payment. */
    val approvalCode: String?,
    /**
     * The shopper's copy of the card receipt. The terminal sends receipt data even when the POS does the printing
     * (tender option `ReceiptHandler`); empty when it sent none.
     */
    val customerReceipt: List<ReceiptField>,
    /** The merchant's copy of the card receipt; empty when the terminal sent none. */
    val cashierReceipt: List<ReceiptField>,
    /** True when a receipt carries `RequiredSignatureFlag`: the shopper has to sign the merchant copy. */
    val signatureRequired: Boolean,
    /** The complete `AdditionalResponse`, flattened by [io.minimpos.terminal.parse.AdditionalResponseParser]. */
    val additionalData: Map<String, String>,
    /** The stored card, when a payment asked for tokenization and Adyen stored it; null otherwise. */
    val tokenization: Tokenization?,
) {
    /** What the POS should do next; [RetryAdvice.DO_NOT_RETRY] for a successful transaction. */
    val advice: RetryAdvice get() = if (success) RetryAdvice.DO_NOT_RETRY else RetryAdvice.forPayment(errorCondition, refusalReason)

    /** With ErrorCondition Busy, the transaction the terminal is busy with (it can be aborted). */
    val busyServiceId: String? get() = additionalData["serviceId"]?.takeIf { errorCondition == "Busy" }
}

/**
 * The result of a payment or refund from [TerminalClient]: either the terminal's answer, or a statement of how sure we
 * are that nothing happened. Network and terminal errors are reported as [NotProcessed] or [Unknown], not thrown.
 */
sealed interface TransactionOutcome {
    /** The ServiceID of the payment or refund request (not of any status check made to settle it). */
    val serviceId: String

    /** The terminal returned a result (approved or not). [recovered] is true when it came via a status check. */
    data class Completed(
        override val serviceId: String,
        /** The terminal's answer; check [TransactionDetails.success] to see whether it was approved. */
        val details: TransactionDetails,
        /** True when the answer came from a transaction status check instead of the original response. */
        val recovered: Boolean = false,
    ) : TransactionOutcome

    /** The request never took effect, so nothing was charged or refunded. */
    data class NotProcessed(
        override val serviceId: String,
        /** Why, in English, e.g. the connection error or the terminal's rejection message. */
        val reason: String,
    ) : TransactionOutcome

    /** The result could not be confirmed; check the Customer Area before retrying. */
    data class Unknown(
        override val serviceId: String,
        /** Why, in English: what went wrong with the original request, or that the status could not be confirmed. */
        val reason: String,
    ) : TransactionOutcome
}

/** The result of [TerminalClient.print]. */
sealed interface PrintOutcome {
    /** Every job was printed. */
    data object Printed : PrintOutcome

    /**
     * A job could not be printed; later jobs were not sent.
     *
     * @property message Why, from the terminal's `AdditionalResponse` or the connection error.
     * @property noPrinter True when the terminal has no printer (ErrorCondition `UnavailableDevice`, or a message
     *   saying so), so printing will never work on it; false for errors that may pass, such as a paper jam.
     */
    data class Failed(
        val message: String,
        val noPrinter: Boolean,
    ) : PrintOutcome
}

/**
 * The result of [TerminalClient.diagnose], a connection and configuration check that charges nothing.
 *
 * @property reachable True when the terminal answered with `Result` `Success`, which proves that the host, POIID and
 *   shared key are all right.
 * @property message Why it failed (connection error, rejection with key advice, or the terminal's message); may be
 *   null when the terminal gives no explanation.
 * @property globalStatus The terminal's nexo `GlobalStatus`, e.g. `OK` or `Busy`; null if not reported.
 * @property printerStatus The nexo `PrinterStatus`, e.g. `OK` or `PaperLow`; null when the terminal has no printer (or
 *   is unreachable).
 */
data class DiagnosisResult(
    val reachable: Boolean,
    val message: String?,
    val globalStatus: String? = null,
    val printerStatus: String? = null,
) {
    /** True when the terminal reported a printer status, i.e. it has a printer. */
    val hasPrinter: Boolean get() = printerStatus != null
}

/**
 * How to settle a payment or refund whose response never arrived (Adyen "No result received"): check the transaction
 * status every [intervalMillis]. Checks that get no answer count towards [attempts]; while the terminal reports the
 * transaction as InProgress, checking continues (up to [maxInProgressChecks]). With the defaults that is a minute of
 * unanswered checks, or ten minutes while the shopper is still busy, before the outcome is reported as unknown.
 *
 * @property attempts How many status checks may go unanswered (connection errors or no usable reply).
 * @property intervalMillis The pause before each status check; Adyen advises every 5 seconds.
 * @property maxInProgressChecks How many InProgress replies to accept before giving up.
 */
data class RecoveryPolicy(
    val attempts: Int = 12,
    val intervalMillis: Long = 5_000,
    val maxInProgressChecks: Int = 120,
)

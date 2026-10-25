package app.minimpos.terminal.client

import app.minimpos.terminal.parse.ReceiptField
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.applicationinfo.CommonField
import com.adyen.model.applicationinfo.ExternalPlatform
import com.adyen.model.applicationinfo.MerchantDevice
import java.util.concurrent.atomic.AtomicReference

private const val MAX_SCANNED_CODE_LENGTH = 128
private const val MAX_TIP_SUGGESTIONS = 3
private const val MAX_TIP_PERCENT = 100

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

    /** A gift-card activation, load, undone load or deactivation (nexo message category `StoredValue`). */
    STORED_VALUE,
}

/** Card entry mode override for a terminal payment. */
enum class CardEntryOverride {
    /** Normal card presentation on the terminal (tap, chip or swipe). */
    NORMAL,

    /** Mail Order / Telephone Order (`SaleToAcquirerData.tenderOption=MOTO`). */
    MOTO,

    /** Manual Key Entry (`PaymentTransaction.TransactionConditions.ForceEntryMode=[Keyed]`). */
    MKE,
}

/** How a payment was authorized on the terminal (`PaymentResult.OnlineFlag` and `AdditionalResponse`). */
enum class OfflineProcessingMode {
    /** Approved online by the issuer/acquirer (`OnlineFlag=true`). */
    ONLINE,

    /** Approved offline by the EMV chip within floor limits (`OnlineFlag=false` and `offlineAuthCode` present). */
    OFFLINE_EMV,

    /** Approved offline under Store-and-Forward (`OnlineFlag=false` without `offlineAuthCode`). */
    STORE_AND_FORWARD,
}

/**
 * What to charge in a card payment, see [TerminalClient.pay]. The shopper, tender option and metadata fields travel in
 * `SaleToAcquirerData`, together with the [PosApplication] info.
 */
data class PaymentParams(
    /**
     * The amount to charge in minor units of [currency] (e.g. `1250` for EUR 12.50); the client converts it once, with
     * the currency's Adyen decimals, to the major units nexo's `RequestedAmount` expects.
     */
    val amountMinor: Long,
    /** The ISO 4217 currency code, e.g. `EUR`. */
    val currency: String,
    /**
     * The POS's own reference for the sale, sent as `SaleTransactionID.TransactionID`; it appears as the merchant
     * reference in the Customer Area and is echoed back in [TransactionDetails.merchantReference].
     */
    val merchantReference: String,
    /**
     * Adyen's `shopperReference`, which links the payment to a shopper; with [recurringProcessingModel] Adyen also
     * stores the card for them. Null sends none.
     */
    val shopperReference: String? = null,
    /** The shopper's email address, sent to Adyen with the shopper's details; null sends none. */
    val shopperEmail: String? = null,
    /**
     * How a card stored for [shopperReference] may be charged later (e.g. card on file or subscription). Null means the
     * card is not stored.
     */
    val recurringProcessingModel: RecurringModel? = null,
    /** Whether the app prints the receipts, card details included, instead of the terminal (`ReceiptHandler`). */
    val appPrintsReceipts: Boolean = false,
    /** Free-form key/value pairs stored with the payment at Adyen; an empty map sends none. */
    val metadata: Map<String, String> = emptyMap(),
    /** Requests the card alias (TokenRequestedType=Customer); tokenization itself is driven by the shopper fields. */
    val requestCardAlias: Boolean = false,
    /**
     * Only holds [amountMinor] on the card: Adyen's authorisation type `PreAuth` with manual capture, so the payment can be
     * adjusted and is captured (or cancelled) later instead of being captured automatically. False takes a normal
     * payment with the account's default authorisation type and capture.
     */
    val preAuthorisation: Boolean = false,
    /** Single-use scanned wallet instrument; null asks the terminal to collect the payment instrument normally. */
    val scannedPayment: ScannedPayment? = null,
    /** Prompts the shopper for a tip on the terminal (`AskGratuity`), with optional `SaleToPOIData` overrides. */
    val terminalTip: TerminalTipOptions? = null,
    /** Chained customer-display and input prompts run before and after the payment; null skips them. */
    val shopperFlow: ShopperSessionFlow? = null,
    /** Sends `AllowPartialAuthorisation` in `SaleToAcquirerData.tenderOption` so the terminal can approve part of [amountMinor]. */
    val allowPartialAuthorisation: Boolean = false,
    /** Optional `SaleData.SaleReferenceID` grouping split tenders of the same sale. */
    val saleReferenceId: String? = null,
    /** Sends `AskGiving` in `SaleToAcquirerData.tenderOption` to prompt for a charity donation after purchase. */
    val askGiving: Boolean = false,
    /** Requests audio guidance for this payment; caller confirms software, enablement and no signature requirement. */
    val audioGuidance: Boolean = false,
    /** Overrides how the card is entered on the terminal (`MOTO` tender option or `ForceEntryMode=Keyed`). */
    val cardEntryOverride: CardEntryOverride = CardEntryOverride.NORMAL,
    /**
     * Pays with, pays out or refunds onto a gift card (`PaymentInstrumentType=StoredValue`); null for card payments.
     * Only redemption may allow partial approval; no terminal card features apply.
     */
    val giftCard: GiftCardPayment? = null,
) {
    init {
        require(
            giftCard == null || (
                !preAuthorisation && scannedPayment == null && terminalTip == null && shopperFlow == null && !askGiving &&
                    !audioGuidance && cardEntryOverride == CardEntryOverride.NORMAL && recurringProcessingModel == null &&
                    !requestCardAlias && (giftCard.use == GiftCardUse.REDEEM || !allowPartialAuthorisation)
            ),
        ) {
            "Gift card requests are immediate, without card features, and only redemption allows partial approval"
        }
        require(
            !audioGuidance || (
                terminalTip == null && !askGiving && scannedPayment == null &&
                    shopperFlow == null && cardEntryOverride == CardEntryOverride.NORMAL
            ),
        ) {
            "Audio guidance requires a card payment without tipping, Giving, special entry or shopper prompts"
        }
        require(scannedPayment == null || !preAuthorisation) { "Scanned wallets require an immediate-charge sale" }
        require(terminalTip == null || (!preAuthorisation && scannedPayment == null)) {
            "Terminal tipping requires an immediate-charge card sale"
        }
        require(!allowPartialAuthorisation || !preAuthorisation) {
            "Partial authorisation requires an immediate-charge sale"
        }
        require(!askGiving || (!preAuthorisation && scannedPayment == null)) {
            "Adyen Giving requires an immediate-charge card sale"
        }
        require(cardEntryOverride == CardEntryOverride.NORMAL || scannedPayment == null) {
            "Card entry overrides cannot be combined with a scanned wallet"
        }
    }
}

/**
 * Dynamic tipping buttons shown on the terminal for one payment (`TipOptions.TipSuggestions` in `SaleData.SaleToPOIData`).
 * Adyen accepts either up to three percentage options (`1`..`100`) or up to three fixed amounts in minor units (`> 0`),
 * plus whether a custom tip option is shown.
 */
sealed interface TerminalTipSuggestions {
    /** Whether the terminal also shows a button to enter a custom tip amount (`TipCustom`). */
    val allowCustom: Boolean

    /** Up to three percentage options (`Percentage1`, `Percentage2`, `Percentage3`), each in `1..100`.
     * @property percentages Non-empty list of up to three percentages.
     * @property allowCustom Whether a custom tip button is included.
     */
    data class Percentages(
        val percentages: List<Int>,
        override val allowCustom: Boolean = true,
    ) : TerminalTipSuggestions {
        init {
            require(percentages.size in 1..MAX_TIP_SUGGESTIONS && percentages.all { it in 1..MAX_TIP_PERCENT }) {
                "Invalid tip percentages"
            }
        }
    }

    /** Up to three fixed tip amounts in minor units (`Amount1`, `Amount2`, `Amount3`).
     * @property amountsMinor Non-empty list of up to three positive minor-unit amounts.
     * @property allowCustom Whether a custom tip button is included.
     */
    data class FixedAmounts(
        val amountsMinor: List<Long>,
        override val allowCustom: Boolean = true,
    ) : TerminalTipSuggestions {
        init {
            require(amountsMinor.size in 1..MAX_TIP_SUGGESTIONS && amountsMinor.all { it > 0 }) { "Invalid fixed tip amounts" }
        }
    }
}

/**
 * Per-payment terminal tipping configuration (`AskGratuity` with optional `SaleData.SaleToPOIData` `TipOptions` override).
 * Adyen requires [suggestions] whenever [amountToTipOnMinor] is specified.
 *
 * @property suggestions Dynamic per-payment `TipSuggestions` override; null uses the terminal's configured gratuities.
 * @property amountToTipOnMinor Part of the purchase amount, in minor units, to calculate percentage tips on
 *   (`AmountToTipOn`); null tips on the full requested amount. Requires [suggestions].
 */
data class TerminalTipOptions(
    val suggestions: TerminalTipSuggestions? = null,
    val amountToTipOnMinor: Long? = null,
) {
    init {
        require(amountToTipOnMinor == null || (suggestions != null && amountToTipOnMinor > 0)) {
            "AmountToTipOn requires positive amount and TipSuggestions"
        }
    }
}

/**
 * A transient numeric wallet code, consumed once while building a request. String conversion never exposes it.
 * Recovery uses transaction status, never this code. Safe to consume or discard from any thread.
 *
 * @property brand Exact Terminal API routing brand, not a display name.
 * @param code ASCII digits, retaining leading zeros; bounded to 128 characters for unverified wallet contracts.
 * @throws IllegalArgumentException for an empty/invalid brand or a nonnumeric, empty or oversized code.
 */
class ScannedPayment(
    val brand: String,
    code: String,
) {
    private val value = AtomicReference(code)

    init {
        require(brand.matches(Regex("[a-z0-9_]+"))) { "Invalid wallet brand" }
        require(code.length in 1..MAX_SCANNED_CODE_LENGTH && code.all { it in '0'..'9' }) { "Invalid payment code" }
    }

    /** Erases the retained reference when an attempt is abandoned, including before a request could be constructed. */
    fun discard() {
        value.set(null)
    }

    internal fun consume(): String = checkNotNull(value.getAndSet(null)) { "Payment code no longer available" }

    override fun toString(): String = "ScannedPayment(brand=$brand, code=[redacted])"
}

/** Native scanning is nonfinancial: an absent, rejected or unreadable answer cannot start a payment. */
sealed interface BarcodeScan {
    /**
     * One scanner result. Deliberately not a data class: string conversion must not expose payment codes.
     * @property code Sensitive scanner content; validate, consume transiently and never persist or log it.
     */
    class Read(
        val code: String,
    ) : BarcodeScan

    /** Scanner did not return a usable code; retrying the scan does not retry a financial operation. */
    data object NotRead : BarcodeScan
}

/**
 * A refund of an earlier card payment, see [TerminalClient.refund]. The Terminal API calls this a reversal: it refers to
 * the original payment by the terminal's transaction ID and timestamp, so no card needs to be presented.
 *
 * Constructing it with an [amountMinor] but no [currency] throws [IllegalArgumentException].
 */
data class RefundParams(
    /** The original payment's `POIData.POITransactionID.TransactionID` ([TransactionDetails.poiTransactionId]). */
    val originalTransactionId: String,
    /** The original payment's `POIData.POITransactionID.TimeStamp`, exactly as received. */
    val originalTimestamp: String,
    /** The POS's own reference for this refund (not the original payment's), sent as its `SaleTransactionID`. */
    val merchantReference: String,
    /** Null refunds the full original amount; otherwise a partial refund in [currency], in minor units. */
    val amountMinor: Long? = null,
    /** The original payment's ISO 4217 currency code; required for a partial refund and not sent for a full one. */
    val currency: String? = null,
) {
    init {
        require(amountMinor == null || currency != null) { "Partial refunds need the original currency" }
    }
}

/**
 * The card details Adyen stored during a payment that asked for tokenization, read from the `AdditionalResponse`.
 *
 * @property storedPaymentMethodId The token for charging the stored card later (`tokenization.storedPaymentMethodId`,
 *   or the older `recurring.recurringDetailReference`).
 */
data class Tokenization(
    val storedPaymentMethodId: String?,
)

/**
 * The terminal's answer to a payment or reversal, flattened for the app. Fields the terminal did not send are null (or
 * empty); the card fields and [tokenization] are only filled for payments. Amounts are in minor units, using Adyen currency precision.
 */
data class TransactionDetails(
    /** True when `Response.Result` is `Success` or `Partial`: the payment was approved or the refund accepted. */
    val success: Boolean,
    /** True when `Response.Result` is `Partial` (`AuthorizedAmount < RequestedAmount`). */
    val partial: Boolean = false,
    /** `Response.ErrorCondition` as sent by the terminal, e.g. `Refusal` or `Cancel`. */
    val errorCondition: String?,
    /**
     * The terminal's explanation (`refusalReason`, plus `message`, `errors` or `warnings`) and any `errorCode`,
     * `traceparent` and extracted `traceId`, on separate lines; remote values stay verbatim. Null when none.
     */
    val message: ExternalText?,
    /** Why the payment was declined (`refusalReason` in the `AdditionalResponse`, e.g. `Not enough balance`). */
    val refusalReason: String?,
    /**
     * The terminal's transaction ID (`POIData.POITransactionID.TransactionID`, `<tender reference>.<PSP reference>`).
     * Together with [poiTimestamp] it identifies the payment for a later refund.
     */
    val poiTransactionId: String?,
    /** The terminal's transaction timestamp in XML date-time format, to pass back as [RefundParams.originalTimestamp]. */
    val poiTimestamp: String?,
    /** The terminal's reconciliation period ID (`POIData.POIReconciliationID`), e.g. `1000`; null when omitted. */
    val poiReconciliationId: String? = null,
    /**
     * Adyen's unique reference for the transaction, from the `AdditionalResponse` or else the part of
     * [poiTransactionId] after the dot.
     */
    val pspReference: String?,
    /** The merchant reference the terminal reports: the payment's `SaleTransactionID`, or a refund's `merchantReference`. */
    val merchantReference: String?,
    /**
     * Authorized total, including tip, surcharge and cashback, or the reversed refund amount, in minor units of
     * [currency]; null when omitted or the currency has no Adyen decimals. Components are never added to this again.
     */
    val amountMinor: Long?,
    /** The tip added on the terminal (`PaymentResult.AmountsResp.TipAmount`), in minor units; null when none was added. */
    val tipMinor: Long? = null,
    /** Surcharge included in [amountMinor] (`TotalFeesAmount`), in minor units; null when unreported, including old history. */
    val surchargeMinor: Long? = null,
    /** Cash returned to the shopper, included in [amountMinor]; null when unreported. Never merchandise. */
    val cashBackMinor: Long? = null,
    /**
     * A gift card's balance after a stored-value operation (`CurrentBalance`, else the value `ItemAmount` reports), in
     * minor units of [currency]; null when unreported and for card payments.
     */
    val balanceMinor: Long? = null,
    /** The ISO 4217 currency code of a payment's [amountMinor]. */
    val currency: String?,
    /** The card scheme, e.g. `mc` or `visa`. */
    val paymentBrand: String?,
    /**
     * Adyen's `paymentMethodVariant` from the `AdditionalResponse`, e.g. `visa`, `mc_debit` or, for a card in a mobile
     * wallet, `visa_applepay`, `mc_googlepay` or `amex_samsungpay`; null when the terminal did not send it.
     */
    val paymentMethodVariant: String? = null,
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
    /** The complete `AdditionalResponse`, flattened by [app.minimpos.terminal.parse.AdditionalResponseParser]. */
    val additionalData: Map<String, String>,
    /** The stored card, when a payment asked for tokenization and Adyen stored it; null otherwise. */
    val tokenization: Tokenization?,
    /** Email collected from the shopper on the terminal before payment (`InputRequest` `GetText`), if any. */
    val collectedEmail: String? = null,
    /** 0–10 rating collected from the shopper on the terminal after payment (`InputRequest` `GetRating`), if any. */
    val shopperRating: Int? = null,
    /** ServiceID of the two-step Adyen Giving donation (`adyen_giving_service_id`), when the shopper donated. */
    val givingServiceId: String? = null,
    /** Charity donation amount in minor units of [currency] from the two-step Adyen Giving status lookup; null when none. */
    val donationMinor: Long? = null,
    /** Whether the transaction was approved online, via Offline EMV, or via Store-and-Forward. */
    val offlineMode: OfflineProcessingMode = OfflineProcessingMode.ONLINE,
    /** Number of unconfirmed offline batches stored on the terminal (`unconfirmedBatchCount`), if reported. */
    val unconfirmedBatchCount: Int? = null,
) {
    /** Why it was not approved (cancelled, busy, what to do next); null when it [succeeded][success]. */
    val decline: Decline? get() = Decline.of(success, errorCondition, refusalReason, additionalData)

    /**
     * Adyen's `adjustAuthorisationData` blob for synchronous authorisation adjustments, sent for pre-authorised payments
     * when "return adjust authorisation data" is enabled in the Customer Area; null otherwise.
     */
    val adjustAuthorisationData: String? get() = additionalData[ADJUST_AUTHORISATION_DATA]

    /** `AdditionalResponse` keys read by the typed properties. */
    companion object {
        /** The `AdditionalResponse` key of [adjustAuthorisationData]. */
        const val ADJUST_AUTHORISATION_DATA = "adjustAuthorisationData"
    }
}

/**
 * The result of a payment or refund from [TerminalClient]: either the terminal's answer, or a statement of how sure we
 * are that nothing happened, with the [Fault] that left it without an answer. Network and terminal errors are reported
 * as [NotProcessed] or [Unknown], not thrown.
 */
sealed interface TransactionOutcome {
    /** The terminal returned a result (approved or not), in its original response or a status check's. */
    data class Completed(
        /** The terminal's answer; check [TransactionDetails.success] to see whether it was approved. */
        val details: TransactionDetails,
    ) : TransactionOutcome

    /** The request never took effect, so nothing was charged or refunded. */
    data class NotProcessed(
        /** Why: a fault that took no effect, or [Fault.NoRecord] from a status check. */
        val fault: Fault,
    ) : TransactionOutcome

    /** The result could not be confirmed; check the Customer Area before retrying. */
    data class Unknown(
        /**
         * What prevented the confirmation: the original request's fault, the status check's, or
         * [Fault.StillInProgress] while the terminal still works on it.
         */
        val fault: Fault,
    ) : TransactionOutcome
}

/** The result of [TerminalClient.print]. */
sealed interface PrintOutcome {
    /** Every job was printed. */
    data object Printed : PrintOutcome

    /**
     * A job could not be printed; later jobs were not sent.
     *
     * @property fault Why: the delivery's fault, or [Fault.TerminalRejected] with the terminal's message.
     * @property noPrinter True when the terminal has no printer (ErrorCondition `UnavailableDevice`, or a message
     *   saying so), so printing will never work on it; false for errors that may pass, such as a paper jam.
     */
    data class Failed(
        val fault: Fault,
        val noPrinter: Boolean,
    ) : PrintOutcome
}

/**
 * Totals reported by the terminal for one [currency] in a `GetTotalsResponse` or `ReconciliationResponse`.
 *
 * @property currency ISO 4217 currency code (`PaymentCurrency`).
 * @property debitMinor Total approved payment amount (`TransactionType.DEBIT`), in minor units.
 * @property debitCount Number of approved debit transactions.
 * @property creditMinor Total accepted refund/reversal amount (`TransactionType.CREDIT` or `REVERSE_DEBIT`), in minor units.
 * @property creditCount Number of accepted credit/reversal transactions.
 */
data class TerminalCurrencyTotals(
    val currency: String,
    val debitMinor: Long = 0,
    val debitCount: Int = 0,
    val creditMinor: Long = 0,
    val creditCount: Int = 0,
)

/** The result of [TerminalClient.getTotals] or [TerminalClient.reconcile]. */
sealed interface TerminalTotalsOutcome {
    /**
     * The terminal returned its totals for the reconciliation period.
     *
     * @property poiReconciliationId The terminal's reconciliation period identifier (`POIReconciliationID`), if reported.
     * @property totals Aggregated totals per currency, sorted by currency code.
     * @property closedPeriod True when the request closed the reconciliation period (`SaleReconciliation`).
     */
    data class Answered(
        val poiReconciliationId: String?,
        val totals: List<TerminalCurrencyTotals>,
        val closedPeriod: Boolean,
    ) : TerminalTotalsOutcome

    /**
     * The totals request failed.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : TerminalTotalsOutcome
}

/**
 * The result of [TerminalClient.diagnose], a connection and configuration check that charges nothing.
 *
 * @property reachable True when the terminal answered with `Result` `Success`, which proves that the host, POIID and
 *   shared key are all right.
 * @property fault Why it failed (the delivery's fault, or the terminal's refusal); null when it is reachable.
 * @property globalStatus The terminal's nexo `GlobalStatus`, e.g. `OK` or `Busy`; null if not reported.
 * @property printerStatus The nexo `PrinterStatus`, e.g. `OK` or `PaperLow`; null when the terminal has no printer (or
 *   is unreachable).
 */
data class DiagnosisResult(
    val reachable: Boolean,
    val fault: Fault?,
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
 * @property reprintReceipts Whether status may request terminal receipt reprinting; unsupported by the Payments app.
 */
data class RecoveryPolicy(
    val attempts: Int = 12,
    val intervalMillis: Long = 5_000,
    val maxInProgressChecks: Int = 120,
    val reprintReceipts: Boolean = true,
)

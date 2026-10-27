package app.minimpos.terminal.client

import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.IdentificationType
import com.adyen.model.nexo.StoredValueAccountID
import com.adyen.model.nexo.StoredValueAccountType
import java.util.concurrent.atomic.AtomicReference

private const val MAX_GIFT_CARD_NUMBER_LENGTH = 30
private const val MAX_PROVIDER_LENGTH = 64
private const val LAST_FOUR = 4

/** How a gift card reaches the terminal (`StoredValueAccountID.EntryMode`). */
enum class GiftCardEntryMode {
    /** Scanned on the POS before the request; the number and expiry date travel in the request (`Scanned`). */
    SCANNED,

    /** Swiped on the terminal, which combines the card with the request (`MagStripe`). */
    MAG_STRIPE,

    /** Keyed in on the terminal (`Keyed`). */
    KEYED,
}

/**
 * The gift card one request addresses (`StoredValueAccountID` with `StoredValueAccountType=GiftCard` and
 * `IdentificationType=PAN`). Swiped and keyed cards are read by the terminal, so the request carries an empty
 * `StoredValueID`, as Adyen documents. A scanned card's number is transient: it is consumed once while building the
 * request, never part of string conversion, and status recovery never needs it. Deliberately not a data class.
 * Safe to consume or discard from any thread.
 *
 * @property provider Exact `StoredValueProvider`, such as `givex`, `svs`, `valuelink` or a configured Intersolve or
 *   local card type; lowercase letters, digits and underscores.
 * @property entryMode How the card reaches the terminal.
 * @property expiryDate A scanned card's expiry date as `MMYY`; null for swiped and keyed cards.
 */
class GiftCardAccount private constructor(
    val provider: String,
    val entryMode: GiftCardEntryMode,
    number: String?,
    val expiryDate: String?,
) {
    private val value = AtomicReference(number)

    /** The scanned number's last four digits, safe to show and keep; null for swiped and keyed cards, whose number the app never sees. */
    val lastFour: String? = number?.takeLast(LAST_FOUR)

    init {
        require(provider.length <= MAX_PROVIDER_LENGTH && provider.matches(PROVIDER)) { "Invalid gift card provider" }
    }

    /** Erases the retained number when an attempt is abandoned, including before a request could be built. */
    fun discard() {
        value.set(null)
    }

    /** This card as nexo's `StoredValueAccountID`, consuming a scanned number. */
    internal fun toNexo(): StoredValueAccountID =
        StoredValueAccountID().apply {
            storedValueAccountType = StoredValueAccountType.GIFT_CARD
            storedValueProvider = provider
            identificationType = IdentificationType.PAN
            entryMode +=
                when (this@GiftCardAccount.entryMode) {
                    GiftCardEntryMode.SCANNED -> EntryModeType.SCANNED
                    GiftCardEntryMode.MAG_STRIPE -> EntryModeType.MAG_STRIPE
                    GiftCardEntryMode.KEYED -> EntryModeType.KEYED
                }
            storedValueID =
                if (this@GiftCardAccount.entryMode == GiftCardEntryMode.SCANNED) {
                    checkNotNull(value.getAndSet(null)) { "Gift card number no longer available" }
                } else {
                    ""
                }
            this.expiryDate = this@GiftCardAccount.expiryDate
        }

    override fun toString(): String = "GiftCardAccount(provider=$provider, entryMode=$entryMode, number=[redacted])"

    /** Building the three documented entry methods. */
    companion object {
        private val PROVIDER = Regex("[a-z0-9_]+")
        private val EXPIRY = Regex("(0[1-9]|1[0-2])[0-9]{2}")

        /**
         * A card scanned on the POS with its [number] (ASCII digits) and [expiryDate] (`MMYY`), both required by
         * Adyen for scanned entry.
         *
         * @throws IllegalArgumentException for an invalid provider, number or expiry date.
         */
        fun scanned(
            provider: String,
            number: String,
            expiryDate: String,
        ): GiftCardAccount {
            require(number.length in LAST_FOUR..MAX_GIFT_CARD_NUMBER_LENGTH && number.all { it in '0'..'9' }) {
                "Invalid gift card number"
            }
            require(expiryDate.matches(EXPIRY)) { "Invalid gift card expiry date" }
            return GiftCardAccount(provider, GiftCardEntryMode.SCANNED, number, expiryDate)
        }

        /**
         * A card the terminal reads: swiped ([GiftCardEntryMode.MAG_STRIPE]) or keyed ([GiftCardEntryMode.KEYED]).
         *
         * @throws IllegalArgumentException for an invalid provider or [GiftCardEntryMode.SCANNED].
         */
        fun onTerminal(
            provider: String,
            entryMode: GiftCardEntryMode,
        ): GiftCardAccount {
            require(entryMode != GiftCardEntryMode.SCANNED) { "Scanned cards need their number" }
            return GiftCardAccount(provider, entryMode, null, null)
        }
    }
}

/** What a gift-card payment request does with the card (`PaymentRequest` with a StoredValue instrument). */
enum class GiftCardUse {
    /** Pays with the card's value (`PaymentType=Normal`); a short balance may be approved in part. */
    REDEEM,

    /** Pays the card's value out to the shopper (`PaymentType=Normal`, `redemptionType=cashback`). */
    CASH_OUT,

    /** Unreferenced refund onto the card (`PaymentType=Refund`). */
    REFUND,
}

/**
 * A gift card a payment request is made with.
 *
 * @property account The card.
 * @property use What the request does with it.
 */
data class GiftCardPayment(
    val account: GiftCardAccount,
    val use: GiftCardUse,
)

/** A gift-card management operation (`StoredValueRequest.StoredValueData.StoredValueTransactionType`). */
enum class StoredValueOperation {
    /** Activates a card, loading an initial amount (or none, with zero). */
    ACTIVATE,

    /** Loads an amount onto an active card. */
    LOAD,

    /** Undoes the card's most recent load within 24 hours (`Reverse`), referring to that load. */
    UNDO_LOAD,

    /** Deactivates the card (`Unload` with zero), documented for Givex only. */
    DEACTIVATE,
}

/**
 * A gift-card management request, see [TerminalClient.storedValue], sent with message category `StoredValue`.
 * Amounts are in minor units of [currency].
 *
 * @property operation What to do.
 * @property account The card; null only for [StoredValueOperation.UNDO_LOAD], which refers to the load instead.
 * @property amountMinor `ItemAmount`: the amount activated or loaded; zero for activation without a load and for
 *   deactivation; for an undo, the amount loaded (only used to validate it).
 * @property currency ISO 4217 code.
 * @property merchantReference The request's own `SaleTransactionID`.
 * @property originalTransactionId For an undo, the load's transaction ID, preferably its PSP reference with a leading
 *   dot (`.NC6HT9CRT65ZGN82`); null otherwise.
 * @property originalTimestamp For an undo, the load's `POITransactionID.TimeStamp` exactly as received; null otherwise.
 * @throws IllegalArgumentException when the fields do not fit [operation].
 */
data class StoredValueParams(
    val operation: StoredValueOperation,
    val account: GiftCardAccount?,
    val amountMinor: Long,
    val currency: String,
    val merchantReference: String,
    val originalTransactionId: String? = null,
    val originalTimestamp: String? = null,
) {
    init {
        val undo = operation == StoredValueOperation.UNDO_LOAD
        require(undo == (account == null)) { "Only an undo refers to its load instead of a card" }
        require(undo == (originalTransactionId != null && originalTimestamp != null)) { "Only an undo names an original load" }
        require(amountMinor >= 0) { "Negative gift card amount" }
        require(operation != StoredValueOperation.LOAD || amountMinor > 0) { "A load needs an amount" }
        require(operation != StoredValueOperation.DEACTIVATE || amountMinor == 0L) { "Deactivation carries no amount" }
    }
}

/** The result of [TerminalClient.balance], a non-financial gift-card balance inquiry. */
sealed interface GiftCardBalance {
    /**
     * The card's balance.
     *
     * @property balanceMinor `PaymentAccountStatus.CurrentBalance` in minor units of [currency].
     * @property currency ISO 4217 code of the balance.
     * @property lastFour Last four digits the terminal reported or the scanned number ended with; null when unknown.
     */
    data class Read(
        val balanceMinor: Long,
        val currency: String,
        val lastFour: String?,
    ) : GiftCardBalance

    /**
     * The terminal answered without a balance (refused, cancelled, inactive card).
     *
     * @property decline Why, from the answer's ErrorCondition.
     * @property message The terminal's own words, verbatim; null when it said nothing.
     */
    data class Refused(
        val decline: Decline?,
        val message: ExternalText?,
    ) : GiftCardBalance

    /**
     * No usable answer; nothing changed on the card either way.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : GiftCardBalance
}

/** The last four digits in a masked or full card identifier, safe to keep; null when it has fewer than four digits. */
internal fun lastFourDigits(identifier: String?): String? =
    identifier
        ?.filter { it in '0'..'9' }
        ?.takeIf { it.length >= LAST_FOUR }
        ?.takeLast(LAST_FOUR)

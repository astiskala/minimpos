package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.ids.Ids
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.ScanWallet
import app.minimpos.core.shopper.ShopperReferences
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.ShopperSessionFlow
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.client.VirtualReceipt
import app.minimpos.terminal.client.VirtualReceiptLine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * App-localized text for the optional shopper-facing terminal session.
 *
 * @property order Header above the item list.
 * @property total Label for the total below the item list.
 * @property confirmOrder Title of the order confirmation prompt.
 * @property confirm Label accepting the order.
 * @property cancel Label declining the order.
 * @property email Prompt for an email address before payment.
 * @property rating Prompt for a 0-10 rating after approval.
 */
data class ShopperPromptTexts(
    val order: String,
    val total: String,
    val confirmOrder: String,
    val confirm: String,
    val cancel: String,
    val email: String,
    val rating: String,
)

/**
 * A payment being checked out ([SaleSession.checkout]): what the operator entered, with the payment settings that
 * decide which fields apply, and the one set of rules for what payment it becomes ([paymentStart]): the shopper
 * reference, saving the card, tipping on the receipt and whether it can be paid at all.
 *
 * @property form What the operator entered.
 * @property payment The payment settings, which decide the fields shown.
 * @property totals The cart priced with the current tax settings; its gross total is charged.
 * @property currency The currency charged.
 * @property kind A sale, or a pre-authorisation that only holds the amount.
 * @property printerAvailable Whether printing is offered, which tipping on the receipt needs.
 * @property linksAvailable Whether payment links can be created
 *   ([app.minimpos.app.terminal.TerminalSetup.paymentLinks]).
 * @property sessionRevision Revision of the coherent session snapshot; null for a checkout made without a session.
 * @property wallets Explicitly configured scanned wallets for this currency; empty when unavailable.
 * @property terminalTippingAvailable Whether the destination supports native terminal tipping (`AskGratuity`).
 * @property shopperDisplayAvailable Whether the destination supports shopper-facing terminal display and input requests.
 * @property shopperTexts App-localized terminal prompts; required when [shopperDisplayAvailable].
 * @property emailOnTerminal Whether the terminal asks for an email when none was entered
 *   ([app.minimpos.app.data.settings.AppSettings.asksEmailOnTerminal]).
 * @property audioGuidanceMinimumSoftware Minimum supported software for the resolved model; null when unsupported or unknown.
 * @property audioGuidanceSimulated Whether the choice demonstrates the flow without physical terminal requirements.
 * @property audioGuidanceTarget Resolved terminal POIID; binds the per-shopper confirmation to that terminal.
 */
data class Checkout(
    val form: CheckoutForm = CheckoutForm(),
    val payment: PaymentSettings = PaymentSettings(),
    val totals: CartTotals = Cart().totals(TaxMode.INCLUSIVE),
    val currency: CurrencySpec = PaymentSettings.FALLBACK_CURRENCY_SPEC,
    val kind: SaleKind = SaleKind.SALE,
    val printerAvailable: Boolean = false,
    val linksAvailable: Boolean = false,
    internal val sessionRevision: Long? = null,
    val wallets: List<ScanWallet> = emptyList(),
    val terminalTippingAvailable: Boolean = true,
    val shopperDisplayAvailable: Boolean = false,
    val shopperTexts: ShopperPromptTexts? = null,
    val emailOnTerminal: Boolean = false,
    val audioGuidanceMinimumSoftware: String? = null,
    val audioGuidanceSimulated: Boolean = false,
    val audioGuidanceTarget: String? = null,
) {
    /** Whether the amount is only held (a pre-authorisation) rather than charged. */
    val preAuthorisation: Boolean get() = kind == SaleKind.PRE_AUTHORISATION

    /** Whether the optional transaction-reference field is shown. */
    val showsTransactionReference: Boolean get() = payment.askTransactionReference

    /** Whether the email field is shown. */
    val showEmail: Boolean get() = payment.captureEmailBefore

    /** Whether the tax row is shown with the total, which only a sale that charges tax has. */
    val showsTax: Boolean get() = payment.chargeTax

    /** Whether a typed email's receipt is also sent automatically, as the email field explains. */
    val emailsAutomatically: Boolean get() = payment.captureEmailBefore && payment.autoSendEmail

    /** Whether the card is saved under the email rather than the customer reference, as the saving switch explains. */
    val savesCardByEmail: Boolean get() = payment.shopperReferenceSource == ShopperReferenceSource.EMAIL

    /** Whether the customer reference field is shown (exactly when it is the shopper reference). */
    val showCustomerReference: Boolean get() = payment.asksCustomerReference

    /** False when an email was typed that is not a valid address; blank is valid. */
    val emailValid: Boolean get() = form.email.isBlank() || ShopperReferences.isValidEmail(form.email)

    /** False when the typed merchant reference is longer than [MAX_REFERENCE_LENGTH]. */
    val referenceValid: Boolean get() = form.transactionReference.trim().length <= MAX_REFERENCE_LENGTH

    /** The entered customer reference; null when none was entered or none is asked for (the email is the shopper reference). */
    val customerReference: String? get() = form.customerReference.trim().takeIf { showCustomerReference && it.isNotEmpty() }

    /** False when the entered customer reference is not a valid Adyen shopper reference; none entered is valid. */
    val customerReferenceValid: Boolean get() = customerReference?.let(ShopperReferences::isValidReference) != false

    /**
     * The Adyen shopperReference sent with the payment, whether or not the card is saved: made as
     * [PaymentSettings.shopperReferenceSource] says, when the entered data allows one; null otherwise.
     */
    val shopperReference: String?
        get() =
            when (payment.shopperReferenceSource) {
                ShopperReferenceSource.CUSTOMER_REFERENCE -> {
                    customerReference?.takeIf(ShopperReferences::isValidReference)
                }

                ShopperReferenceSource.EMAIL -> {
                    form.email.trim().takeIf { ShopperReferences.isValidEmail(it) }?.let {
                        ShopperReferences.fromEmail(it, payment.emailReferenceMode, payment.emailReferenceSalt)
                    }
                }

                ShopperReferenceSource.NONE -> {
                    null
                }
            }

    /**
     * Whether the card can be saved: saving is offered ([PaymentSettings.offerCardSaving]) and there is a
     * [shopperReference] to save it under; the switch is hidden otherwise.
     */
    val canTokenize: Boolean get() = payment.offerCardSaving && shopperReference != null

    /**
     * Whether the card will be saved: the operator's choice, else the settings default (the pre-authorisation one for
     * [preAuthorisation]), when [canTokenize].
     */
    val tokenize: Boolean
        get() = canTokenize && (form.tokenize ?: if (preAuthorisation) payment.preAuthTokenizeDefaultOn else payment.tokenizeDefaultOn)

    /** Whether "Tip on the receipt" is offered: for a sale, while a printer is available to print the receipt. */
    val canTipOnReceipt: Boolean get() = payment.receiptTipping != ReceiptTipping.DISABLED && !preAuthorisation && printerAvailable

    /** Whether native terminal tipping (`AskGratuity`) is offered: for a sale on a destination that supports it. */
    val canTipOnTerminal: Boolean
        get() = payment.terminalTipping != ReceiptTipping.DISABLED && !preAuthorisation && terminalTippingAvailable

    /** Whether the sale prompts for a tip on the terminal: mutually exclusive with [tipOnReceipt]. */
    val askGratuity: Boolean
        get() =
            canTipOnTerminal &&
                form.tipOnReceipt != true &&
                (form.askGratuity ?: (payment.terminalTipping == ReceiptTipping.DEFAULT_ON))

    /** Whether the sale is taken for tipping on the receipt: the operator's choice, else the settings default when not [askGratuity]. */
    val tipOnReceipt: Boolean
        get() =
            canTipOnReceipt &&
                !askGratuity &&
                (form.tipOnReceipt ?: (payment.receiptTipping == ReceiptTipping.DEFAULT_ON))

    /** Whether Adyen Giving (`AskGiving`) is offered: for an immediate-charge sale on a destination that supports it. */
    val canAskGiving: Boolean
        get() = payment.askGiving != ReceiptTipping.DISABLED && !preAuthorisation && !tipOnReceipt && terminalTippingAvailable

    /** Whether the sale prompts for an Adyen Giving charity donation after purchase. */
    val askGiving: Boolean
        get() = canAskGiving && (form.askGiving ?: (payment.askGiving == ReceiptTipping.DEFAULT_ON))

    /** Whether MOTO card entry is available at checkout on this destination. */
    val canUseMoto: Boolean get() = payment.allowMoto && terminalTippingAvailable

    /** Whether Manual Key Entry (MKE) is available at checkout on this destination. */
    val canUseMke: Boolean get() = payment.allowMke && terminalTippingAvailable

    /** Effective card entry mode (`NORMAL` unless the chosen special mode is enabled and supported). */
    val effectiveCardEntry: CardEntryOverride
        get() =
            when (form.cardEntryOverride) {
                CardEntryOverride.MOTO -> if (canUseMoto) CardEntryOverride.MOTO else CardEntryOverride.NORMAL
                CardEntryOverride.MKE -> if (canUseMke) CardEntryOverride.MKE else CardEntryOverride.NORMAL
                CardEntryOverride.NORMAL -> CardEntryOverride.NORMAL
            }

    /** True when the operator selected MOTO or MKE, requiring a fraud/liability warning and Manager PIN approval. */
    val requiresSpecialEntryApproval: Boolean get() = effectiveCardEntry.needsManagerApproval

    /** What a tender other than the terminal leaves out of this sale: its tip and its donation invitation, when chosen. */
    private val dropped: Set<DroppedOption>
        get() =
            buildSet {
                if (tipOnReceipt || askGratuity) add(DroppedOption.TIP)
                if (askGiving) add(DroppedOption.DONATION)
            }

    /**
     * What must happen before this checkout is paid with [tender], the one reading of each tender's confirmations:
     * what the tender drops of what the shopper would be offered (a payment link, scanned wallet or token takes no tip
     * and invites no donation, as [linkStart], [walletStart] and [savedCardStart] leave them out), the special
     * card-entry warning, and Manager approval, which every token charge and MOTO/MKE payment needs.
     */
    fun ceremony(tender: Tender): TenderCeremony =
        when (tender) {
            Tender.TERMINAL -> {
                TenderCeremony(
                    emptySet(),
                    specialEntryWarning = requiresSpecialEntryApproval,
                    managerApproval = requiresSpecialEntryApproval,
                )
            }

            Tender.LINK, Tender.WALLET -> {
                TenderCeremony(dropped, specialEntryWarning = false, managerApproval = false)
            }

            Tender.TOKEN -> {
                TenderCeremony(dropped, specialEntryWarning = false, managerApproval = true)
            }
        }

    /** Resolved per-payment terminal tipping options when [askGratuity] is active; null otherwise. */
    fun terminalTipOptions(): TerminalTipOptions? {
        if (!askGratuity) return null
        val preTaxAmount =
            totals.amounts.net.takeIf {
                payment.tipOnPreTax && payment.chargeTax && it in 1..<totals.amounts.gross
            }
        if (!payment.dynamicTipOverride && preTaxAmount == null) return TerminalTipOptions()
        val suggestions =
            if (payment.dynamicTipOverride && totals.amounts.gross < payment.lowBasketThresholdMinor) {
                TerminalTipSuggestions.FixedAmounts(payment.fixedTipAmountsMinor, payment.allowCustomTip)
            } else {
                TerminalTipSuggestions.Percentages(payment.tipPercentages, payment.allowCustomTip)
            }
        return TerminalTipOptions(suggestions = suggestions, amountToTipOnMinor = preTaxAmount)
    }

    /** Resolved chained shopper display and input session for external terminals; null when disabled or unsupported. */
    fun shopperSessionFlow(merchantReference: String): ShopperSessionFlow? {
        if (!shopperDisplayAvailable) return null
        val texts = checkNotNull(shopperTexts)
        val virtualReceipt =
            if (payment.showVirtualReceipt) {
                VirtualReceipt(
                    header = texts.order,
                    lines =
                        totals.lines.map {
                            VirtualReceiptLine(it.line.quantity, it.line.name, currency.code, it.amounts.gross)
                        },
                    subtotalMinor = totals.amounts.net.takeIf { payment.chargeTax },
                    taxMinor = totals.amounts.tax.takeIf { payment.chargeTax },
                    totalMinor = totals.amounts.gross,
                    currency = currency.code,
                    totalLabel = texts.total,
                )
            } else {
                null
            }
        val confirmPrompt =
            if (payment.confirmBasketOnTerminal) {
                texts.confirmOrder to "${currency.code} ${currency.toMajor(totals.amounts.gross).toPlainString()}"
            } else {
                null
            }
        val emailPrompt = texts.email.takeIf { emailOnTerminal && form.email.isBlank() }
        val qrReceipt = merchantReference.takeIf { payment.showTerminalQrReceipt }
        val ratingPrompt = (texts.rating to (null as String?)).takeIf { payment.askShopperRating }
        if (listOf(virtualReceipt, confirmPrompt, emailPrompt, qrReceipt, ratingPrompt).all { it == null }) return null
        return ShopperSessionFlow(
            virtualReceipt = virtualReceipt,
            confirmationPrompt = confirmPrompt,
            confirmLabel = texts.confirm,
            cancelLabel = texts.cancel,
            emailPrompt = emailPrompt,
            qrReceipt = qrReceipt,
            ratingPrompt = ratingPrompt,
        )
    }

    /** Whether this shopper's accessible flow is compatible and its setup was explicitly confirmed for this terminal. */
    val audioGuidanceReady: Boolean
        get() =
            !form.audioGuidance || (
                (audioGuidanceSimulated || audioGuidanceMinimumSoftware != null) && audioGuidanceTarget != null &&
                    form.audioGuidanceConfirmedFor == audioGuidanceTarget &&
                    !askGratuity && !tipOnReceipt && !askGiving && effectiveCardEntry == CardEntryOverride.NORMAL
            )

    /** Whether "Pay" is enabled: something to charge and every entered field valid. */
    val canPay: Boolean get() =
        !totals.isEmpty && totals.amounts.gross > 0 && emailValid && referenceValid && customerReferenceValid &&
            audioGuidanceReady

    /**
     * The payment to start, or null unless [canPay]. A blank merchant reference is generated from [now] in [zone] with
     * the configured prefix; the [shopperReference] is always sent, and the card saved only when [tokenize] says so.
     */
    fun paymentStart(
        now: Instant,
        zone: ZoneId,
    ): PaymentStart? {
        if (!canPay) return null
        val reference = form.transactionReference.trim().ifEmpty { Ids.transactionReference(payment.referencePrefix, now, zone) }
        val allowPartial = payment.allowPartialAuthorisation && !preAuthorisation && !tipOnReceipt && !form.audioGuidance
        return PaymentStart(
            totals = totals,
            currency = currency,
            merchantReference = reference,
            customerReference = customerReference,
            shopperEmail = form.email.trim().takeIf { it.isNotEmpty() },
            tokenization = TokenizationRequest(payment.recurringModel()).takeIf { tokenize },
            kind = kind,
            tipOnReceipt = tipOnReceipt,
            shopperReference = shopperReference,
            sessionRevision = sessionRevision,
            terminalTip = terminalTipOptions(),
            shopperFlow = if (form.audioGuidance) null else shopperSessionFlow(reference),
            allowPartialAuthorisation = allowPartial,
            askGiving = askGiving,
            cardEntryOverride = effectiveCardEntry,
            audioGuidance = form.audioGuidance,
            audioGuidancePoiId = audioGuidanceTarget.takeIf { form.audioGuidance },
        )
    }

    /**
     * Whether a payment link is offered instead of the terminal: for a sale (a pre-authorisation always goes to the
     * terminal) that [canPay], while links are [linksAvailable].
     */
    val canSendLink: Boolean get() = !form.audioGuidance && !preAuthorisation && linksAvailable && canPay

    /**
     * The payment link to create, or null unless [canSendLink]: the [paymentStart] at [now] in [zone] without tipping on
     * the receipt (there is no paper to write on), working for [PaymentSettings.linkExpiryHours] but at most
     * [MAX_LINK_LIFETIME].
     */
    fun linkStart(
        now: Instant,
        zone: ZoneId,
    ): PaymentLinkStart? {
        if (!canSendLink) return null
        val start =
            paymentStart(now, zone)?.copy(
                tipOnReceipt = false,
                terminalTip = null,
                shopperFlow = null,
                allowPartialAuthorisation = false,
                requestedAmountMinor = totals.amounts.gross,
                askGiving = false,
                cardEntryOverride = CardEntryOverride.NORMAL,
            ) ?: return null
        val lifetime = minOf(Duration.ofHours(payment.linkExpiryHours.toLong()), MAX_LINK_LIFETIME)
        return PaymentLinkStart(start, now.plus(lifetime))
    }

    /**
     * The saved-card charge to start for [card] at [now] in [zone], or null unless this checkout is an immediate-charge
     * sale with a [shopperReference] that [canPay] and [card] supports the configured recurring processing model.
     */
    fun savedCardStart(
        card: StoredCard,
        now: Instant,
        zone: ZoneId,
        fromCheckout: Boolean = false,
    ): SavedCardStart? {
        val model = payment.recurringModel().value
        if (form.audioGuidance) return null
        if (preAuthorisation || shopperReference == null || !card.supports(model)) return null
        val start = paymentStart(now, zone)?.forSavedCard() ?: return null
        return SavedCardStart(start, card, model, fromCheckout = fromCheckout)
    }

    /** Whether scanned wallets are offered, independently of entered-field validation. */
    val offersScanWallet: Boolean get() = !form.audioGuidance && !preAuthorisation && wallets.isNotEmpty()

    /** Whether a configured scanned wallet can pay this checkout now. */
    val canScanWallet: Boolean get() = offersScanWallet && canPay

    /** Immediate-charge sale at [now] in [zone], retaining saving consent but never receipt tipping; null when unavailable. */
    fun walletStart(
        now: Instant,
        zone: ZoneId,
    ): PaymentStart? =
        paymentStart(now, zone)
            ?.copy(
                tipOnReceipt = false,
                terminalTip = null,
                shopperFlow = null,
                allowPartialAuthorisation = false,
                askGiving = false,
                cardEntryOverride = CardEntryOverride.NORMAL,
            ).takeIf { canScanWallet }

    /** Whether [actual] has the same priced cart, currency, form and payment settings as this checkout, and still offers [wallet]. */
    internal fun matchesWalletCheckout(
        actual: Checkout,
        wallet: ScanWallet,
    ): Boolean =
        wallet in actual.wallets &&
            actual.currency == currency &&
            actual.totals == totals &&
            actual.form == form &&
            actual.payment == payment

    /** Checkout limits and continuation of stored partially paid sales. */
    companion object {
        /**
         * Next card tender for a stored partially paid [sale], or null when no balance can be collected.
         * Reuses its references and currency, without repeating consent, tips or shopper-display prompts.
         */
        fun remainingPayment(sale: SaleEntity): PaymentStart? {
            val remaining = sale.remainingMinor
            if (remaining <= 0) return null
            return PaymentStart(
                totals = Cart().totals(TaxMode.INCLUSIVE),
                currency = CurrencySpec.of(sale.currency),
                merchantReference = sale.merchantReference,
                customerReference = sale.customerReference,
                shopperEmail = sale.shopperEmail,
                tokenization = null,
                kind = sale.kind,
                tipOnReceipt = false,
                shopperReference = sale.shopperReference,
                allowPartialAuthorisation = true,
                requestedAmountMinor = remaining,
                existingSaleId = sale.id,
            )
        }

        /** Longest merchant reference accepted, in characters (Adyen's limit for `merchantReference`). */
        const val MAX_REFERENCE_LENGTH = 80

        /**
         * The longest a payment link may work: Adyen's 70 days, less a few minutes, so a clock slightly ahead of
         * Adyen's cannot ask for more.
         */
        val MAX_LINK_LIFETIME: Duration = Duration.ofDays(70).minusMinutes(5)
    }
}

/** A way to pay a checkout: on the terminal, by payment link, with a scanned wallet code, or with a saved card (Charge token). */
enum class Tender {
    /** Card on the terminal, possibly with MOTO or MKE entry. */
    TERMINAL,

    /** A payment link the shopper pays on Adyen's page. */
    LINK,

    /** A shopper-presented wallet code scanned at checkout. */
    WALLET,

    /** A card saved for the shopper reference, charged through Checkout. */
    TOKEN,
}

/** What a tender leaves out of a sale that the shopper would otherwise be offered, see [Checkout.ceremony]. */
enum class DroppedOption {
    /** Tipping on the receipt or on the terminal. */
    TIP,

    /** The Adyen Giving charity donation invitation. */
    DONATION,
}

/**
 * What must happen, in order, before a checkout is paid with one tender ([Checkout.ceremony]).
 *
 * @property drops What the tender leaves out, which the operator confirms first; empty when nothing is lost.
 * @property specialEntryWarning Whether the MOTO/MKE fraud and liability warning is confirmed next.
 * @property managerApproval Whether Manager approval is asked for last, which the operation checks again before sending.
 */
data class TenderCeremony(
    val drops: Set<DroppedOption>,
    val specialEntryWarning: Boolean,
    val managerApproval: Boolean,
)

/**
 * Whether a payment taken with this card entry needs Manager approval: MOTO and MKE do, ordinary entry does not. The
 * one rule for both the checkout that asks for approval and the payment that checks it again before sending.
 */
internal val CardEntryOverride.needsManagerApproval: Boolean get() = this != CardEntryOverride.NORMAL

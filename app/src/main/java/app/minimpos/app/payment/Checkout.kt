package app.minimpos.app.payment

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
import app.minimpos.terminal.client.ShopperSessionFlow
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.client.VirtualReceipt
import app.minimpos.terminal.client.VirtualReceiptLine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

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
 */
data class Checkout(
    val form: CheckoutForm = CheckoutForm(),
    val payment: PaymentSettings = PaymentSettings(),
    val totals: CartTotals = Cart().totals(TaxMode.INCLUSIVE),
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val kind: SaleKind = SaleKind.SALE,
    val printerAvailable: Boolean = false,
    val linksAvailable: Boolean = false,
    internal val sessionRevision: Long? = null,
    val wallets: List<ScanWallet> = emptyList(),
    val terminalTippingAvailable: Boolean = true,
    val shopperDisplayAvailable: Boolean = false,
) {
    /** Whether the amount is only held (a pre-authorisation) rather than charged. */
    val preAuthorisation: Boolean get() = kind == SaleKind.PRE_AUTHORISATION

    /** Whether the email field is shown. */
    val showEmail: Boolean get() = payment.captureEmailBefore

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

    /** Resolved per-payment terminal tipping options when [askGratuity] is active; null otherwise. */
    fun terminalTipOptions(): TerminalTipOptions? {
        if (!askGratuity) return null
        val preTaxAmount =
            currency.toMajor(totals.amounts.net).takeIf {
                payment.tipOnPreTax && payment.chargeTax && totals.amounts.net in 1 until totals.amounts.gross
            }
        if (!payment.dynamicTipOverride && preTaxAmount == null) return TerminalTipOptions()
        val suggestions =
            if (payment.dynamicTipOverride && totals.amounts.gross < payment.lowBasketThresholdMinor) {
                TerminalTipSuggestions.FixedAmounts(payment.fixedTipAmountsMinor, payment.allowCustomTip)
            } else {
                TerminalTipSuggestions.Percentages(payment.tipPercentages, payment.allowCustomTip)
            }
        return TerminalTipOptions(suggestions = suggestions, amountToTipOn = preTaxAmount)
    }

    /** Resolved chained shopper display and input session for external terminals; null when disabled or unsupported. */
    fun shopperSessionFlow(merchantReference: String): ShopperSessionFlow? {
        if (!shopperDisplayAvailable) return null
        val virtualReceipt =
            if (payment.showVirtualReceipt) {
                VirtualReceipt(
                    header = "Order",
                    lines =
                        totals.lines.map {
                            VirtualReceiptLine(it.line.quantity, it.line.name, currency.code, currency.toMajor(it.amounts.gross))
                        },
                    subtotal = currency.toMajor(totals.amounts.net).takeIf { payment.chargeTax },
                    tax = currency.toMajor(totals.amounts.tax).takeIf { payment.chargeTax },
                    total = currency.toMajor(totals.amounts.gross),
                    currency = currency.code,
                )
            } else {
                null
            }
        val confirmPrompt =
            if (payment.confirmBasketOnTerminal) {
                "Confirm order" to "${currency.code} ${currency.toMajor(totals.amounts.gross).toPlainString()}"
            } else {
                null
            }
        val emailPrompt = "Enter your email address:".takeIf { payment.collectEmailOnTerminal && form.email.isBlank() }
        val qrReceipt = merchantReference.takeIf { payment.showTerminalQrReceipt }
        val ratingPrompt = ("Rate your experience (0–10)" to (null as String?)).takeIf { payment.askShopperRating }
        if (listOf(virtualReceipt, confirmPrompt, emailPrompt, qrReceipt, ratingPrompt).all { it == null }) return null
        return ShopperSessionFlow(
            virtualReceipt = virtualReceipt,
            confirmationPrompt = confirmPrompt,
            emailPrompt = emailPrompt,
            qrReceipt = qrReceipt,
            ratingPrompt = ratingPrompt,
        )
    }

    /** Amount requested on the first tender in minor units: operator-entered split amount when valid, else full gross total. */
    val requestedAmountMinor: Long
        get() =
            form.splitAmountMinor?.takeIf { !preAuthorisation && it in 1 until totals.amounts.gross }
                ?: totals.amounts.gross

    /** Whether "Pay" is enabled: something to charge and every entered field valid. */
    val canPay: Boolean get() = !totals.isEmpty && totals.amounts.gross > 0 && emailValid && referenceValid && customerReferenceValid

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
        val allowPartial = payment.allowPartialAuthorisation && !preAuthorisation && !tipOnReceipt
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
            shopperFlow = shopperSessionFlow(reference),
            allowPartialAuthorisation = allowPartial,
            requestedAmountMinor = requestedAmountMinor,
            askGiving = askGiving,
        )
    }

    /**
     * Whether a payment link is offered instead of the terminal: for a sale (a pre-authorisation always goes to the
     * terminal) that [canPay], while links are [linksAvailable].
     */
    val canSendLink: Boolean get() = !preAuthorisation && linksAvailable && canPay

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
            ) ?: return null
        val lifetime = minOf(Duration.ofHours(payment.linkExpiryHours.toLong()), MAX_LINK_LIFETIME)
        return PaymentLinkStart(start, now.plus(lifetime))
    }

    /** Whether scanned wallets are offered, independently of entered-field validation. */
    val offersScanWallet: Boolean get() = !preAuthorisation && wallets.isNotEmpty()

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

    /** Field limits. */
    companion object {
        /** Longest merchant reference accepted, in characters (Adyen's limit for `merchantReference`). */
        const val MAX_REFERENCE_LENGTH = 80

        /**
         * The longest a payment link may work: Adyen's 70 days, less a few minutes, so a clock slightly ahead of
         * Adyen's cannot ask for more.
         */
        val MAX_LINK_LIFETIME: Duration = Duration.ofDays(70).minusMinutes(5)
    }
}

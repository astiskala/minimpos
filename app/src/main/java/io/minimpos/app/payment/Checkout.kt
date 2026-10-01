package io.minimpos.app.payment

import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartTotals
import io.minimpos.core.ids.Ids
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.core.tax.TaxMode
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
 */
data class Checkout(
    val form: CheckoutForm = CheckoutForm(),
    val payment: PaymentSettings = PaymentSettings(),
    val totals: CartTotals = Cart().totals(TaxMode.INCLUSIVE),
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val kind: SaleKind = SaleKind.SALE,
    val printerAvailable: Boolean = false,
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

    /** The Adyen shopperReference tokenization would use, if the entered data allows one. */
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
            }

    /** Whether the card can be saved, which needs a [shopperReference]; the switch is disabled otherwise. */
    val canTokenize: Boolean get() = shopperReference != null

    /**
     * Whether the card will be saved: the operator's choice, else the settings default (the pre-authorisation one for
     * [preAuthorisation]), when [canTokenize].
     */
    val tokenize: Boolean
        get() = canTokenize && (form.tokenize ?: if (preAuthorisation) payment.preAuthTokenizeDefaultOn else payment.tokenizeDefaultOn)

    /** Whether "Tip on the receipt" is offered: for a sale, while a printer is available to print the receipt. */
    val canTipOnReceipt: Boolean get() = !preAuthorisation && printerAvailable

    /** Whether the sale is taken for tipping on the receipt: the operator's choice, else the settings default. */
    val tipOnReceipt: Boolean get() = canTipOnReceipt && (form.tipOnReceipt ?: payment.tipOnReceiptDefaultOn)

    /** Whether "Pay" is enabled: something to charge and every entered field valid. */
    val canPay: Boolean get() = !totals.isEmpty && totals.amounts.gross > 0 && emailValid && referenceValid && customerReferenceValid

    /**
     * The payment to start, or null unless [canPay]. A blank merchant reference is generated from [now] in [zone] with
     * the configured prefix; the card is saved only when [tokenize] says so.
     */
    fun paymentStart(
        now: Instant,
        zone: ZoneId,
    ): PaymentStart? {
        if (!canPay) return null
        return PaymentStart(
            totals = totals,
            currency = currency,
            merchantReference = form.transactionReference.trim().ifEmpty { Ids.transactionReference(payment.referencePrefix, now, zone) },
            customerReference = customerReference,
            shopperEmail = form.email.trim().takeIf { it.isNotEmpty() },
            tokenization =
                shopperReference?.takeIf { tokenize }?.let {
                    TokenizationRequest(it, payment.recurringModel(), payment.sendShopperEmail)
                },
            kind = kind,
            tipOnReceipt = tipOnReceipt,
        )
    }

    /** Field limits. */
    companion object {
        /** Longest merchant reference accepted, in characters (Adyen's limit for `merchantReference`). */
        const val MAX_REFERENCE_LENGTH = 80
    }
}

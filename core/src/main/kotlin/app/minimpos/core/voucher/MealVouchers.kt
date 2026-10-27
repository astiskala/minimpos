package app.minimpos.core.voucher

import java.time.DayOfWeek
import java.time.LocalDate

/** How a voucher payment reaches the voucher rather than an ordinary card account. */
enum class VoucherSelection {
    /**
     * The terminal recognizes the voucher card itself and routes it over its own network; the request is an ordinary
     * card payment for the voucher amount.
     */
    TERMINAL,

    /** The app sends `selectedAccountType` `MEAL_VOUCHER` in Base64 `SaleData.SaleToPOIData` (Brazilian PAT). */
    ACCOUNT_TYPE,
}

/** Where the value of an approved voucher payment can be returned. */
enum class VoucherRefund {
    /** Only to the original voucher, by referenced refund of its transaction; never as cash or to another tender. */
    ORIGINAL_VOUCHER,

    /** Not through the app at all: the network does not support refunds, so staff offer another remedy. */
    NONE,
}

/**
 * Meal-voucher markets Mini mPOS offers at the point of sale, with the behavior Adyen documents for each (Brazilian meal
 * vouchers, Meal vouchers in Europe and its best practices, Titres-restaurant; checked October 26, 2026). The market is
 * chosen by the verified Adyen store country, never by language, locale or currency; the merchant still confirms issuer
 * onboarding. The issuer decides acceptance: a matching entry never establishes it.
 *
 * Not offered: Intersolve vouchers (Monizze and Pluxee in Belgium, Lunch Check in Switzerland), because Adyen documents
 * no Terminal API payment request for them, only Stored Value and Checkout examples.
 *
 * @property countries ISO 3166-1 alpha-2 store countries of the market.
 * @property currency ISO 4217 currency of its vouchers.
 * @property selection How a voucher tender reaches the voucher.
 * @property productEligibility Whether only eligible products may be paid; Adyen asks in-person integrations never to
 *   combine eligible and ineligible items in one voucher tender.
 * @property partialAuthorisation Whether the tender asks the issuer to approve a short balance
 *   (`AllowPartialAuthorisation`); otherwise a short balance is refused.
 * @property saleLimitMinor Most voucher value one sale may take, in minor units of [currency]; null when Adyen
 *   documents no amount limit. Daily limits across sales are enforced by the issuer.
 * @property closedDays Days the vouchers cannot be used; public holidays are enforced by the issuer.
 * @property refund Where approved voucher value can be returned.
 */
enum class MealVoucherScheme(
    val countries: Set<String>,
    val currency: String,
    val selection: VoucherSelection,
    val productEligibility: Boolean,
    val partialAuthorisation: Boolean,
    val saleLimitMinor: Long?,
    val closedDays: Set<DayOfWeek>,
    val refund: VoucherRefund,
) {
    /**
     * Brazilian PAT meal and food vouchers. Eligibility is the merchant's MCC and CNAE activity, checked by the issuer,
     * not individual products. A short balance is refused. Adyen documents no refund flow, so returns go only to the
     * original voucher, never as cash.
     */
    BRAZIL_PAT(setOf("BR"), "BRL", VoucherSelection.ACCOUNT_TYPE, false, false, null, emptySet(), VoucherRefund.ORIGINAL_VOUCHER),

    /**
     * Vouchers co-branded with Mastercard or Visa (Edenred in Belgium, Finland, Germany, Luxembourg and Spain; Pluxee
     * in Germany, Luxembourg and Spain; Up Spain). Product eligibility applies, issuers may approve a short balance and
     * refunds follow the standard card flow to the original voucher.
     */
    EUROPE_CARD_SCHEME(
        setOf("BE", "FI", "DE", "LU", "ES"),
        "EUR",
        VoucherSelection.TERMINAL,
        true,
        true,
        null,
        emptySet(),
        VoucherRefund.ORIGINAL_VOUCHER,
    ),

    /**
     * French Titres-restaurant (Conecs: Pluxee, Swile, Bimpli, Up Déjeuner, Glady; Edenred, Swile and Up Déjeuner also
     * over Mastercard). At most EUR 25 a day, never on Sundays or public holidays, eligible products only, no partial
     * approval documented at the point of sale, and Conecs refunds are not supported, so the app never refunds them.
     */
    FRANCE_TITRES_RESTAURANT(
        setOf("FR"),
        "EUR",
        VoucherSelection.TERMINAL,
        true,
        false,
        FRANCE_DAILY_LIMIT_MINOR,
        setOf(DayOfWeek.SUNDAY),
        VoucherRefund.NONE,
    ),
    ;

    /** Whether vouchers may be used on [date], the store's local date. Public holidays are not known locally. */
    fun openOn(date: LocalDate): Boolean = date.dayOfWeek !in closedDays

    /**
     * Most a voucher tender may collect now, in minor units: the [unpaidMinor] purchase, limited for [productEligibility]
     * to [eligibleMinor] (eligible lines' gross) and by [saleLimitMinor], less [collectedMinor] already taken or reserved
     * by this sale's other voucher tenders. Never negative.
     */
    fun limitMinor(
        unpaidMinor: Long,
        eligibleMinor: Long,
        collectedMinor: Long,
    ): Long {
        val eligible = if (productEligibility) eligibleMinor - collectedMinor else unpaidMinor
        val market = saleLimitMinor?.let { it - collectedMinor } ?: unpaidMinor
        return minOf(unpaidMinor, eligible, market).coerceAtLeast(0)
    }

    /** Market lookup by verified store country. */
    companion object {
        /** The market of a verified store in [country] (ISO 3166-1 alpha-2, any case); null where none is offered. */
        fun forCountry(country: String?): MealVoucherScheme? = entries.firstOrNull { country?.uppercase() in it.countries }

        /** Markets whose vouchers are in [currency]; used only to label an offline simulator demonstration. */
        fun demonstrable(currency: String): List<MealVoucherScheme> = entries.filter { it.currency == currency }

        /** The stored [name] of a scheme, or null for an unknown or absent one. */
        fun stored(name: String?): MealVoucherScheme? = entries.firstOrNull { it.name == name }

        /** Brazilian voucher variants of Adyen's test cards. */
        private val BRAZILIAN_VARIANTS =
            setOf("ticket", "sodexo", "vale_refeicao_prepaid", "elo_voucher_br", "visa_voucher_br", "mc_voucher_br")

        /**
         * Whether the terminal reported a Brazilian PAT voucher as the `paymentMethodVariant`, as Adyen's test cards
         * document them; for display only, such as a voucher chosen on the terminal's own screen.
         */
        fun reportedBrazilianVoucher(variant: String?): Boolean = variant?.trim()?.lowercase() in BRAZILIAN_VARIANTS
    }
}

/** EUR 25 in cents: the Titres-restaurant daily spending limit Adyen documents. */
private const val FRANCE_DAILY_LIMIT_MINOR = 2_500L

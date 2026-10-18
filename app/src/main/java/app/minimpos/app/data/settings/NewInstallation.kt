package app.minimpos.app.data.settings

import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.core.tax.StarterTax
import java.util.Locale

/** A text a new installation starts with, named in the operator's language by [NewInstallation]'s text adapter. */
enum class InstallationText {
    /** The receipt's title. */
    RECEIPT_TITLE,

    /** The receipt's footer. */
    RECEIPT_FOOTER,

    /** The subject of receipt emails, with its `{business}` and `{reference}` placeholders. */
    EMAIL_SUBJECT,

    /** The label of the business's tax ID where the country has no regional one. */
    TAX_ID_DEFAULT,

    /** Australian Business Number. */
    TAX_ID_ABN,

    /** New Zealand GST number. */
    TAX_ID_GST,

    /** Singapore GST registration number. */
    TAX_ID_GST_REGISTRATION,

    /** Malaysian SST registration number. */
    TAX_ID_SST_REGISTRATION,

    /** Canadian GST/HST number. */
    TAX_ID_GST_HST,

    /** Mexican RFC. */
    TAX_ID_RFC,

    /** Brazilian CPF/CNPJ. */
    TAX_ID_CPF_CNPJ,

    /** A VAT number, in the [NewInstallation.VAT_COUNTRIES]. */
    TAX_ID_VAT,

    /** The explanation of items marked as charged no tax (Australia's GST-free items). */
    NO_TAX_NOTE,

    /** The explanation of items at the marked tax rate. */
    TAX_MARKER_NOTE,

    /** A standard rate named GST. */
    TAX_GST,

    /** A standard rate named SST. */
    TAX_SST,

    /** A standard rate named IVA. */
    TAX_IVA,

    /** A standard rate named VAT. */
    TAX_VAT,

    /** A standard rate where the country's tax has no regional name. */
    TAX_STANDARD,

    /** The reduced rate. */
    TAX_REDUCED,

    /** The 0% rate, which every catalogue keeps. */
    TAX_ZERO,

    /** The sample receipt's taxed item's rate where the country's standard rate has no regional name. */
    SAMPLE_TAX,
}

/**
 * What a new installation starts with in one country and language, the one owner of every regional default: its
 * settings ([settings]), starter tax rates ([starterTaxRates]) and the names it gives them. Initial values only; saved
 * and transferred settings, and catalogues, are used as written. Pure: the [texts] adapter supplies the words, Android
 * resources in the app and a fixed table in tests.
 *
 * @param country ISO 3166-1 alpha-2 country code, possibly empty, in any case and with surrounding spaces.
 * @param language ISO 639-1 language code, empty for the baseline receipt style; Japanese receipts mark the reduced rate.
 * @param texts Each [InstallationText] in the operator's language.
 */
class NewInstallation(
    country: String,
    language: String = "",
    private val texts: (InstallationText) -> String,
) {
    private val code = country.trim().uppercase(Locale.ROOT)
    private val japanese = language.equals("ja", ignoreCase = true)
    private val starter = StarterTax.forCountry(code)

    /** Whether the receipt marks items charged no tax: Australian GST-free items, outside the Japanese receipt style. */
    private val marksNoTax = code == "AU" && !japanese

    private val taxIdLabel: InstallationText
        get() =
            when (code) {
                "AU" -> InstallationText.TAX_ID_ABN
                "NZ" -> InstallationText.TAX_ID_GST
                "SG" -> InstallationText.TAX_ID_GST_REGISTRATION
                "MY" -> InstallationText.TAX_ID_SST_REGISTRATION
                "CA" -> InstallationText.TAX_ID_GST_HST
                "MX" -> InstallationText.TAX_ID_RFC
                "BR" -> InstallationText.TAX_ID_CPF_CNPJ
                in VAT_COUNTRIES -> InstallationText.TAX_ID_VAT
                else -> InstallationText.TAX_ID_DEFAULT
            }

    private val standardTax: InstallationText
        get() =
            when (code) {
                "AU", "NZ", "SG" -> InstallationText.TAX_GST
                "MY" -> InstallationText.TAX_SST
                "MX" -> InstallationText.TAX_IVA
                in VAT_COUNTRIES -> InstallationText.TAX_VAT
                else -> InstallationText.TAX_STANDARD
            }

    /**
     * The settings a new installation starts with: regional pricing and receipt display choices with their localized
     * texts, no extra checkout references or card saving, and automatic receipts wherever there is a printer.
     */
    val settings: AppSettings
        get() {
            val defaults = AppSettings()
            return defaults.copy(
                payment = PaymentSettings(taxMode = starter.mode, chargeTax = code != "HK"),
                receipt =
                    defaults.receipt.copy(
                        title = texts(InstallationText.RECEIPT_TITLE),
                        footer = texts(InstallationText.RECEIPT_FOOTER),
                        taxIdLabel = texts(taxIdLabel),
                        showTaxAmounts = !japanese && code != "HK",
                        showTaxRateTotals = japanese || code in VAT_COUNTRIES,
                        markedTaxRateMilliPercent =
                            when {
                                japanese -> StarterTax.forCountry("JP").reducedMilliPercent
                                marksNoTax -> 0
                                else -> null
                            },
                        markedTaxRateNote = texts(if (marksNoTax) InstallationText.NO_TAX_NOTE else InstallationText.TAX_MARKER_NOTE),
                    ),
                email = defaults.email.copy(subject = texts(InstallationText.EMAIL_SUBJECT)),
            )
        }

    /** The name of a 0% rate made later, such as for an imported product without a tax rate. */
    val zeroRateName: String get() = texts(InstallationText.TAX_ZERO)

    /**
     * The tax rates a new installation starts with, in order: the country's standard rate and reduced rate where
     * [StarterTax] has them, then always a 0% rate, so the first is the one new products start with.
     */
    val starterTaxRates: List<TaxRateEntity>
        get() =
            listOfNotNull(
                starter.standardMilliPercent?.let { TaxRateEntity(name = texts(standardTax), rateMilliPercent = it) },
                starter.reducedMilliPercent?.let { TaxRateEntity(name = texts(InstallationText.TAX_REDUCED), rateMilliPercent = it) },
                TaxRateEntity(name = zeroRateName, rateMilliPercent = 0),
            )

    /** The name of the sample receipt's taxed rate: the country's standard rate name, else a generic sample one. */
    val sampleTaxName: String
        get() = texts(if (standardTax == InstallationText.TAX_STANDARD) InstallationText.SAMPLE_TAX else standardTax)

    /** Countries whose regional defaults follow VAT. */
    companion object {
        /** The countries whose receipts total each VAT rate and name the tax ID and standard rate VAT. */
        val VAT_COUNTRIES =
            setOf(
                "AT",
                "BE",
                "BG",
                "CY",
                "CZ",
                "DE",
                "DK",
                "EE",
                "ES",
                "FI",
                "FR",
                "GB",
                "GR",
                "HR",
                "HU",
                "IE",
                "IT",
                "LT",
                "LU",
                "LV",
                "MT",
                "NL",
                "PL",
                "PT",
                "RO",
                "SE",
                "SI",
                "SK",
            )
    }
}

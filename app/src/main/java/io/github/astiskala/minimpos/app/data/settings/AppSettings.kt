package io.github.astiskala.minimpos.app.data.settings

import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.core.money.AdyenCurrencies
import io.github.astiskala.minimpos.core.shopper.EmailReferenceMode
import io.github.astiskala.minimpos.core.tax.StarterTax
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.core.tax.TaxRates
import io.github.astiskala.minimpos.terminal.client.RecurringModel
import io.github.astiskala.minimpos.terminal.simulator.SimulatedOutcome
import io.github.astiskala.minimpos.terminal.transport.CloudRegion
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.Serializable

/**
 * All non-secret configuration, stored as JSON in DataStore (`settings.json`) and edited in Settings. Shared-key
 * passphrases, API keys, SMTP passwords and both PIN verifiers live in
 * [io.github.astiskala.minimpos.app.data.security.SecretStore].
 *
 * Constructor defaults define the current baseline; omitted settings take those defaults. Each section knows the
 * limits of its numbers ([normalized]); [SettingsRepository] applies them on every read and write, so a value out of
 * range never reaches the rest of the app, and the Settings fields offer the same ranges.
 *
 * @property terminal Where payments are sent and how the terminal is reached.
 * @property payment Currency, tax, references and what checkout asks for.
 * @property receipt Receipt content and printing.
 * @property email SMTP settings for emailed receipts.
 * @property security Admin area locking.
 * @property simulator How the built-in terminal simulator behaves.
 * @property history How long sales and refunds are kept.
 * @property pricingChange Local recoverable pricing journal; null when stable, never shared.
 * @property onboardingCompleted Whether this device has chosen its first-run setup path; never shared.
 */
@Serializable
data class AppSettings(
    val terminal: TerminalSettings = TerminalSettings(),
    val payment: PaymentSettings = PaymentSettings(),
    val receipt: ReceiptSettings = ReceiptSettings(),
    val email: EmailSettings = EmailSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val simulator: SimulatorSettings = SimulatorSettings(),
    val history: HistorySettings = HistorySettings(),
    /** Local confirmed pricing transition being committed; null when stable, never transferred to another device. */
    val pricingChange: PricingChange? = null,
    val onboardingCompleted: Boolean = false,
) {
    /** These settings with every number brought within its section's limits. */
    fun normalized(): AppSettings =
        copy(
            terminal = terminal.normalized(),
            payment = payment.normalized(),
            receipt = receipt.normalized(),
            email = email.normalized(),
            security = security.normalized(),
            simulator = simulator.normalized(),
            history = history.normalized(),
        )

    /**
     * These settings with what belongs to one device taken from [device]: the fields each section names as its own
     * device's ([TerminalSettings.withDeviceFieldsOf], [PaymentSettings.withDeviceFieldsOf]) and the whole simulator.
     * Everything else is shared by the terminals of a merchant account, so a new section, or a new field of a section,
     * travels by default; a section with a device-bound field names it next to its declaration.
     */
    fun withDeviceFieldsOf(device: AppSettings): AppSettings =
        copy(
            terminal = terminal.withDeviceFieldsOf(device.terminal),
            payment = payment.withDeviceFieldsOf(device.payment),
            simulator = device.simulator,
            pricingChange = device.pricingChange,
            onboardingCompleted = device.onboardingCompleted,
        )

    /**
     * These settings as another terminal of the same merchant account takes them: what belongs to this device
     * ([withDeviceFieldsOf]) is left at its defaults.
     */
    fun shared(): AppSettings = withDeviceFieldsOf(AppSettings())

    /**
     * [other]'s [shared] settings in place of these, keeping what belongs to this device ([withDeviceFieldsOf]), except
     * that the default tax rate is [defaultTaxRateId], the row of this device that matches the one [other] named.
     */
    fun takingOver(
        other: AppSettings,
        defaultTaxRateId: Long?,
    ): AppSettings = other.withDeviceFieldsOf(copy(payment = payment.copy(defaultTaxRateId = defaultTaxRateId)))

    /** The settings a new installation starts with. */
    companion object {
        internal val VAT_COUNTRIES =
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

        /**
         * The settings a new installation starts with in [country] (ISO 3166-1 alpha-2, possibly empty), using
         * [language] (ISO 639-1, empty for the baseline receipt style): regional pricing and receipt display choices,
         * no extra checkout references or card saving, and automatic receipts wherever there is a printer.
         * These are initial values only; saved and transferred settings are used as written.
         */
        fun forNewInstallation(
            country: String,
            language: String = "",
        ): AppSettings {
            val code = country.trim().uppercase()
            val japanese = language.equals("ja", ignoreCase = true)
            val receipt = ReceiptSettings()
            return AppSettings(
                payment = PaymentSettings(taxMode = StarterTax.forCountry(code).mode, chargeTax = code != "HK"),
                receipt =
                    receipt.copy(
                        showTaxAmounts = !japanese && code != "HK",
                        showTaxRateTotals = japanese || code in VAT_COUNTRIES,
                        markedTaxRateMilliPercent =
                            when {
                                japanese -> StarterTax.forCountry("JP").reducedMilliPercent
                                code == "AU" -> 0
                                else -> null
                            },
                        markedTaxRateNote = if (code == "AU" && !japanese) "※ No GST charged" else receipt.markedTaxRateNote,
                    ),
            )
        }
    }
}

/** Where payments go ("Payments go to" in Settings › Terminal). */
enum class TerminalMode {
    /** Terminal when running on an Adyen terminal, simulator elsewhere. Settings stores this when the default is picked. */
    AUTO,

    /** A real terminal through the local Terminal API: this device when it is a terminal, else one on the network. */
    TERMINAL,

    /**
     * A terminal reached over the internet through Adyen's Cloud device API, with an API key instead of the shared key.
     * Only offered off-terminal.
     */
    CLOUD,

    /** Tap to Pay on this phone through the Adyen Payments app. Only offered off-terminal (it does not run on terminals). */
    PAYMENTS_APP,

    /** The in-process [io.github.astiskala.minimpos.terminal.simulator.TerminalSimulator]; no card is charged. */
    SIMULATOR,
}

/**
 * How the terminal is reached and identified. Only the shared key fields apply on a terminal: there the app always
 * uses `localhost` and the device's own POIID.
 *
 * @property mode Where payments go; see [TerminalMode].
 * @property environment Selected for network/cloud terminals; read from this device's verified certificate on an
 *   Adyen terminal. Null until selected or read; it picks the Checkout API endpoint.
 *   With [TerminalMode.PAYMENTS_APP] the installed Payments app decides instead.
 * @property cloudRegion Not a choice: the Cloud device API's live data centre found for the API key in
 *   [TerminalMode.CLOUD]; null for TEST or until found.
 * @property host Only used off-terminal (a terminal on the network): its IP address or host name. Port 8443 is implied.
 * @property poiIdOverride Only used off-terminal, for a terminal on the network or in the cloud; on a terminal its own
 *   POIID (`Settings.Global.DEVICE_NAME`) is used.
 * @property paymentsAppInstallationId The Adyen Payments app instance boarded on this phone, which is the POIID in
 *   [TerminalMode.PAYMENTS_APP]; blank until boarded.
 * @property storeId The ID of the store the Payments app is boarded for (not its reference); blank boards it for the
 *   merchant account.
 * @property keyIdentifier Identifier of the shared key configured for the terminal in the Customer Area.
 * @property keyVersion Version of that shared key, within [KEY_VERSIONS].
 * @property merchantAccount The Adyen merchant account the terminal takes payments for, for captures and authorisation
 *   adjustments through the Checkout API (its API key is a secret). It is required before real payments can start.
 * @property liveUrlPrefix The company's live endpoint prefix for the Checkout API (Customer Area: Developers › API
 *   URLs); only used when [environment] is LIVE.
 */
@Serializable
data class TerminalSettings(
    val mode: TerminalMode = TerminalMode.AUTO,
    val environment: TerminalEnvironment? = null,
    val cloudRegion: CloudRegion? = null,
    val host: String = "",
    val poiIdOverride: String = "",
    val paymentsAppInstallationId: String = "",
    val storeId: String = "",
    val keyIdentifier: String = "",
    val keyVersion: Int = 1,
    val merchantAccount: String = "",
    val liveUrlPrefix: String = "",
) {
    /**
     * Selects [choice], storing the device's [automaticMode] as [TerminalMode.AUTO]. An unchanged effective destination
     * keeps its learned fields; changing it clears its environment and cloud region.
     */
    fun selectDestination(
        choice: TerminalMode,
        automaticMode: TerminalMode,
    ): TerminalSettings =
        if (choice == (if (mode == TerminalMode.AUTO) automaticMode else mode)) {
            this
        } else {
            withConnection(mode = if (choice == automaticMode) TerminalMode.AUTO else choice)
        }

    /** Selects [selected], clearing the learned cloud region even on reselection; null leaves the environment unknown. */
    fun selectEnvironment(selected: TerminalEnvironment?): TerminalSettings = copy(environment = selected, cloudRegion = null)

    /**
     * Imports a connection choice: null arguments retain the corresponding choice, a changed [mode] clears learned
     * fields, and the same supplied [environment] preserves the cloud region. Other device fields remain unchanged.
     */
    fun withConnection(
        mode: TerminalMode? = null,
        environment: TerminalEnvironment? = null,
    ): TerminalSettings {
        val moved = if (mode != null && mode != this.mode) copy(mode = mode, environment = null, cloudRegion = null) else this
        return if (environment != null && environment != moved.environment) moved.selectEnvironment(environment) else moved
    }

    /** These settings with [keyVersion] within its range. */
    fun normalized(): TerminalSettings = copy(keyVersion = keyVersion.coerceIn(KEY_VERSIONS))

    /**
     * These settings with the fields that belong to one device taken from [device]: where payments go ([mode]), the
     * selected or detected [environment] and [cloudRegion], the [host], the [poiIdOverride] and the [paymentsAppInstallationId].
     * Everything else is shared by the terminals of a merchant account, so it travels when one terminal sets up another.
     */
    fun withDeviceFieldsOf(device: TerminalSettings): TerminalSettings =
        copy(
            mode = device.mode,
            environment = device.environment,
            cloudRegion = device.cloudRegion,
            host = device.host,
            poiIdOverride = device.poiIdOverride,
            paymentsAppInstallationId = device.paymentsAppInstallationId,
        )

    /** The limits of the numbers. */
    companion object {
        /** The allowed range of [keyVersion]. */
        val KEY_VERSIONS = 1..9_999
    }
}

/**
 * When the shopper's email address is asked for, to send the receipt. Whatever the choice, receipts can be emailed from
 * history once SMTP is configured.
 */
enum class EmailCapture {
    /** Never: checkout has no email field and the result screen no "Email receipt" button. */
    OFF,

    /** In a field on the checkout screen, before the card is presented; the result screen also offers the button. */
    BEFORE_PAYMENT,

    /** Only with the "Email receipt" button on the result screen, after the payment. */
    AFTER_PAYMENT,
}

/**
 * What the Adyen shopper reference sent with every payment is made from, if anything; saved cards are filed under it
 * (see [PaymentSettings.offerCardSaving]).
 */
enum class ShopperReferenceSource {
    /** The customer reference typed at checkout. */
    CUSTOMER_REFERENCE,

    /** The shopper's email address, as set by [PaymentSettings.emailReferenceMode]. */
    EMAIL,

    /**
     * Nothing: payments carry no shopper reference and cards cannot be saved, so checkout asks for no customer
     * reference (and the email only as set).
     */
    NONE,
}

/** Whether sales offer tipping on a printed receipt, and the checkout switch's initial choice. */
@Serializable
enum class ReceiptTipping {
    /** Checkout never offers receipt tipping. */
    DISABLED,

    /** Checkout offers receipt tipping with its switch initially off. */
    DEFAULT_OFF,

    /** Checkout offers receipt tipping with its switch initially on. */
    DEFAULT_ON,
}

/**
 * How payments are taken: currency, tax, references and saving cards (tokenization).
 *
 * @property currencyCode Any currency in Adyen's currency table; blank follows the device's country.
 * @property chargeTax Off: no sale is taxed; products keep their tax rate for when it is switched back on.
 * @property taxMode Whether prices include tax; a new installation starts as is usual in the device's country
 *   ([AppSettings.forNewInstallation]).
 * @property defaultTaxRateId The tax rate new products and custom items start with; null (or a deleted rate) means the
 *   first rate.
 * @property referencePrefix Optional start of generated merchant references; they are unique without one.
 * @property askTransactionReference Whether checkout has a merchant reference field; left empty, a reference is
 *   generated as it is when the field is hidden. A new installation starts without it.
 * @property tokenizeDefaultOn Whether "Save card" starts switched on at checkout of a sale.
 * @property preAuthTokenizeDefaultOn Whether "Save card" starts switched on at checkout of a pre-authorisation, where a
 *   saved card allows charging late costs after the pre-authorisation has been captured.
 * @property receiptTipping Whether receipt tipping is offered for sales with a printer, and its initial choice.
 * @property recurringProcessingModel Adyen's `recurringProcessingModel` for saved cards, one of [RECURRING_MODELS].
 * @property emailCapture When checkout asks for an email; [effectiveEmailCapture] is what applies.
 * @property autoSendEmail When the email was captured before payment, send the receipt as soon as the payment is
 *   approved.
 * @property shopperReferenceSource What the shopper reference sent with every payment is made from, whether or not the
 *   card is saved; [ShopperReferenceSource.NONE] (what a new installation starts with) sends none and saves no cards.
 * @property emailReferenceMode How an email becomes a shopper reference, when [shopperReferenceSource] is
 *   [ShopperReferenceSource.EMAIL].
 * @property emailReferenceSalt Salt mixed into hashed email references. Terminals with the same salt give a shopper the
 *   same reference, so saved cards work on all of them; changing it gives every shopper a new reference.
 * @property offerCardSaving Whether checkout offers "Save card" while a shopper reference is available.
 * @property linkExpiryHours How long a payment link works, in hours within [LINK_EXPIRY_HOURS].
 */
@Serializable
data class PaymentSettings(
    val currencyCode: String = "",
    val chargeTax: Boolean = true,
    val taxMode: TaxMode = TaxMode.INCLUSIVE,
    val defaultTaxRateId: Long? = null,
    val referencePrefix: String = "",
    val askTransactionReference: Boolean = false,
    val tokenizeDefaultOn: Boolean = false,
    val preAuthTokenizeDefaultOn: Boolean = true,
    val receiptTipping: ReceiptTipping = ReceiptTipping.DISABLED,
    val recurringProcessingModel: String = "UnscheduledCardOnFile",
    val emailCapture: EmailCapture = EmailCapture.AFTER_PAYMENT,
    val autoSendEmail: Boolean = true,
    val shopperReferenceSource: ShopperReferenceSource = ShopperReferenceSource.NONE,
    val emailReferenceMode: EmailReferenceMode = EmailReferenceMode.HASHED,
    val emailReferenceSalt: String = "",
    val offerCardSaving: Boolean = true,
    val linkExpiryHours: Int = DEFAULT_LINK_EXPIRY_HOURS,
) {
    /** These settings with [linkExpiryHours] within its range. */
    fun normalized(): PaymentSettings = copy(linkExpiryHours = linkExpiryHours.coerceIn(LINK_EXPIRY_HOURS))

    /**
     * When the email is the shopper reference for saved cards it must be known when the payment starts, so it is then
     * always asked for before payment, regardless of the receipt email capture choice.
     */
    val effectiveEmailCapture: EmailCapture
        get() = if (shopperReferenceSource == ShopperReferenceSource.EMAIL) EmailCapture.BEFORE_PAYMENT else emailCapture

    /** Whether checkout asks for the email before payment, according to [effectiveEmailCapture]. */
    val captureEmailBefore: Boolean get() = effectiveEmailCapture == EmailCapture.BEFORE_PAYMENT

    /**
     * Checkout asks for a customer reference exactly when it is the shopper reference. With the email as shopper
     * reference there is no separate customer reference.
     */
    val asksCustomerReference: Boolean
        get() = shopperReferenceSource == ShopperReferenceSource.CUSTOMER_REFERENCE

    /** The rate new products and custom items start with among [rates]: [defaultTaxRateId]'s, else the first; null without rates. */
    fun defaultTaxRate(rates: List<TaxRateEntity>): TaxRateEntity? = rates.firstOrNull { it.id == defaultTaxRateId } ?: rates.firstOrNull()

    /**
     * These settings with the field that belongs to one device taken from [device]: [defaultTaxRateId], a row ID of
     * that device's database (another terminal finds its own row by name and rate, see `SetupTransfer`).
     */
    fun withDeviceFieldsOf(device: PaymentSettings): PaymentSettings = copy(defaultTaxRateId = device.defaultTaxRateId)

    /** The currency in effect: the chosen one, else the device [country]'s own currency if Adyen supports it, else EUR. */
    fun resolvedCurrency(country: String): String =
        AdyenCurrencies[currencyCode]?.code ?: AdyenCurrencies.forCountry(country)?.code ?: FALLBACK_CURRENCY

    /** [recurringProcessingModel] as a [RecurringModel]; an unrecognised stored value falls back to `UnscheduledCardOnFile`. */
    fun recurringModel(): RecurringModel =
        RecurringModel.entries.firstOrNull { it.value == recurringProcessingModel } ?: RecurringModel.UNSCHEDULED_CARD_ON_FILE

    /** Fixed values the payment settings fall back on or choose from. */
    companion object {
        /** The currency when none is chosen and the device's country has none that Adyen supports. */
        const val FALLBACK_CURRENCY = "EUR"

        /** The values Adyen accepts for `recurringProcessingModel`. */
        val RECURRING_MODELS: List<String> = RecurringModel.entries.map { it.value }

        /** How long a payment link works by default, in hours: Adyen's own default. */
        const val DEFAULT_LINK_EXPIRY_HOURS = 24

        /** The allowed range of [linkExpiryHours]: an hour to Adyen's longest, 70 days. */
        val LINK_EXPIRY_HOURS = 1..70 * 24
    }
}

/** Whether receipts can be printed. */
enum class PrinterMode {
    /** Detected: the simulator's setting, or asked from the terminal with a diagnosis request. */
    AUTO,

    /** Always offer printing, even when detection says there is no printer. */
    ON,

    /** Never print; receipts can still be emailed. */
    OFF,
}

/** When the merchant copy of a card receipt is printed after an approved payment. */
enum class MerchantCopyPolicy {
    /** Never. */
    NEVER,

    /** Only when the terminal asked for the shopper's signature, which the merchant copy is for. */
    SIGNATURE_ONLY,

    /** After every approved payment. */
    ALWAYS,
}

/**
 * What receipts show and when they are printed. Blank text fields are left off the receipt.
 *
 * @property businessName Name at the top of receipts and in email subjects; blank uses the app name in emails.
 * @property addressLines Business address, one receipt line per line of text.
 * @property taxIdLabel Label printed before [taxId], such as "ABN" or "VAT".
 * @property taxId The business's tax registration number.
 * @property phone Business phone number.
 * @property title Heading above the items.
 * @property footer Text at the bottom of receipts.
 * @property printerMode Whether printing is offered; see [PrinterMode].
 * @property autoPrint Print the customer receipt as soon as a payment is approved or a refund accepted, while printing
 *   is offered; a new installation starts with it on.
 * @property merchantCopy When the merchant copy is printed; see [MerchantCopyPolicy].
 * @property showTaxBreakdown Show the tax per rate.
 * @property showReferences Show the merchant and customer references.
 * @property showRefundQr Print the refund QR code on approved sales' customer copies.
 * @property charsPerLine Characters per printed line (32 fits a 58 mm roll), within [CHARS_PER_LINE]; plain-text
 *   emails use it too.
 * @property showTaxAmounts Print tax amounts without changing the payment's calculation.
 * @property showTaxRateTotals Print taxable totals grouped by numeric rate, independently of tax amounts.
 * @property markedTaxRateMilliPercent Rate whose items are marked, in thousandths of a percent; null disables marking.
 * @property markedTaxRateMarker Text appended to marked item names; blank normalizes to ※.
 * @property markedTaxRateNote Explanation below marked items; blank omits the explanation.
 */
@Serializable
data class ReceiptSettings(
    val businessName: String = "",
    val addressLines: String = "",
    val taxIdLabel: String = "Tax ID",
    val taxId: String = "",
    val phone: String = "",
    val title: String = "RECEIPT",
    val footer: String = "Thank you!",
    val printerMode: PrinterMode = PrinterMode.AUTO,
    val autoPrint: Boolean = true,
    val merchantCopy: MerchantCopyPolicy = MerchantCopyPolicy.SIGNATURE_ONLY,
    val showTaxBreakdown: Boolean = true,
    val showReferences: Boolean = true,
    val showRefundQr: Boolean = true,
    val charsPerLine: Int = 32,
    val showTaxAmounts: Boolean = true,
    val showTaxRateTotals: Boolean = false,
    val markedTaxRateMilliPercent: Int? = null,
    val markedTaxRateMarker: String = "※",
    val markedTaxRateNote: String = "※ Items at the marked tax rate",
) {
    /** These settings with line width and the marked rate within their limits, and a nonblank trimmed marker. */
    fun normalized(): ReceiptSettings =
        copy(
            charsPerLine = charsPerLine.coerceIn(CHARS_PER_LINE),
            markedTaxRateMilliPercent = markedTaxRateMilliPercent?.coerceIn(0, TaxRates.MAX),
            markedTaxRateMarker = markedTaxRateMarker.trim().ifBlank { "※" },
            markedTaxRateNote = markedTaxRateNote.trim(),
        )

    /** The limits of the numbers. */
    companion object {
        /** The allowed range of [charsPerLine]. */
        val CHARS_PER_LINE = 24..64
    }
}

/** How the SMTP connection is secured. */
enum class SmtpSecurity {
    /** Plain SMTP; the password (if any) is sent unencrypted. */
    NONE,

    /** Starts in plain text and upgrades with STARTTLS, which the server must support. */
    STARTTLS,

    /** TLS from the start (SMTPS, usually port 465). */
    SSL,
}

/**
 * SMTP settings for emailed receipts. The password is a secret and is stored in
 * [io.github.astiskala.minimpos.app.data.security.SecretStore].
 *
 * @property host SMTP server name.
 * @property port SMTP server port, within [PORTS]; 587 for STARTTLS, usually 465 for [SmtpSecurity.SSL].
 * @property security How the connection is secured.
 * @property username Login name; blank sends without authentication.
 * @property fromAddress Sender address.
 * @property fromName Sender display name; blank shows only the address.
 * @property bcc Optional address that gets a blind copy of every receipt.
 * @property subject Subject line; `{business}` and `{reference}` are replaced with the business name and the merchant
 *   reference.
 */
@Serializable
data class EmailSettings(
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val fromAddress: String = "",
    val fromName: String = "",
    val bcc: String = "",
    val subject: String = "Your receipt from {business}",
) {
    /** Whether enough is set to try sending: a host and a sender address. */
    val isConfigured: Boolean get() = host.isNotBlank() && fromAddress.isNotBlank()

    /** These settings with [port] within [PORTS]. */
    fun normalized(): EmailSettings = copy(port = port.coerceIn(PORTS))

    /** The limits of the numbers. */
    companion object {
        /** The allowed range of [port]. */
        val PORTS = 1..65_535
    }
}

/**
 * Admin area locking. The admin PIN itself is in [io.github.astiskala.minimpos.app.data.security.PinManager].
 *
 * @property autoLockMinutes Minutes without activity after which the admin area locks again; 0 locks it only when the
 *   admin area is left. Never negative.
 */
@Serializable
data class SecuritySettings(
    val autoLockMinutes: Int = 2,
) {
    /** These settings with [autoLockMinutes] not negative. */
    fun normalized(): SecuritySettings = copy(autoLockMinutes = autoLockMinutes.coerceAtLeast(0))
}

/**
 * How the built-in terminal simulator behaves, for trying the app without a terminal.
 *
 * @property outcome How every simulated payment ends.
 * @property delayMillis How long the simulated terminal takes to answer, in milliseconds, within [DELAY_MILLIS].
 * @property hasPrinter Whether the simulated terminal has a printer (prints are shown on screen).
 * @property signatureRequired Whether simulated approvals ask for a signature, which affects the merchant copy.
 */
@Serializable
data class SimulatorSettings(
    val outcome: SimulatedOutcome = SimulatedOutcome.APPROVE,
    val delayMillis: Long = 2_500,
    val hasPrinter: Boolean = true,
    val signatureRequired: Boolean = false,
) {
    /** These settings with [delayMillis] within [DELAY_MILLIS]. */
    fun normalized(): SimulatorSettings = copy(delayMillis = delayMillis.coerceIn(DELAY_MILLIS.first.toLong(), DELAY_MILLIS.last.toLong()))

    /** The limits of the numbers. */
    companion object {
        /** The allowed range of [delayMillis], in milliseconds. */
        val DELAY_MILLIS = 0..60_000
    }
}

/**
 * How long transactions are kept.
 *
 * @property retentionDays Sales and refunds older than this many days are deleted at startup; 0 keeps them forever.
 *   Never negative.
 */
@Serializable
data class HistorySettings(
    val retentionDays: Int = 90,
) {
    /** These settings with [retentionDays] not negative. */
    fun normalized(): HistorySettings = copy(retentionDays = retentionDays.coerceAtLeast(0))
}

package io.minimpos.app.data.settings

import io.minimpos.core.money.AdyenCurrencies
import io.minimpos.core.shopper.EmailReferenceMode
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.client.RecurringModel
import io.minimpos.terminal.simulator.SimulatedOutcome
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.Serializable

/**
 * All non-secret configuration, stored as JSON in DataStore (`settings.json`) and edited in Settings. Secrets (shared
 * key passphrase, SMTP password, PIN verifier) live in [io.minimpos.app.data.security.SecretStore].
 *
 * Every field has a default, so settings files from older versions keep loading: missing keys take the default and
 * unknown keys are ignored. Each section knows the limits of its numbers ([normalized]); [SettingsRepository] applies
 * them to whatever it reads or writes, so a value out of range (from an old file, a transfer or a bug) never reaches the
 * rest of the app, and the Settings fields offer the same ranges.
 *
 * @property terminal Where payments are sent and how the terminal is reached.
 * @property payment Currency, tax, references and what checkout asks for.
 * @property receipt Receipt content and printing.
 * @property email SMTP settings for emailed receipts.
 * @property security Admin area locking.
 * @property simulator How the built-in terminal simulator behaves.
 * @property history How long sales and refunds are kept.
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
) {
    /** These settings with every number brought within its section's limits. */
    fun normalized(): AppSettings =
        copy(
            terminal = terminal.normalized(),
            receipt = receipt.normalized(),
            email = email.normalized(),
            security = security.normalized(),
            simulator = simulator.normalized(),
            history = history.normalized(),
        )
}

/** Where payments go ("Payments go to" in Settings › Terminal). */
enum class TerminalMode {
    /** Terminal when running on an Adyen terminal, simulator elsewhere. Settings stores this when the default is picked. */
    AUTO,

    /** A real terminal through the local Terminal API: this device when it is a terminal, else one on the network. */
    TERMINAL,

    /** The in-process [io.minimpos.terminal.simulator.TerminalSimulator]; no card is charged. */
    SIMULATOR,
}

/**
 * How payments taken with manual capture (pre-authorisations, tips on the receipt) are captured and adjusted. Not stored:
 * it follows from whether the Checkout API is set up in Settings › Terminal (see `io.minimpos.app.terminal.AdyenApi`).
 */
enum class CaptureMode {
    /** By the app, through Adyen's Checkout API (simulated while payments go to the simulator). */
    API,

    /** By staff in the Customer Area: no Checkout API is set up, so the app only records what to capture. */
    CUSTOMER_AREA,
}

/**
 * How the terminal is reached and identified. Only the shared key fields apply on a terminal: there the app always
 * uses `localhost` and the device's own POIID.
 *
 * @property mode Where payments go; see [TerminalMode].
 * @property environment Not a choice: the environment of the terminal's certificate, remembered from the last
 *   connection (null until then). Only used for display, such as the TEST banner.
 * @property host Only used off-terminal (a terminal on the network): its IP address or host name. Port 8443 is implied.
 * @property poiIdOverride Only used off-terminal; on a terminal its own POIID (`Settings.Global.DEVICE_NAME`) is used.
 * @property saleId The nexo SaleID the app identifies itself with in every request; blank uses "MiniMPOS".
 * @property keyIdentifier Identifier of the shared key configured for the terminal in the Customer Area.
 * @property keyVersion Version of that shared key, within [KEY_VERSIONS].
 * @property timeoutSeconds How long to wait for a payment response before checking the transaction status, within
 *   [TIMEOUT_SECONDS]. Adyen advises 120 seconds for local integrations.
 * @property merchantAccount The Adyen merchant account the terminal takes payments for, for captures and authorisation
 *   adjustments through the Checkout API (its API key is a secret). Blank, together with no API key, leaves captures to
 *   the Customer Area.
 * @property liveUrlPrefix The company's live endpoint prefix for the Checkout API (Customer Area: Developers › API
 *   URLs); only used when [environment] is LIVE.
 */
@Serializable
data class TerminalSettings(
    val mode: TerminalMode = TerminalMode.AUTO,
    val environment: TerminalEnvironment? = null,
    val host: String = "",
    val poiIdOverride: String = "",
    val saleId: String = "MiniMPOS",
    val keyIdentifier: String = "",
    val keyVersion: Int = 1,
    val timeoutSeconds: Int = MIN_TIMEOUT_SECONDS,
    val merchantAccount: String = "",
    val liveUrlPrefix: String = "",
) {
    /** These settings with [keyVersion] and [timeoutSeconds] within their ranges. */
    fun normalized(): TerminalSettings =
        copy(keyVersion = keyVersion.coerceIn(KEY_VERSIONS), timeoutSeconds = timeoutSeconds.coerceIn(TIMEOUT_SECONDS))

    /**
     * These settings with the fields that belong to one device taken from [device]: where payments go ([mode]), the
     * certificate's [environment], the [host] and the [poiIdOverride]. Everything else is shared by the terminals of
     * a merchant account, so it travels when one terminal sets up another.
     */
    fun withDeviceFieldsOf(device: TerminalSettings): TerminalSettings =
        copy(mode = device.mode, environment = device.environment, host = device.host, poiIdOverride = device.poiIdOverride)

    /** The limits of the numbers. */
    companion object {
        /** The shortest payment timeout, and the default. */
        const val MIN_TIMEOUT_SECONDS = 120

        /** The longest payment timeout. */
        const val MAX_TIMEOUT_SECONDS = 600

        /** The allowed range of [timeoutSeconds]. */
        val TIMEOUT_SECONDS = MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS

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

    /** Both the checkout field and the result screen's button. */
    BOTH,
}

/** What the Adyen shopper reference of a saved card is made from. */
enum class ShopperReferenceSource {
    /** The customer reference typed at checkout. */
    CUSTOMER_REFERENCE,

    /** The shopper's email address, as set by [PaymentSettings.emailReferenceMode]. */
    EMAIL,
}

/**
 * How payments are taken: currency, tax, references and saving cards (tokenization).
 *
 * @property currencyCode Any currency in Adyen's currency table; blank follows the device's country.
 * @property chargeTax Off: no sale is taxed; products keep their tax rate for when it is switched back on.
 * @property taxMode Whether prices include tax.
 * @property defaultTaxRateId The tax rate new products and custom items start with; null (or a deleted rate) means the
 *   first rate.
 * @property referencePrefix Optional start of generated merchant references; they are unique without one.
 * @property askTransactionReference Whether checkout has a merchant reference field; left empty, a reference is
 *   generated as it is when the field is hidden.
 * @property tokenizeDefaultOn Whether "Save card" starts switched on at checkout of a sale.
 * @property preAuthTokenizeDefaultOn Whether "Save card" starts switched on at checkout of a pre-authorisation, where a
 *   saved card allows charging late costs after the pre-authorisation has been captured.
 * @property tipOnReceiptDefaultOn Whether "Tip on the receipt" starts switched on at checkout of a sale (it is only
 *   offered while a printer is available).
 * @property recurringProcessingModel Adyen's `recurringProcessingModel` for saved cards, one of [RECURRING_MODELS].
 * @property emailCapture When checkout asks for an email; [effectiveEmailCapture] is what applies.
 * @property autoSendEmail When the email was captured before payment, send the receipt as soon as the payment is
 *   approved.
 * @property shopperReferenceSource What the shopper reference of a saved card is made from.
 * @property emailReferenceMode How an email becomes a shopper reference, when [shopperReferenceSource] is
 *   [ShopperReferenceSource.EMAIL].
 * @property emailReferenceSalt Salt mixed into hashed email references. Terminals with the same salt give a shopper the
 *   same reference, so saved cards work on all of them; changing it gives every shopper a new reference.
 * @property sendShopperEmail Include `shopperEmail` in tokenization requests.
 */
@Serializable
data class PaymentSettings(
    val currencyCode: String = "",
    val chargeTax: Boolean = true,
    val taxMode: TaxMode = TaxMode.INCLUSIVE,
    val defaultTaxRateId: Long? = null,
    val referencePrefix: String = "",
    val askTransactionReference: Boolean = true,
    val tokenizeDefaultOn: Boolean = false,
    val preAuthTokenizeDefaultOn: Boolean = true,
    val tipOnReceiptDefaultOn: Boolean = false,
    val recurringProcessingModel: String = "UnscheduledCardOnFile",
    val emailCapture: EmailCapture = EmailCapture.AFTER_PAYMENT,
    val autoSendEmail: Boolean = true,
    val shopperReferenceSource: ShopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE,
    val emailReferenceMode: EmailReferenceMode = EmailReferenceMode.HASHED,
    val emailReferenceSalt: String = "",
    val sendShopperEmail: Boolean = true,
) {
    /**
     * When the email is the shopper reference for saved cards it must be known when the payment starts, so it is then
     * always asked for before payment (Never becomes Before, After becomes Before and after).
     */
    val effectiveEmailCapture: EmailCapture
        get() =
            when {
                shopperReferenceSource != ShopperReferenceSource.EMAIL -> emailCapture
                emailCapture == EmailCapture.AFTER_PAYMENT || emailCapture == EmailCapture.BOTH -> EmailCapture.BOTH
                else -> EmailCapture.BEFORE_PAYMENT
            }

    /** Whether checkout asks for the email before payment, according to [effectiveEmailCapture]. */
    val captureEmailBefore: Boolean get() =
        effectiveEmailCapture == EmailCapture.BEFORE_PAYMENT ||
            effectiveEmailCapture == EmailCapture.BOTH

    /**
     * Checkout asks for a customer reference exactly when it is the shopper reference, so cards can always be saved.
     * With the email as shopper reference there is no separate customer reference.
     */
    val asksCustomerReference: Boolean
        get() = shopperReferenceSource == ShopperReferenceSource.CUSTOMER_REFERENCE

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
 * @property autoPrint Print the customer receipt as soon as a payment is approved or a refund accepted.
 * @property merchantCopy When the merchant copy is printed; see [MerchantCopyPolicy].
 * @property showTaxBreakdown Show the tax per rate.
 * @property showReferences Show the merchant and customer references.
 * @property showRefundQr Print the refund QR code on approved sales' customer copies.
 * @property charsPerLine Characters per printed line (32 fits a 58 mm roll), within [CHARS_PER_LINE]; plain-text
 *   emails use it too.
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
    val autoPrint: Boolean = false,
    val merchantCopy: MerchantCopyPolicy = MerchantCopyPolicy.SIGNATURE_ONLY,
    val showTaxBreakdown: Boolean = true,
    val showReferences: Boolean = true,
    val showRefundQr: Boolean = true,
    val charsPerLine: Int = 32,
) {
    /** These settings with [charsPerLine] within [CHARS_PER_LINE]. */
    fun normalized(): ReceiptSettings = copy(charsPerLine = charsPerLine.coerceIn(CHARS_PER_LINE))

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
 * [io.minimpos.app.data.security.SecretStore].
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
 * Admin area locking. The admin PIN itself is in [io.minimpos.app.data.security.PinManager].
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

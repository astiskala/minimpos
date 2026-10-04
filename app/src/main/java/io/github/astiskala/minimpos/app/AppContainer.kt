package io.github.astiskala.minimpos.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.repo.HistoryRepository
import io.github.astiskala.minimpos.app.data.repo.RefundRepository
import io.github.astiskala.minimpos.app.data.repo.SaleRepository
import io.github.astiskala.minimpos.app.data.security.KeystoreSecretCipher
import io.github.astiskala.minimpos.app.data.security.PinManager
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretBlob
import io.github.astiskala.minimpos.app.data.security.SecretCipher
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.security.SessionLock
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.JsonDataStoreSerializer
import io.github.astiskala.minimpos.app.data.settings.PaymentSettings
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.app.email.EmailTexts
import io.github.astiskala.minimpos.app.email.MailTransport
import io.github.astiskala.minimpos.app.email.ReceiptEmailer
import io.github.astiskala.minimpos.app.email.SmtpMailer
import io.github.astiskala.minimpos.app.payment.Captures
import io.github.astiskala.minimpos.app.payment.PaymentLinks
import io.github.astiskala.minimpos.app.payment.PaymentStart
import io.github.astiskala.minimpos.app.payment.PricingChanges
import io.github.astiskala.minimpos.app.payment.ReceiptDelivery
import io.github.astiskala.minimpos.app.payment.RefundBook
import io.github.astiskala.minimpos.app.payment.SaleBook
import io.github.astiskala.minimpos.app.payment.SaleSession
import io.github.astiskala.minimpos.app.payment.TransactionLifecycle
import io.github.astiskala.minimpos.app.payment.TransactionState
import io.github.astiskala.minimpos.app.qr.QrCodes
import io.github.astiskala.minimpos.app.receipt.ReceiptFactory
import io.github.astiskala.minimpos.app.receipt.ReceiptSampleTexts
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.RefundStart
import io.github.astiskala.minimpos.app.refund.StoredPayments
import io.github.astiskala.minimpos.app.terminal.AdyenApi
import io.github.astiskala.minimpos.app.terminal.AndroidDeviceInfo
import io.github.astiskala.minimpos.app.terminal.DeviceInfo
import io.github.astiskala.minimpos.app.terminal.PaymentsAppBridge
import io.github.astiskala.minimpos.app.terminal.ReceiptBusinessDetails
import io.github.astiskala.minimpos.app.terminal.SetupDiscovery
import io.github.astiskala.minimpos.app.terminal.SimulatedTerminal
import io.github.astiskala.minimpos.app.terminal.TapToPaySetup
import io.github.astiskala.minimpos.app.terminal.TerminalGateway
import io.github.astiskala.minimpos.app.terminal.TerminalSetupSource
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
import io.github.astiskala.minimpos.app.terminal.VirtualPrinter
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.receipt.ReceiptLabels
import io.github.astiskala.minimpos.core.tax.StarterTax
import io.github.astiskala.minimpos.terminal.checkout.CheckoutCredentials
import io.github.astiskala.minimpos.terminal.checkout.CheckoutPaymentLinks
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkApi
import io.github.astiskala.minimpos.terminal.client.PosApplication
import io.github.astiskala.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.AppLinkExchange
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppTransport
import io.github.astiskala.minimpos.terminal.transport.AdyenCloudDevices
import io.github.astiskala.minimpos.terminal.transport.AdyenLocalTransport
import io.github.astiskala.minimpos.terminal.transport.AdyenStoreDetails
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.CloudCredentials
import io.github.astiskala.minimpos.terminal.transport.CloudDevices
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalKey
import io.github.astiskala.minimpos.terminal.transport.TerminalTls
import io.github.astiskala.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Manual dependency injection: one instance per process, created by [MiniMposApplication]. Screens reach it through
 * `LocalAppContainer`. The constructor parameters with defaults are test seams: tests pass an in-memory database, a
 * fake cipher, device and mail transport, temporary files and their own dispatchers.
 *
 * @param context The application context, for resources and storage.
 * @property database The Room database.
 * @param cipher Encrypts secrets at rest.
 * @property device The device the app runs on.
 * @param mailTransport Sends emails; null uses JavaMail's SMTP transport.
 * @param storageDir The DataStore file for a name.
 * @property appScope Where work that outlives any screen runs (payments, refunds, connection checks).
 * @param terminalTransport Connects to a real terminal at a host with a shared key.
 * @param ioDispatcher Runs file and Keystore work.
 * @param cloudDevices Reaches terminals in the cloud with an API key.
 * @param paymentsAppExchange Opens the Adyen Payments app; null uses [paymentsApp], which the activity serves.
 * @param paymentsAppManagement Boards and revokes the Payments app with an API key, in an environment.
 * @param paymentLinks Creates, checks and expires payment links with Checkout API credentials.
 * @param storeDetails Reads Management API stores for reviewed receipt-business import.
 * @param terminalDetails Reads optional Management terminal setup details.
 */
class AppContainer(
    private val context: Context,
    val database: AppDatabase = AppDatabase.create(context),
    cipher: SecretCipher = KeystoreSecretCipher(),
    val device: DeviceInfo = AndroidDeviceInfo(context),
    mailTransport: MailTransport? = null,
    private val storageDir: (String) -> File = { context.dataStoreFile(it) },
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    terminalTransport: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport = ::AdyenLocalTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    cloudDevices: (CloudCredentials) -> CloudDevices = { AdyenCloudDevices(it) },
    paymentsAppExchange: AppLinkExchange? = null,
    paymentsAppManagement: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppManagement = { key, environment ->
        AdyenPaymentsAppManagement(key, environment)
    },
    paymentLinks: (CheckoutCredentials) -> PaymentLinkApi = { CheckoutPaymentLinks(it) },
    storeDetails: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment ->
        AdyenStoreDetails(key, environment)
    },
    terminalDetails: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
) {
    private val country = device.country.trim().uppercase(Locale.ROOT)
    private val defaults =
        AppSettings
            .forNewInstallation(
                country,
                context.resources.configuration.locales[0]
                    .language,
            ).let {
                it.copy(
                    receipt =
                        it.receipt.copy(
                            title = context.getString(R.string.receipt_default_title),
                            footer = context.getString(R.string.receipt_default_footer),
                            taxIdLabel = context.getString(taxIdLabelResource()),
                            markedTaxRateNote =
                                context.getString(
                                    if (country == "AU" && it.receipt.markedTaxRateMilliPercent == 0) {
                                        R.string.receipt_default_no_gst_note
                                    } else {
                                        R.string.receipt_default_tax_marker_note
                                    },
                                ),
                        ),
                    email = it.email.copy(subject = context.getString(R.string.email_default_subject)),
                )
            }

    /** Non-secret settings (`settings.json`). */
    val settings = SettingsRepository(store("settings.json", serializer<AppSettings>(), defaults))

    /** [settings] as a state that is always available; it holds the defaults until the file has been read. */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, defaults)

    /** Encrypted credentials (`secrets.json`). */
    val secrets = SecretStore(store("secrets.json", serializer<SecretBlob>(), SecretBlob()), cipher, ioDispatcher)

    /** The admin PIN. */
    val pinManager = PinManager(secrets)

    /** Optional Manager PIN, separate from configuration access. */
    val managerPin = PinManager(secrets, verifierSecret = Secret.MANAGER_PIN_VERIFIER)

    /** Whether money-moving actions are currently unlocked. */
    val managerLock = SessionLock()

    /** Whether the admin area is unlocked. */
    val sessionLock = SessionLock()

    /** Tax rates, categories and products. */
    val catalog = CatalogRepository(database, context.getString(R.string.tax_default_zero))

    /** Copies the catalogue, settings and secrets to another device by QR code, and imports the setup helper's codes. */
    val setupTransfer = SetupTransfer(catalog, settings, secrets, onTerminal = device.isAdyenTerminal)

    /** Stored sales. */
    val sales = SaleRepository(database)

    /** Stored refunds. */
    val refundRecords = RefundRepository(database, sales)

    /** The combined transaction history and its housekeeping. */
    val history = HistoryRepository(database)

    /** What the simulator prints, shown on screen instead of paper. */
    val virtualPrinter = VirtualPrinter()

    /** How payments and refunds identify themselves to Adyen (SaleToAcquirerData.applicationInfo). */
    val application =
        PosApplication(
            name = APPLICATION_NAME,
            version = BuildConfig.VERSION_NAME,
            integrator = APPLICATION_NAME,
            osName = "Android",
            osVersion = device.osVersion,
        )

    private val terminalSetup = TerminalSetupSource(settings, secrets, device)

    /** Optional read-only discovery of terminal connection fields. */
    val setupDiscovery = SetupDiscovery(terminalSetup, settings, { secrets.set(Secret.TERMINAL_PASSPHRASE, it) }, terminalDetails)

    /** Read-only Management store lookup for manually importing receipt business details. */
    val receiptBusinessDetails = ReceiptBusinessDetails(terminalSetup, storeDetails)

    /** The built-in simulator, which stands in for the terminal and the Checkout API alike. */
    private val simulator = SimulatedTerminal(virtualPrinter)

    /** The App Links to the Adyen Payments app, which the activity opens and answers. */
    val paymentsApp = PaymentsAppBridge()

    private val paymentsAppLinks: AppLinkExchange = paymentsAppExchange ?: paymentsApp

    /** The payment terminal (or the simulator): the Terminal API operations, sent where payments go. */
    val gateway =
        TerminalGateway(
            setups = terminalSetup,
            simulator = simulator,
            application = application,
            paymentsApp = { key, environment -> PaymentsAppTransport(key, environment, paymentsAppLinks, PaymentsAppBridge.RETURN_URL) },
            connect = terminalTransport,
            cloud = cloudDevices,
        )

    /** Boards (and revokes) the Adyen Payments app on this phone, for Tap to Pay. */
    val tapToPay = TapToPaySetup(terminalSetup, settings, paymentsAppLinks, paymentsAppManagement)

    /**
     * Adyen's Checkout API, for captures and authorisation adjustments (simulated with the simulator) and payment links
     * (never simulated).
     */
    val api = AdyenApi(terminalSetup, simulated = simulator.modifications, connectLinks = paymentLinks)

    /** Whether payments and printing can work, for Home, Settings and the receipt screens. */
    val terminalStatus = TerminalStatus(terminalSetup, gateway, settings, appScope)

    /** Stored sales with what can be done with them now, for the screens that show one. */
    val storedPayments = StoredPayments(sales)

    /**
     * Builds receipt documents from stored sales and refunds, with localised labels. Screens get their receipts from
     * [receipts] (through `TransactionActions`); tests build documents with it directly.
     */
    internal val receiptFactory =
        ReceiptFactory(
            receiptLabels(),
            currentLabels = ::receiptLabels,
            standingText = { standing ->
                when (standing) {
                    PaymentStanding.CHARGED -> null

                    PaymentStanding.NOT_APPROVED -> context.getString(R.string.receipt_not_completed)

                    PaymentStanding.AWAITING_TIP -> context.getString(R.string.detail_tip_awaiting)

                    PaymentStanding.HELD -> context.getString(R.string.receipt_pre_auth_note)

                    PaymentStanding.CAPTURE_REQUESTED -> context.getString(R.string.status_capture_requested)

                    PaymentStanding.CAPTURE_FAILED -> context.getString(R.string.status_capture_failed)

                    PaymentStanding.CAPTURE_SENDING,
                    PaymentStanding.CAPTURE_UNKNOWN,
                    -> context.getString(R.string.status_capture_unknown)

                    PaymentStanding.HOLD_CANCELLED -> context.getString(R.string.status_cancellation_requested)
                }
            },
            refundText = { status ->
                context.getString(
                    when (status) {
                        RefundStatus.REQUESTED -> R.string.refund_status_requested
                        RefundStatus.PENDING -> R.string.status_pending
                        RefundStatus.UNKNOWN -> R.string.status_unknown
                        RefundStatus.FAILED -> R.string.status_failed
                    },
                )
            },
            sampleTexts = {
                ReceiptSampleTexts(
                    coffee = context.getString(R.string.receipt_sample_coffee),
                    custom = context.getString(R.string.receipt_sample_item),
                    taxed =
                        context.getString(
                            standardTaxNameResource().takeUnless { it == R.string.tax_default_standard } ?: R.string.receipt_sample_tax,
                        ),
                    zero = context.getString(R.string.receipt_sample_zero_tax),
                )
            },
        )

    /** A time stamp (epoch milliseconds) as a short date and time in the device's locale and zone, as receipts print it. */
    fun formatDateTime(epochMillis: Long): String = receiptFactory.formatDateTime(epochMillis)

    /** The sample receipt Settings shows and test-prints with [appSettings], which may not be stored yet. */
    fun sampleReceipt(appSettings: AppSettings): ReceiptDocument =
        receiptFactory.sample(
            appSettings.receipt,
            currency(appSettings),
            appSettings.payment.taxMode,
            appSettings.payment.asksCustomerReference,
        )

    /** Printing and emailing of stored sales and refunds, including what is delivered automatically. */
    val receipts =
        ReceiptDelivery(
            settings = settings,
            sales = sales,
            refunds = refundRecords,
            receipts = receiptFactory,
            gateway = gateway,
            status = terminalStatus,
            emailer =
                ReceiptEmailer(
                    settings = settings,
                    secrets = secrets,
                    mailer = mailTransport?.let { SmtpMailer(it, ioDispatcher) } ?: SmtpMailer(io = ioDispatcher),
                    texts = emailTexts(),
                    currentTexts = ::emailTexts,
                    qrPng = { QrCodes.png(it) },
                ),
            notFound = context.getString(R.string.error_not_found),
        )

    private val sessions = SaleKind.entries.associateWith { SaleSession(it) }

    /**
     * The payment of [kind] being rung up, shared by its ring-up and checkout screens; one per kind, so a sale being rung
     * up is kept while a pre-authorisation is taken.
     */
    fun session(kind: SaleKind): SaleSession = sessions.getValue(kind)

    /** Runs card payments and keeps their progress. */
    val payments: TransactionLifecycle<PaymentStart> =
        TransactionLifecycle(
            scope = appScope,
            gateway = gateway,
            book = SaleBook(sales),
            onSucceeded = { id, start ->
                // The cart has been paid for, so the next payment of its kind starts afresh.
                completeSale(id, start)
            },
        )

    /** Enters tips and captures and adjusts payments taken with manual capture. */
    val captures = Captures(sales, api::target, ::managerPermits)

    /** Creates payment links for sales, asks Adyen whether they were paid, and cancels them. */
    val links =
        PaymentLinks(
            scope = appScope,
            sales = sales,
            settings = settings,
            target = api::target,
            onCreated = { id, start ->
                // The cart is now the link's to pay, so the next sale starts afresh.
                completeSale(id, start.payment)
            },
            permits = ::managerPermits,
        )

    /** Runs referenced refunds and keeps their progress. */
    val refunds: TransactionLifecycle<RefundStart> =
        TransactionLifecycle(
            scope = appScope,
            gateway = gateway,
            book = RefundBook(refundRecords),
            onSucceeded = { id, _ -> receipts.arm(id) },
            permits = { managerPermits() },
        )

    private fun completeSale(
        id: String,
        start: PaymentStart,
    ) {
        receipts.arm(id)
        session(start.kind).complete(start)
    }

    /** Confirmed pricing changes and their recovery, including both payment kinds' sessions. */
    val pricingChanges =
        PricingChanges(settings, catalog, ::currency, sessions.values) {
            payments.state.value is TransactionState.Processing || refunds.state.value is TransactionState.Processing
        }

    /**
     * Starts the background work, once per process: marks transactions and captures interrupted by the last shutdown as UNKNOWN,
     * seeds the [starterTaxRates] on first launch, prunes old history, and starts the [terminalStatus] checks.
     */
    fun start() {
        appScope.launch {
            pricingChanges.recover()
            history.settleInterrupted()
            catalog.seedDefaults(starterTaxRates())
            history.prune(settings.current().history.retentionDays, System.currentTimeMillis())
        }
        terminalStatus.start()
    }

    /**
     * The tax rates a new installation starts with, named in the current language: the [StarterTax] of the device's
     * country (its standard rate, and a reduced one where it has one), then always a 0% rate.
     */
    internal fun starterTaxRates(): List<TaxRateEntity> {
        val starter = StarterTax.forCountry(device.country)
        return listOfNotNull(
            starter.standardMilliPercent?.let {
                TaxRateEntity(
                    name = context.getString(standardTaxNameResource()),
                    rateMilliPercent = it,
                )
            },
            starter.reducedMilliPercent?.let {
                TaxRateEntity(
                    name = context.getString(R.string.tax_default_reduced),
                    rateMilliPercent = it,
                )
            },
            TaxRateEntity(name = context.getString(R.string.tax_default_zero), rateMilliPercent = 0),
        )
    }

    /** Records actual touch, key or text-entry activity for both PIN sessions; call on the main thread. */
    fun userActivity() {
        val timeout = settingsState.value.security.autoLockMinutes.minutes.inWholeMilliseconds
        sessionLock.touch(timeout)
        managerLock.touch(timeout)
    }

    /** The currency payments are taken in with [appSettings] (by default the current ones). */
    fun currency(appSettings: AppSettings = settingsState.value): CurrencySpec =
        resolveCurrency(appSettings.payment, Locale.forLanguageTag("und-${device.country}"))

    private suspend fun managerPermits(): Boolean =
        !managerPin.pinConfigured.first() ||
            managerLock.allows(
                settings
                    .current()
                    .security.autoLockMinutes.minutes.inWholeMilliseconds,
            )

    private fun <T> store(
        name: String,
        serializer: KSerializer<T>,
        default: T,
    ): DataStore<T> =
        DataStoreFactory.create(
            serializer = JsonDataStoreSerializer(serializer, default),
            corruptionHandler = ReplaceFileCorruptionHandler { default },
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { storageDir(name) },
        )

    @StringRes
    private fun taxIdLabelResource(): Int =
        when (country) {
            "AU" -> R.string.receipt_tax_id_abn
            "NZ" -> R.string.receipt_tax_id_gst
            "SG" -> R.string.receipt_tax_id_gst_registration
            "MY" -> R.string.receipt_tax_id_sst_registration
            "CA" -> R.string.receipt_tax_id_gst_hst
            "MX" -> R.string.receipt_tax_id_rfc
            "BR" -> R.string.receipt_tax_id_cpf_cnpj
            else -> if (country in AppSettings.VAT_COUNTRIES) R.string.receipt_tax_id_vat else R.string.receipt_default_tax_id
        }

    @StringRes
    private fun standardTaxNameResource(): Int =
        when (country) {
            "AU", "NZ", "SG" -> R.string.tax_name_gst
            "MY" -> R.string.tax_name_sst
            "MX" -> R.string.tax_name_iva
            else -> if (country in AppSettings.VAT_COUNTRIES) R.string.tax_name_vat else R.string.tax_default_standard
        }

    private fun receiptLabels() =
        ReceiptLabels(
            date = context.getString(R.string.receipt_date),
            reference = context.getString(R.string.receipt_reference),
            customer = context.getString(R.string.receipt_customer),
            subtotal = context.getString(R.string.receipt_subtotal),
            tax = context.getString(R.string.receipt_tax),
            total = context.getString(R.string.receipt_total),
            includesTaxFormat = context.getString(R.string.receipt_includes_tax),
            quantityFormat = context.getString(R.string.receipt_quantity),
            refundQrCaption = context.getString(R.string.receipt_refund_qr_caption),
            merchantCopy = context.getString(R.string.receipt_merchant_copy),
            refundTitle = context.getString(R.string.receipt_refund_title),
            originalReference = context.getString(R.string.receipt_original_sale),
            refundTotal = context.getString(R.string.receipt_refund_total),
            partialRefund = context.getString(R.string.receipt_partial_refund),
            cardSaved = context.getString(R.string.receipt_card_saved),
            notCompleted = context.getString(R.string.receipt_not_completed),
            preAuthTitle = context.getString(R.string.receipt_pre_auth_title),
            amountHeld = context.getString(R.string.receipt_amount_held),
            preAuthNote = context.getString(R.string.receipt_pre_auth_note),
            cancellationTitle = context.getString(R.string.receipt_cancellation_title),
            cancelledReference = context.getString(R.string.receipt_cancelled_reference),
            cancellationNote = context.getString(R.string.receipt_cancellation_note),
            released = context.getString(R.string.receipt_released),
            amount = context.getString(R.string.receipt_amount),
            tip = context.getString(R.string.receipt_tip),
            tipTotal = context.getString(R.string.receipt_tip_total),
            signature = context.getString(R.string.receipt_signature),
            heldNow = context.getString(R.string.receipt_held_now),
            captured = context.getString(R.string.receipt_captured),
            taxableGrossFormat = context.getString(R.string.receipt_taxable_gross_format),
            taxableNetFormat = context.getString(R.string.receipt_taxable_net_format),
            amountDue = context.getString(R.string.receipt_amount_due),
            unpaid = context.getString(R.string.receipt_unpaid),
            payLinkCaption = context.getString(R.string.receipt_pay_link_caption),
            payLinkIntro = context.getString(R.string.receipt_pay_link_intro),
            payNow = context.getString(R.string.receipt_pay_now),
            linkValidFormat = context.getString(R.string.receipt_link_valid),
            paidOnline = context.getString(R.string.receipt_paid_online),
        )

    private fun emailTexts() =
        EmailTexts(
            appName = context.getString(R.string.app_name),
            intro = context.getString(R.string.email_intro),
            refundIntro = context.getString(R.string.email_refund_intro),
            testSubject = context.getString(R.string.email_test_subject),
            testBody = context.getString(R.string.email_test_body),
            notConfigured = context.getString(R.string.email_not_configured),
            invalidAddress = context.getString(R.string.email_invalid_address),
            preAuthIntro = context.getString(R.string.email_pre_auth_intro),
            cancellationIntro = context.getString(R.string.email_cancellation_intro),
            linkSubject = context.getString(R.string.email_link_subject),
            linkIntro = context.getString(R.string.email_link_intro),
        )

    /** The application identity and currency resolution, also used without a container. */
    companion object {
        /** Fixed values: Adyen asks for the same application info formatting in every request. */
        const val APPLICATION_NAME = "Mini mPOS"

        /**
         * The currency of [payment]: the configured one, else the currency of [locale]'s country if Adyen supports it,
         * else EUR.
         */
        fun resolveCurrency(
            payment: PaymentSettings,
            locale: Locale = Locale.getDefault(),
        ): CurrencySpec = CurrencySpec.of(payment.resolvedCurrency(locale.country))
    }
}

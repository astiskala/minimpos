package io.minimpos.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.repo.CatalogRepository
import io.minimpos.app.data.repo.HistoryRepository
import io.minimpos.app.data.repo.RefundRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.security.KeystoreSecretCipher
import io.minimpos.app.data.security.PinManager
import io.minimpos.app.data.security.SecretBlob
import io.minimpos.app.data.security.SecretCipher
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.security.SessionLock
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.JsonDataStoreSerializer
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.transfer.SetupTransfer
import io.minimpos.app.email.EmailTexts
import io.minimpos.app.email.MailTransport
import io.minimpos.app.email.ReceiptEmailer
import io.minimpos.app.email.SmtpMailer
import io.minimpos.app.payment.Captures
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.RefundBook
import io.minimpos.app.payment.SaleBook
import io.minimpos.app.payment.SaleSession
import io.minimpos.app.payment.TransactionLifecycle
import io.minimpos.app.qr.QrCodes
import io.minimpos.app.receipt.ReceiptFactory
import io.minimpos.app.refund.RefundStart
import io.minimpos.app.refund.StoredPayments
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.AndroidDeviceInfo
import io.minimpos.app.terminal.DeviceInfo
import io.minimpos.app.terminal.TerminalGateway
import io.minimpos.app.terminal.TerminalSetupSource
import io.minimpos.app.terminal.TerminalStatus
import io.minimpos.app.terminal.VirtualPrinter
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptLabels
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.transport.AdyenLocalTransport
import io.minimpos.terminal.transport.TerminalKey
import io.minimpos.terminal.transport.TerminalTls
import io.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.io.File
import java.util.Locale

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
) {
    /** Non-secret settings (`settings.json`). */
    val settings = SettingsRepository(store("settings.json", serializer<AppSettings>(), AppSettings()))

    /** [settings] as a state that is always available; it holds the defaults until the file has been read. */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    /** Encrypted credentials (`secrets.json`). */
    val secrets = SecretStore(store("secrets.json", serializer<SecretBlob>(), SecretBlob()), cipher, ioDispatcher)

    /** The admin PIN. */
    val pinManager = PinManager(secrets)

    /** Whether the admin area is unlocked. */
    val sessionLock = SessionLock()

    /** Tax rates, categories and products. */
    val catalog = CatalogRepository(database, context.getString(R.string.tax_default_zero))

    /** Copies the catalogue, settings and secrets to another terminal by QR code. */
    val setupTransfer = SetupTransfer(catalog, settings, secrets)

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

    /** The payment terminal (or the simulator): the Terminal API operations, sent where payments go. */
    val gateway = TerminalGateway(terminalSetup, secrets, virtualPrinter, application, terminalTransport)

    /** Adyen's Checkout API, for captures and authorisation adjustments (simulated with the simulator). */
    val api = AdyenApi(terminalSetup, secrets, simulated = gateway.simulatedModifications)

    /** Whether payments and printing can work, for Home, Settings and the receipt screens. */
    val terminalStatus = TerminalStatus(terminalSetup, gateway, settings, appScope)

    /** Stored sales with what can be done with them now, for the screens that show one. */
    val storedPayments = StoredPayments(sales, terminalStatus.state.filter { it.loaded }.map { it.captureMode })

    /**
     * Builds receipt documents from stored sales and refunds, with localised labels. Screens get their receipts from
     * [receipts] (through `TransactionActions`); tests build documents with it directly.
     */
    internal val receiptFactory = ReceiptFactory(receiptLabels())

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
                    texts =
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
                        ),
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
            unknownOutcome = context.getString(R.string.payment_unknown_outcome),
            onSucceeded = { id, start ->
                receipts.arm(id)
                // The cart has been paid for, so the next payment of its kind starts afresh.
                session(start.kind).clear()
            },
        )

    /** Enters tips and captures and adjusts payments taken with manual capture. */
    val captures = Captures(sales, api::target)

    /** Runs referenced refunds and keeps their progress. */
    val refunds: TransactionLifecycle<RefundStart> =
        TransactionLifecycle(
            scope = appScope,
            gateway = gateway,
            book = RefundBook(refundRecords),
            unknownOutcome = context.getString(R.string.refund_unknown_outcome),
            onSucceeded = { id, _ -> receipts.arm(id) },
        )

    /**
     * Starts the background work, once per process: marks transactions and captures interrupted by the last shutdown as UNKNOWN,
     * seeds the default tax rates, prunes old history, and starts the [terminalStatus] checks.
     */
    fun start() {
        appScope.launch {
            history.settleInterrupted(context.getString(R.string.payment_interrupted), context.getString(R.string.capture_interrupted))
            catalog.seedDefaults(context.getString(R.string.tax_default_standard), context.getString(R.string.tax_default_zero))
            history.prune(settings.current().history.retentionDays, System.currentTimeMillis())
        }
        terminalStatus.start()
    }

    /** The currency payments are taken in with [appSettings] (by default the current ones). */
    fun currency(appSettings: AppSettings = settingsState.value): CurrencySpec = resolveCurrency(appSettings.payment)

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

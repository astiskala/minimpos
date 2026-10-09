package app.minimpos.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.repo.SampleData
import app.minimpos.app.data.security.KeystoreSecretCipher
import app.minimpos.app.data.security.PinManager
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretBlob
import app.minimpos.app.data.security.SecretCipher
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.JsonDataStoreSerializer
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.transfer.SetupTransfer
import app.minimpos.app.email.EmailTexts
import app.minimpos.app.email.MailTransport
import app.minimpos.app.email.ReceiptEmailer
import app.minimpos.app.email.SmtpMailer
import app.minimpos.app.payment.Captures
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.PricingChanges
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.ReceiptRecords
import app.minimpos.app.payment.RefundBook
import app.minimpos.app.payment.SaleBook
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.payment.WalletPayments
import app.minimpos.app.qr.QrCodes
import app.minimpos.app.receipt.ReceiptFactory
import app.minimpos.app.receipt.ReceiptSampleTexts
import app.minimpos.app.receipt.ReportText
import app.minimpos.app.refund.PaymentStanding
import app.minimpos.app.refund.RefundStart
import app.minimpos.app.refund.StoredPayments
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.AndroidDeviceInfo
import app.minimpos.app.terminal.DeviceInfo
import app.minimpos.app.terminal.HistorySwitches
import app.minimpos.app.terminal.PaymentsAppBridge
import app.minimpos.app.terminal.ReceiptBusinessDetails
import app.minimpos.app.terminal.SetupAccess
import app.minimpos.app.terminal.SetupDiscovery
import app.minimpos.app.terminal.SetupImport
import app.minimpos.app.terminal.SetupImportChecks
import app.minimpos.app.terminal.SharedKeySetup
import app.minimpos.app.terminal.SimulatedTerminal
import app.minimpos.app.terminal.TapToPaySetup
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.app.terminal.TerminalStatus
import app.minimpos.app.terminal.VirtualPrinter
import app.minimpos.app.terminal.WalletDiscovery
import app.minimpos.app.update.AppUpdate
import app.minimpos.app.update.GitHubReleases
import app.minimpos.app.update.UpdateCheck
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptLabels
import app.minimpos.core.tax.StarterTax
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.PaymentLinkApi
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.paymentsapp.PaymentsAppTransport
import app.minimpos.terminal.transport.AdyenCloudDevices
import app.minimpos.terminal.transport.AdyenLocalTransport
import app.minimpos.terminal.transport.AdyenStoreDetails
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.AdyenWalletMethods
import app.minimpos.terminal.transport.CloudCredentials
import app.minimpos.terminal.transport.CloudDevices
import app.minimpos.terminal.transport.StoreDetailsApi
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTls
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.WalletMethodsApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * @param paymentModifications Verifies API access and modifies payments with Checkout API credentials.
 * @param storeDetails Reads Management API store or merchant details for reviewed receipt-business import.
 * @param terminalDetails Reads optional Management terminal setup details.
 * @param terminalEnvironment Reads the local terminal certificate without credentials.
 * @param walletMethods Reads optional, read-only POS-wallet configuration without affecting ordinary API setup.
 * @param updateCheck Reads the latest GitHub release for the installed version, on devices that are not Adyen
 *   terminals; the default reaches GitHub, tests answer directly.
 * @param onboardingCompleted Initial setup completion for isolated tests; production installations start incomplete.
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
    paymentLinks: ((CheckoutCredentials) -> PaymentLinkApi)? = null,
    paymentModifications: ((CheckoutCredentials) -> PaymentModifications)? = null,
    storeDetails: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment ->
        AdyenStoreDetails(key, environment)
    },
    terminalDetails: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
    terminalEnvironment: suspend () -> TerminalEnvironment? = { TerminalTls().readEnvironment("localhost") },
    walletMethods: (String, TerminalEnvironment) -> WalletMethodsApi = { key, environment -> AdyenWalletMethods(key, environment) },
    updateCheck: suspend () -> UpdateCheck = { GitHubReleases().latest(BuildConfig.VERSION_CODE.toLong()) },
    onboardingCompleted: Boolean = false,
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
                            title = context.resources.getString(R.string.receipt_default_title),
                            footer = context.resources.getString(R.string.receipt_default_footer),
                            taxIdLabel = context.resources.getString(taxIdLabelResource()),
                            markedTaxRateNote =
                                context.resources.getString(
                                    if (country == "AU" && it.receipt.markedTaxRateMilliPercent == 0) {
                                        R.string.receipt_default_no_gst_note
                                    } else {
                                        R.string.receipt_default_tax_marker_note
                                    },
                                ),
                        ),
                    email = it.email.copy(subject = context.resources.getString(R.string.email_default_subject)),
                    onboardingCompleted = onboardingCompleted,
                )
            }

    /** Non-secret settings (`settings.json`). */
    val settings = SettingsRepository(store("settings.json", serializer<AppSettings>(), defaults))

    /** [settings] as a state that is always available; it holds the defaults until the file has been read. */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, defaults)

    private val localizedContext: Context get() = context.withAppLanguage(settingsState.value.languageTag)

    /** First-run choice after storage has loaded; null prevents briefly showing either Home or onboarding too early. */
    val onboardingState: StateFlow<Boolean?> =
        settings.onboardingCompleted.stateIn(appScope, SharingStarted.Eagerly, true.takeIf { onboardingCompleted })

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
    val catalog = CatalogRepository(database, localizedContext.getString(R.string.tax_default_zero))

    /** Stored sales. */
    val sales = SaleRepository(database)

    /** Stored refunds. */
    val refundRecords = RefundRepository(database, sales)

    /** The combined transaction history and its housekeeping. */
    val history = HistoryRepository(database)

    /** Copies the catalogue, settings and secrets to another device by QR code, and imports the setup helper's codes. */
    val setupTransfer = SetupTransfer(catalog, settings, secrets, onTerminal = device.isAdyenTerminal, history = history)

    /** Explicit environment-switch history deletion and recovery; unrelated merchant configuration remains. */
    val historySwitches =
        HistorySwitches(
            settings,
            history,
            if (device.isAdyenTerminal) {
                TerminalMode.TERMINAL
            } else {
                TerminalMode.SIMULATOR
            },
        )

    /** Optional offline catalog and read-only history examples, with scoped removal. */
    val sampleData =
        SampleData(
            database,
            catalog,
            sales,
            history,
            texts = {
                listOf(
                    R.string.sample_coffee,
                    R.string.sample_tea,
                    R.string.sample_cake,
                    R.string.sample_deposit,
                    R.string.sample_category,
                ).map(localizedContext::getString)
            },
            starterRates = ::starterTaxRates,
            country = device.country,
        )

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

    private val terminalSetup = TerminalSetupSource(settings, secrets, device, history.switchPending)

    /** Read-only Management store lookup for manually importing receipt business details. */
    val receiptBusinessDetails = ReceiptBusinessDetails(terminalSetup, storeDetails, terminalDetails)

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
            localEnvironment = terminalEnvironment,
        )

    /**
     * Adyen's Checkout API, for captures, authorisation adjustments and payment links, all simulated in simulator mode.
     */
    val api =
        AdyenApi(
            terminalSetup,
            simulated = simulator.modifications,
            connect = paymentModifications ?: { CheckoutModifications(it, application = application.toApplicationInfo()) },
            connectLinks = paymentLinks ?: { CheckoutPaymentLinks(it, application = application.toApplicationInfo()) },
            verifyAccess = SetupAccess(terminalDetails)::verify,
            readEnvironment = gateway::refreshEnvironment,
        )

    /** Optional memory-only POS-wallet availability; errors are displayed only in Settings. */
    val walletDiscovery = WalletDiscovery(terminalSetup, appScope, terminalDetails, walletMethods)

    private val sharedKeys = SharedKeySetup(terminalSetup, terminalDetails, api::verify, ::financialOperationRunning)

    /** Optional read-only discovery of terminal connection fields, within the destination's environment. */
    val setupDiscovery =
        SetupDiscovery(terminalSetup, settings, {
            secrets.set(Secret.TERMINAL_PASSPHRASE, it)
        }, terminalDetails, gateway::readEnvironment, sharedKeys)

    /** Boards (and revokes) the Adyen Payments app on this phone, for Tap to Pay. */
    val tapToPay = TapToPaySetup(terminalSetup, settings, paymentsAppLinks, paymentsAppManagement) { api.verify() }

    /** Whether payments and printing can work, for Home, Settings and the receipt screens. */
    val terminalStatus = TerminalStatus(terminalSetup, gateway, appScope, api::verify, tapToPay::checkSaved)

    /**
     * The startup check for a newer GitHub release, which Home offers to download in the browser; never started on an
     * Adyen terminal, which updates through the Customer Area.
     */
    val update = AppUpdate(updateCheck, appScope)

    /** Stored sales with what can be done with them now, for the screens that show one. */
    val storedPayments = StoredPayments(sales)

    /**
     * Builds receipt documents from stored sales and refunds, with localised labels. Screens get their receipts from
     * [receipts] (through `TransactionActions`); tests build documents with it directly.
     */
    internal val receiptFactory =
        ReceiptFactory(
            receiptLabels(),
            locale = { localizedContext.resources.configuration.locales[0] ?: Locale.ROOT },
            currentLabels = { receiptLabels() },
            simulationText = { localizedContext.getString(R.string.link_simulation_note) },
            demoLinkText = { localizedContext.getString(R.string.link_demo_label) },
            reportText = { label ->
                localizedContext.getString(
                    when (label) {
                        ReportText.TITLE -> R.string.report_title
                        ReportText.GENERATED -> R.string.report_generated
                        ReportText.SALES -> R.string.report_sales
                        ReportText.TIPS -> R.string.report_tips
                        ReportText.REFUNDS -> R.string.report_refunds
                        ReportText.NET -> R.string.report_net
                        ReportText.HOLDS -> R.string.report_holds
                        ReportText.UNRESOLVED -> R.string.report_unresolved
                        ReportText.ONLINE -> R.string.report_online
                        ReportText.UNDATED -> R.string.report_undated
                        ReportText.UNDATED_NOTE -> R.string.report_undated_note
                        ReportText.NOTE -> R.string.report_note
                        ReportText.ISSUE_NOTE -> R.string.report_issue_note
                        ReportText.DEMO -> R.string.report_demo
                        ReportText.UNKNOWN -> R.string.report_unknown
                    },
                )
            },
            standingText = { standing ->
                when (standing) {
                    PaymentStanding.CHARGED -> null

                    PaymentStanding.NOT_APPROVED -> localizedContext.getString(R.string.receipt_not_completed)

                    PaymentStanding.AWAITING_TIP -> localizedContext.getString(R.string.detail_tip_awaiting)

                    PaymentStanding.HELD -> localizedContext.getString(R.string.receipt_pre_auth_note)

                    PaymentStanding.CAPTURE_REQUESTED -> localizedContext.getString(R.string.status_capture_requested)

                    PaymentStanding.CAPTURE_FAILED -> localizedContext.getString(R.string.status_capture_failed)

                    PaymentStanding.CAPTURE_SENDING,
                    PaymentStanding.CAPTURE_UNKNOWN,
                    -> localizedContext.getString(R.string.status_capture_unknown)

                    PaymentStanding.HOLD_CANCELLED -> localizedContext.getString(R.string.status_cancellation_requested)
                }
            },
            refundText = { status ->
                localizedContext.getString(
                    when (status) {
                        RefundStatus.REQUESTED -> R.string.refund_status_requested
                        RefundStatus.PENDING -> R.string.status_pending
                        RefundStatus.UNKNOWN -> R.string.status_unknown
                        RefundStatus.FAILED -> R.string.status_failed
                    },
                )
            },
            sampleTexts = { sampleTexts() },
        )

    /** A time stamp (epoch milliseconds) in the current app language and device time zone, as receipts print it. */
    fun formatDateTime(epochMillis: Long): String = receiptFactory.formatDateTime(epochMillis)

    /** The sample receipt Settings shows and test-prints with [appSettings], which may not be stored yet. */
    fun sampleReceipt(appSettings: AppSettings): ReceiptDocument {
        val localized = context.withAppLanguage(appSettings.languageTag)
        return ReceiptFactory(
            receiptLabels(localized),
            locale = { localized.resources.configuration.locales[0] ?: Locale.ROOT },
            sampleTexts = { sampleTexts(localized) },
        ).sample(
            appSettings.receipt,
            currency(appSettings),
            appSettings.payment.taxMode,
            appSettings.payment.asksCustomerReference,
        )
    }

    /** Printing and emailing of stored sales and refunds, including what is delivered automatically. */
    val receipts =
        ReceiptDelivery(
            settings = settings,
            records = ReceiptRecords(sales, refundRecords, history),
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

    /** Transient wallet scanning delegates to the same sale lifecycle, with original-context send protection. */
    val walletPayments = WalletPayments(session(SaleKind.SALE), payments, walletDiscovery, gateway, appScope, ::walletCheckout)

    private suspend fun walletCheckout(): Checkout {
        val checkout =
            session(SaleKind.SALE)
                .checkout(
                    settingsState,
                    flowOf(terminalStatus.state.value.printerAvailable),
                    currency = ::currency,
                ).first()
        return checkout.copy(wallets = walletDiscovery.state.value.offered(checkout.currency.code))
    }

    /** Enters tips and captures and adjusts payments taken with manual capture. */
    val captures = Captures(sales, api::target, ::managerPermits)

    /** Creates payment links for sales, asks Adyen whether they were paid, and cancels them. */
    val links =
        PaymentLinks(
            scope = appScope,
            sales = sales,
            target = api::target,
            locale = { localizedContext.resources.configuration.locales[0] ?: Locale.ROOT },
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

    /** Refund/cancellation initiation and capture retries, keeping reference facts out of screens. */
    val storedPaymentActions = StoredPaymentActions(refunds, captures, settingsState)

    private fun completeSale(
        id: String,
        start: PaymentStart,
    ) {
        receipts.arm(id)
        session(start.kind).complete(start)
    }

    private fun financialOperationRunning(): Boolean =
        payments.state.value is TransactionState.Processing || refunds.state.value is TransactionState.Processing

    internal val setupImport =
        SetupImport(
            setupTransfer,
            terminalSetup,
            gateway,
            api,
            tapToPay,
            SetupImportChecks(receiptBusinessDetails, terminalDetails, sharedKeys),
            historySwitches,
        ) {
            payments.state.value is TransactionState.Processing || refunds.state.value is TransactionState.Processing
        }

    /** Confirmed pricing changes and their recovery, including both payment kinds' sessions. */
    val pricingChanges =
        PricingChanges(settings, catalog, ::currency, sessions.values) {
            payments.state.value is TransactionState.Processing || refunds.state.value is TransactionState.Processing
        }

    /**
     * Starts the background work, once per process: marks transactions and captures interrupted by the last shutdown as UNKNOWN,
     * seeds the [starterTaxRates] on first launch, prunes old history, and starts the [terminalStatus] checks. On a
     * device that is not an Adyen terminal it also starts the [update] check, once.
     */
    fun start() {
        appScope.launch {
            pricingChanges.recover()
            setupImport.recover()
            historySwitches.recover()
            history.settleInterrupted()
            catalog.seedDefaults(starterTaxRates())
            history.prune(settings.current().history.retentionDays, System.currentTimeMillis())
        }
        terminalStatus.start()
        walletDiscovery.start()
        // Adyen terminals update through the Customer Area; only other devices check GitHub Releases for a newer one.
        if (!device.isAdyenTerminal) update.start()
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
                    name = localizedContext.getString(standardTaxNameResource()),
                    rateMilliPercent = it,
                )
            },
            starter.reducedMilliPercent?.let {
                TaxRateEntity(
                    name = localizedContext.getString(R.string.tax_default_reduced),
                    rateMilliPercent = it,
                )
            },
            TaxRateEntity(name = localizedContext.getString(R.string.tax_default_zero), rateMilliPercent = 0),
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

    private fun sampleTexts(localizedContext: Context = this.localizedContext) =
        ReceiptSampleTexts(
            coffee = localizedContext.getString(R.string.receipt_sample_coffee),
            custom = localizedContext.getString(R.string.receipt_sample_item),
            taxed =
                localizedContext.getString(
                    standardTaxNameResource().takeUnless { it == R.string.tax_default_standard } ?: R.string.receipt_sample_tax,
                ),
            zero = localizedContext.getString(R.string.receipt_sample_zero_tax),
        )

    private fun receiptLabels(localizedContext: Context = this.localizedContext) =
        ReceiptLabels(
            date = localizedContext.getString(R.string.receipt_date),
            reference = localizedContext.getString(R.string.receipt_reference),
            customer = localizedContext.getString(R.string.receipt_customer),
            subtotal = localizedContext.getString(R.string.receipt_subtotal),
            tax = localizedContext.getString(R.string.receipt_tax),
            total = localizedContext.getString(R.string.receipt_total),
            includesTaxFormat = localizedContext.getString(R.string.receipt_includes_tax),
            quantityFormat = localizedContext.getString(R.string.receipt_quantity),
            refundQrCaption = localizedContext.getString(R.string.receipt_refund_qr_caption),
            merchantCopy = localizedContext.getString(R.string.receipt_merchant_copy),
            refundTitle = localizedContext.getString(R.string.receipt_refund_title),
            originalReference = localizedContext.getString(R.string.receipt_original_sale),
            refundTotal = localizedContext.getString(R.string.receipt_refund_total),
            partialRefund = localizedContext.getString(R.string.receipt_partial_refund),
            cardSaved = localizedContext.getString(R.string.receipt_card_saved),
            notCompleted = localizedContext.getString(R.string.receipt_not_completed),
            preAuthTitle = localizedContext.getString(R.string.receipt_pre_auth_title),
            amountHeld = localizedContext.getString(R.string.receipt_amount_held),
            preAuthNote = localizedContext.getString(R.string.receipt_pre_auth_note),
            cancellationTitle = localizedContext.getString(R.string.receipt_cancellation_title),
            cancelledReference = localizedContext.getString(R.string.receipt_cancelled_reference),
            cancellationNote = localizedContext.getString(R.string.receipt_cancellation_note),
            released = localizedContext.getString(R.string.receipt_released),
            amount = localizedContext.getString(R.string.receipt_amount),
            tip = localizedContext.getString(R.string.receipt_tip),
            tipTotal = localizedContext.getString(R.string.receipt_tip_total),
            signature = localizedContext.getString(R.string.receipt_signature),
            heldNow = localizedContext.getString(R.string.receipt_held_now),
            captured = localizedContext.getString(R.string.receipt_captured),
            taxableGrossFormat = localizedContext.getString(R.string.receipt_taxable_gross_format),
            taxableNetFormat = localizedContext.getString(R.string.receipt_taxable_net_format),
            amountDue = localizedContext.getString(R.string.receipt_amount_due),
            unpaid = localizedContext.getString(R.string.receipt_unpaid),
            payLinkCaption = localizedContext.getString(R.string.receipt_pay_link_caption),
            payLinkIntro = localizedContext.getString(R.string.receipt_pay_link_intro),
            payNow = localizedContext.getString(R.string.receipt_pay_now),
            linkValidFormat = localizedContext.getString(R.string.receipt_link_valid),
            paidOnline = localizedContext.getString(R.string.receipt_paid_online),
        )

    private fun emailTexts() =
        EmailTexts(
            demoSubject = localizedContext.getString(R.string.link_demo_label),
            simulationNote = localizedContext.getString(R.string.link_simulation_note),
            appName = localizedContext.getString(R.string.app_name),
            intro = localizedContext.getString(R.string.email_intro),
            refundIntro = localizedContext.getString(R.string.email_refund_intro),
            testSubject = localizedContext.getString(R.string.email_test_subject),
            testBody = localizedContext.getString(R.string.email_test_body),
            preAuthIntro = localizedContext.getString(R.string.email_pre_auth_intro),
            cancellationIntro = localizedContext.getString(R.string.email_cancellation_intro),
            linkSubject = localizedContext.getString(R.string.email_link_subject),
            linkIntro = localizedContext.getString(R.string.email_link_intro),
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

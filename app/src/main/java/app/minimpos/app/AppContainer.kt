package app.minimpos.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.repo.SampleData
import app.minimpos.app.data.repo.SampleDataFixture
import app.minimpos.app.data.security.KeystoreSecretCipher
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.PinManager
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretBlob
import app.minimpos.app.data.security.SecretCipher
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.InstallationText
import app.minimpos.app.data.settings.JsonDataStoreSerializer
import app.minimpos.app.data.settings.NewInstallation
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.StorageHealth
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.transfer.SetupTransfer
import app.minimpos.app.email.EmailTexts
import app.minimpos.app.email.MailTransport
import app.minimpos.app.email.ReceiptEmailer
import app.minimpos.app.email.SmtpMailer
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.RefundStart
import app.minimpos.app.history.StoredPayments
import app.minimpos.app.payment.Captures
import app.minimpos.app.payment.CheckoutRefunds
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.PricingChanges
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.ReceiptRecords
import app.minimpos.app.payment.RefundBook
import app.minimpos.app.payment.SaleBook
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.app.payment.ShopperPromptTexts
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.TerminalReconciliation
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.WalletPayments
import app.minimpos.app.qr.QrCodes
import app.minimpos.app.receipt.ReceiptFactory
import app.minimpos.app.receipt.ReceiptSampleTexts
import app.minimpos.app.receipt.ReceiptTexts
import app.minimpos.app.receipt.ReportText
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.AndroidDeviceInfo
import app.minimpos.app.terminal.CapabilityDiscovery
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
import app.minimpos.app.terminal.TapToPayDiagnostics
import app.minimpos.app.terminal.TapToPaySetup
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.app.terminal.TerminalStatus
import app.minimpos.app.terminal.UnlockedSetup
import app.minimpos.app.terminal.VirtualPrinter
import app.minimpos.app.terminal.WalletDiscovery
import app.minimpos.app.terminal.refreshEnvironment
import app.minimpos.app.ui.components.SetupGate
import app.minimpos.app.update.AppUpdate
import app.minimpos.app.update.GitHubReleases
import app.minimpos.app.update.UpdateCheck
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptLabels
import app.minimpos.core.tax.StarterTax
import app.minimpos.terminal.checkout.AdyenCheckout
import app.minimpos.terminal.checkout.CheckoutApi
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppCredentials
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.PaymentsAppCredentials
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.paymentsapp.PaymentsAppTransport
import app.minimpos.terminal.simulator.SimulatedPaymentLinks
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

internal val Context.locale: Locale get() = resources.configuration.locales[0] ?: Locale.ROOT

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
 * @param http The one OkHttp client every default Adyen API client and terminal connection derives from, so they share
 *   its connection pool and dispatcher. Built in [appScope] after construction rather than on the main thread, as
 *   building it sets up TLS.
 * @param terminalTransport Connects to a real terminal at a host with a shared key.
 * @param ioDispatcher Runs file and Keystore work.
 * @param cloudDevices Reaches terminals in the cloud with an API key.
 * @param paymentsAppExchange Opens the Adyen Payments app; null uses [paymentsApp], which the activity serves.
 * @param paymentsAppManagement Boards and revokes the Payments app with an API key, in an environment.
 * @param paymentsAppCredentials Provisions the device-local boarding credential with the primary API key.
 * @param checkout Makes the Checkout API (modifications, payment links and saved cards) for one credential; null uses
 *   Adyen's.
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
    http: Lazy<OkHttpClient> = lazy { OkHttpClient() },
    terminalTransport: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport = { host, key, tls ->
        AdyenLocalTransport(host, key, tls, http.value)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    cloudDevices: (CloudCredentials) -> CloudDevices = { AdyenCloudDevices(it, baseClient = http.value) },
    paymentsAppExchange: AppLinkExchange? = null,
    paymentsAppManagement: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppManagement = { key, environment ->
        AdyenPaymentsAppManagement(key, environment, baseClient = http.value)
    },
    paymentsAppCredentials: (apiKey: String, environment: TerminalEnvironment) -> PaymentsAppCredentials = { key, environment ->
        AdyenPaymentsAppCredentials(key, environment, baseClient = http.value)
    },
    checkout: ((CheckoutCredentials) -> CheckoutApi)? = null,
    storeDetails: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment ->
        AdyenStoreDetails(key, environment, baseClient = http.value)
    },
    terminalDetails: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it, baseClient = http.value) },
    terminalEnvironment: suspend () -> TerminalEnvironment? = { TerminalTls().readEnvironment("localhost") },
    walletMethods: (String, TerminalEnvironment) -> WalletMethodsApi = { key, environment ->
        AdyenWalletMethods(key, environment, baseClient = http.value)
    },
    updateCheck: suspend () -> UpdateCheck = { GitHubReleases().latest(BuildConfig.VERSION_CODE.toLong()) },
    onboardingCompleted: Boolean = false,
) {
    private val country = device.country.trim().uppercase(Locale.ROOT)
    private val defaults = installation(context).settings.copy(onboardingCompleted = onboardingCompleted)

    /** Configuration-file readiness; unreadable files are retained and protected access remains blocked. */
    val storage = StorageHealth()

    private val settingsStore = store("settings.json", serializer<AppSettings>(), defaults)
    private val secretsStore = store("secrets.json", serializer<SecretBlob>(), SecretBlob())

    init {
        appScope.launch { storage.check(settingsStore, secretsStore) }
        // Builds the client and its TLS setup in the background, so a screen's first request never builds it on the main
        // thread.
        appScope.launch { http.value }
    }

    /** Non-secret settings (`settings.json`). */
    val settings = SettingsRepository(settingsStore)

    /** [settings] as a state that is always available; it holds the defaults until the file has been read. */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, defaults)

    private val localizedContext: Context get() = context.withAppLanguage(settingsState.value.languageTag)

    /** First-run choice after storage has loaded; null prevents briefly showing either Home or onboarding too early. */
    val onboardingState: StateFlow<Boolean?> =
        settings.onboardingCompleted.stateIn(appScope, SharingStarted.Eagerly, true.takeIf { onboardingCompleted })

    /** Encrypted credentials (`secrets.json`). */
    val secrets = SecretStore(secretsStore, cipher, ioDispatcher)

    /** The admin PIN. */
    val pinManager = PinManager(secrets)

    /** Optional Manager PIN, separate from configuration access. */
    val managerPin = PinManager(secrets, verifierSecret = Secret.MANAGER_PIN_VERIFIER)

    /** Whether money-moving actions are currently unlocked. */
    val managerLock = SessionLock()

    /**
     * Manager approval of money-moving operations: taken when one starts, so leaving the screen that approved it cannot
     * refuse it, and checked again before sending.
     */
    val managerApproval =
        ManagerApproval(
            configured = { managerPin.pinConfigured.first() },
            session = managerLock,
            autoLockMillis = { settingsState.value.security.autoLockMinutes.minutes.inWholeMilliseconds },
        )

    /** Whether the admin area is unlocked. */
    val sessionLock = SessionLock()

    /** Tax rates, categories and products. */
    val catalog = CatalogRepository(database) { installation(localizedContext).zeroRateName }

    /** Stored sales. */
    val sales = SaleRepository(database)

    /** Stored refunds. */
    val refundRecords = RefundRepository(database, sales)

    /** The combined transaction history and its housekeeping. */
    val history = HistoryRepository(database, onRemoved = ::historyRemoved, onPurged = ::historyPurged)

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

    /**
     * The one setup source; a setup it remembers as verified completes the shared-key recovery records waiting for it.
     * Tests reach the wired source itself rather than building a sibling over the same settings and secrets, so they
     * exercise its history-switch gating and verified completions exactly as the app runs them.
     */
    internal val terminalSetup: TerminalSetupSource =
        TerminalSetupSource(settings, secrets, device, history.switchPending, ::completeKeyRecovery)

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
            paymentsApp = { key, environment ->
                PaymentsAppTransport(
                    key,
                    environment,
                    paymentsAppLinks,
                    PaymentsAppBridge.RETURN_URL,
                ) { device.paymentsAppVersion(environment) }
            },
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
            simulated =
                CheckoutApi.of(
                    simulator.modifications,
                    SimulatedPaymentLinks(),
                    simulator.savedCards { shopper ->
                        history.reader
                            .shopper(shopper, linkedMapOf<String, StoredCard>()) { cards, batch ->
                                SavedCardCharges.simulatedCards(batch, shopper).forEach { cards.putIfAbsent(it.id, it) }
                                cards
                            }.values
                            .toList()
                    },
                ),
            connect = checkout ?: { AdyenCheckout(it, application.toApplicationInfo(), http.value) },
            verifyAccess = SetupAccess(terminalDetails)::verify,
            readEnvironment = gateway::refreshEnvironment,
        )

    /** Optional memory-only POS-wallet availability; errors are displayed only in Settings. */
    val walletDiscovery = WalletDiscovery(terminalSetup, appScope, terminalDetails, walletMethods)

    /** Progressive terminal capability inspection and scoped Management updates for Settings › Capabilities. */
    val capabilityDiscovery = CapabilityDiscovery(terminalSetup, terminalDetails, walletMethods)

    private val sharedKeys: SharedKeySetup = SharedKeySetup(terminalSetup, terminalDetails, api::verify, ::financialOperationRunning)

    /** Boards (and revokes) the Adyen Payments app on this phone, for Tap to Pay. */
    val tapToPay =
        TapToPaySetup(
            terminalSetup,
            settings,
            paymentsAppLinks,
            paymentsAppManagement,
            TapToPayDiagnostics(terminalSetup, settings, paymentsAppLinks, paymentsAppManagement, appScope),
            { api.verify() },
            paymentsAppCredentials,
        )

    /** Whether payments and printing can work, for Home, Settings and the receipt screens. */
    val terminalStatus =
        TerminalStatus(terminalSetup, gateway, appScope, api::inspect, tapToPay::checkSaved) { tapToPay.diagnostics.refresh() }

    /** Optional terminal discovery and explicitly confirmed shared-key setup through connection verification. */
    val setupDiscovery =
        SetupDiscovery(terminalSetup, {
            secrets.set(Secret.TERMINAL_PASSPHRASE, it)
        }, terminalDetails, gateway::readEnvironment, sharedKeys, terminalStatus::check, storeDetails)

    /**
     * The startup check for a newer GitHub release, which Home offers to download in the browser; never started on an
     * Adyen terminal, which updates through the Customer Area.
     */
    val update = AppUpdate(updateCheck, appScope)

    /** Stored sales with what can be done with them now, for the screens that show one. */
    val storedPayments = StoredPayments(sales)

    /**
     * Holds back every screen action reaching Adyen or a terminal while payments still need setting up; the navigation
     * host keeps it following the terminal status and shows its explanation.
     */
    val setupGate = SetupGate()

    /**
     * Builds receipt documents from stored sales and refunds, with localised labels. Screens get their receipts from
     * [receipts] (through `TransactionActions`); tests build documents with it directly.
     */
    internal val receiptFactory = ReceiptFactory(::receiptTexts)

    /** A time stamp (epoch milliseconds) in the current app language and device time zone, as receipts print it. */
    fun formatDateTime(epochMillis: Long): String = receiptFactory.formatDateTime(epochMillis)

    /** The sample receipt Settings shows and test-prints with [appSettings], which may not be stored yet. */
    fun sampleReceipt(appSettings: AppSettings): ReceiptDocument =
        receiptFactory.sample(
            appSettings.receipt,
            currency(appSettings),
            appSettings.payment.taxMode,
            appSettings.payment.asksCustomerReference,
            appSettings.languageTag,
        )

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
                    qrPng = { QrCodes.png(it) },
                    currentTexts = ::emailTexts,
                ),
            scope = appScope,
        )

    private val sessions = SaleKind.entries.associateWith { SaleSession(it, ::shopperPromptTexts) }

    /** The sale rung up for a saved-card charge, kept apart from New sale's cart; no terminal prompts apply to it. */
    val savedCardSession = SaleSession(SaleKind.SALE)

    private fun shopperPromptTexts(): ShopperPromptTexts {
        val localized = localizedContext
        return ShopperPromptTexts(
            order = localized.getString(R.string.shopper_order),
            total = localized.getString(R.string.checkout_total),
            confirmOrder = localized.getString(R.string.shopper_confirm_order),
            confirm = localized.getString(R.string.shopper_confirm),
            cancel = localized.getString(R.string.action_cancel),
            email = localized.getString(R.string.shopper_email_prompt),
            rating = localized.getString(R.string.shopper_rating_prompt),
        )
    }

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
            approval = managerApproval,
        )

    /** Transient wallet scanning delegates to the same sale lifecycle, with original-context send protection. */
    val walletPayments =
        WalletPayments(
            session(SaleKind.SALE),
            payments,
            walletDiscovery,
            gateway,
            appScope,
            currentCheckout = {
                session(SaleKind.SALE)
                    .checkout(
                        settings = settingsState,
                        terminal = terminalStatus.state,
                        wallets = walletDiscovery.state,
                        currency = ::currency,
                    ).first()
            },
        )

    /** Enters tips and captures and adjusts payments taken with manual capture. */
    val captures = Captures(sales, api::target, managerApproval)

    /** Creates payment links for sales, asks Adyen whether they were paid, and cancels them. */
    val links =
        PaymentLinks(
            scope = appScope,
            sales = sales,
            target = api::target,
            locale = { localizedContext.locale },
            onCreated = { id, start ->
                // The cart is now the link's to pay, so the next sale starts afresh.
                completeSale(id, start.payment)
            },
            approval = managerApproval,
        )

    /** Runs referenced refunds and keeps their progress. */
    val refunds: TransactionLifecycle<RefundStart> =
        TransactionLifecycle(
            scope = appScope,
            gateway = gateway,
            book = RefundBook(refundRecords),
            onSucceeded = { id, _ -> receipts.arm(id) },
            approval = managerApproval,
        )

    /** Charges saved cards through the Checkout API and asks again about unknown charges. */
    val savedCardCharges =
        SavedCardCharges(
            scope = appScope,
            sales = sales,
            target = api::target,
            onCharged = { id, start ->
                completeSale(id, start.payment, if (start.fromCheckout) session(SaleKind.SALE) else savedCardSession)
            },
            approval = managerApproval,
            targets = api.targets,
        )

    /** Explicit Tokens lookup, retained across its reference, cart and review screens; never queries on its own. */
    val savedCardLookup =
        savedCardCharges.lookup(
            appScope,
            savedCardSession.checkout(settingsState, currency = ::currency),
            terminalStatus.state,
        )

    /** Refunds saved-card charges through the Checkout API, which has no terminal transaction to reverse. */
    private val checkoutRefunds =
        CheckoutRefunds(appScope, refundRecords, sales, api::target, onAccepted = { receipts.arm(it) }, approval = managerApproval)

    /**
     * Every financial operation, whichever way it is sent: status checks, refund routing and whether anything is being
     * sent now, which setup changes wait for.
     */
    val operations = FinancialOperations(payments, refunds, savedCardCharges, checkoutRefunds, links, sales, refundRecords)

    private fun historyPurged() {
        operations.forgetAll()
        receipts.forgetAll()
        simulator.forgetAll()
    }

    private fun historyRemoved(
        items: List<HistoryItem>,
        tenders: List<SaleTenderEntity>,
    ) {
        val ids = items.map { it.id }.toSet()
        operations.forget(ids)
        receipts.forget(ids)
        val chargeKeys =
            items
                .filterIsInstance<HistoryItem.Sale>()
                .filter { it.sale.savedCard }
                .map { SavedCardCharges.idempotencyKey(it.id) }
                .toSet()
        simulator.forget(items, tenders, chargeKeys)
    }

    /**
     * One terminal reconciliation: rechecks unresolved terminal operations, reads or closes the period and compares its
     * totals against local accounting, so the screens only follow its outcome.
     */
    val reconciliation =
        TerminalReconciliation(history, operations, gateway, terminalStatus.state, approval = managerApproval)

    /** Refund/cancellation initiation and capture retries, keeping reference facts out of screens. */
    val storedPaymentActions = StoredPaymentActions(operations, captures, settingsState)

    /** Whether a financial operation is being sent now, see [FinancialOperations.running]. */
    private fun financialOperationRunning(): Boolean = operations.running

    /** Forgets shared-key recovery records waiting for [unlocked] once the setup source verified it. */
    private suspend fun completeKeyRecovery(unlocked: UnlockedSetup) = sharedKeys.complete(unlocked)

    private fun completeSale(
        id: String,
        start: PaymentStart,
        session: SaleSession = session(start.kind),
    ) {
        receipts.arm(id)
        session.complete(start)
    }

    internal val setupImport =
        SetupImport(
            setupTransfer,
            terminalSetup,
            gateway,
            api,
            tapToPay,
            SetupImportChecks(receiptBusinessDetails, terminalDetails, sharedKeys),
            historySwitches,
        ) { operations.running }

    /** Confirmed pricing changes and their recovery, including both payment kinds' sessions. */
    val pricingChanges =
        PricingChanges(settings, catalog, ::currency, sessions.values + savedCardSession) { operations.running }

    /** Optional offline catalog and read-only history examples, with scoped removal. */
    val sampleData =
        SampleData(
            database,
            catalog,
            sales,
            history,
            fixture = ::sampleFixture,
            starterRates = ::starterTaxRates,
            country = device.country,
            withSettings = { replaceReceiptDetails, write ->
                val added = pricingChanges.withStableSettings(write)
                pricingChanges.update { it.copy(receipt = sampleFixture().receipt(it.receipt, replaceReceiptDetails)) }
                added
            },
        )

    private fun sampleFixture(): SampleDataFixture =
        SampleDataFixture.read(
            context.assets
                .open("sample-data.json")
                .bufferedReader()
                .use { it.readText() },
            localizedContext.locale.toLanguageTag(),
        )

    /**
     * Starts the background work, once per process: marks transactions and captures interrupted by the last shutdown as UNKNOWN,
     * seeds the [starterTaxRates] on first launch, prunes old history, and starts the [terminalStatus] checks. On a
     * device that is not an Adyen terminal it also starts the [update] check, once.
     */
    fun start() {
        appScope.launch {
            storage.awaitReady()
            pricingChanges.recover()
            setupImport.recover()
            historySwitches.recover()
            history.settleInterrupted()
            catalog.seedDefaults(starterTaxRates())
            while (true) {
                val busy = financialOperationRunning()
                if (!busy) history.prune(settings.current().history.retentionDays, System.currentTimeMillis())
                delay(if (busy) 15.minutes else 1.days)
            }
        }
        appScope.launch {
            storage.awaitReady()
            terminalStatus.start()
            walletDiscovery.start()
            // Adyen terminals update through the Customer Area; only other devices check GitHub Releases.
            if (!device.isAdyenTerminal) update.start()
        }
    }

    /**
     * The tax rates a new installation starts with, named in the current language: the [StarterTax] of the device's
     * country (its standard rate, and a reduced one where it has one), then always a 0% rate.
     */
    internal fun starterTaxRates(): List<TaxRateEntity> = installation(localizedContext).starterTaxRates

    /**
     * What a new installation starts with in this device's country, worded with [localized]'s resources (the device's
     * language for the stored defaults, the app language for names made later).
     */
    private fun installation(localized: Context): NewInstallation =
        NewInstallation(country, localized.locale.language) { localized.getString(it.resource()) }

    /** Records actual touch, key or text-entry activity for both PIN sessions; call on the main thread. */
    fun userActivity() {
        val timeout = settingsState.value.security.autoLockMinutes.minutes.inWholeMilliseconds
        sessionLock.touch(timeout)
        managerLock.touch(timeout)
    }

    /** The currency payments are taken in with [appSettings] (by default the current ones). */
    fun currency(appSettings: AppSettings = settingsState.value): CurrencySpec =
        resolveCurrency(appSettings.payment, Locale.forLanguageTag("und-${device.country}"))

    private fun <T> store(
        name: String,
        serializer: KSerializer<T>,
        default: T,
    ): DataStore<T> =
        storage.protect(
            DataStoreFactory.create(
                serializer = JsonDataStoreSerializer(serializer, default),
                scope = CoroutineScope(ioDispatcher + SupervisorJob()),
                produceFile = { storageDir(name) },
            ),
        )

    /** The resource of each text a new installation starts with: the Android adapter of [NewInstallation]'s texts. */
    @StringRes
    private fun InstallationText.resource(): Int =
        when (this) {
            InstallationText.RECEIPT_TITLE -> R.string.receipt_default_title
            InstallationText.RECEIPT_FOOTER -> R.string.receipt_default_footer
            InstallationText.EMAIL_SUBJECT -> R.string.email_default_subject
            InstallationText.TAX_ID_DEFAULT -> R.string.receipt_default_tax_id
            InstallationText.TAX_ID_ABN -> R.string.receipt_tax_id_abn
            InstallationText.TAX_ID_GST -> R.string.receipt_tax_id_gst
            InstallationText.TAX_ID_GST_REGISTRATION -> R.string.receipt_tax_id_gst_registration
            InstallationText.TAX_ID_SST_REGISTRATION -> R.string.receipt_tax_id_sst_registration
            InstallationText.TAX_ID_GST_HST -> R.string.receipt_tax_id_gst_hst
            InstallationText.TAX_ID_RFC -> R.string.receipt_tax_id_rfc
            InstallationText.TAX_ID_CPF_CNPJ -> R.string.receipt_tax_id_cpf_cnpj
            InstallationText.TAX_ID_VAT -> R.string.receipt_tax_id_vat
            InstallationText.NO_TAX_NOTE -> R.string.receipt_default_no_gst_note
            InstallationText.TAX_MARKER_NOTE -> R.string.receipt_default_tax_marker_note
            InstallationText.TAX_GST -> R.string.tax_name_gst
            InstallationText.TAX_SST -> R.string.tax_name_sst
            InstallationText.TAX_IVA -> R.string.tax_name_iva
            InstallationText.TAX_VAT -> R.string.tax_name_vat
            InstallationText.TAX_STANDARD -> R.string.tax_default_standard
            InstallationText.TAX_REDUCED -> R.string.tax_default_reduced
            InstallationText.TAX_ZERO -> R.string.tax_default_zero
            InstallationText.SAMPLE_TAX -> R.string.receipt_sample_tax
        }

    private fun receiptTexts(languageTag: String = settingsState.value.languageTag): ReceiptTexts {
        val localized = context.withAppLanguage(languageTag.ifEmpty { settingsState.value.languageTag })
        return ReceiptTexts(
            labels = receiptLabels(localized),
            locale = localized.locale,
            sample = sampleTexts(localized),
            simulationNote = localized.getString(R.string.link_simulation_note),
            demoLink = localized.getString(R.string.link_demo_label),
            standing = { standing ->
                when (standing) {
                    // The receipt builder already warns about payments that were not approved.
                    PaymentStanding.CHARGED, PaymentStanding.NOT_APPROVED -> null

                    PaymentStanding.AWAITING_TIP -> localized.getString(R.string.detail_tip_awaiting)

                    PaymentStanding.HELD -> localized.getString(R.string.receipt_pre_auth_note)

                    PaymentStanding.CAPTURE_REQUESTED -> localized.getString(R.string.status_capture_requested)

                    PaymentStanding.CAPTURE_FAILED -> localized.getString(R.string.status_capture_failed)

                    PaymentStanding.CAPTURE_SENDING, PaymentStanding.CAPTURE_UNKNOWN -> localized.getString(R.string.status_capture_unknown)

                    PaymentStanding.HOLD_CANCELLED -> localized.getString(R.string.status_cancellation_requested)
                }
            },
            refund = { status ->
                localized.getString(
                    when (status) {
                        RefundStatus.REQUESTED -> R.string.refund_status_requested
                        RefundStatus.PENDING -> R.string.status_pending
                        RefundStatus.UNKNOWN -> R.string.status_unknown
                        RefundStatus.FAILED -> R.string.status_failed
                    },
                )
            },
            report = { label ->
                localized.getString(
                    when (label) {
                        ReportText.TITLE -> R.string.report_title
                        ReportText.GENERATED -> R.string.report_generated
                        ReportText.SALES -> R.string.report_sales
                        ReportText.TIPS -> R.string.report_tips
                        ReportText.SURCHARGES -> R.string.surcharge_label
                        ReportText.PURCHASE -> R.string.surcharge_purchase_known
                        ReportText.UNKNOWN_SURCHARGES -> R.string.surcharge_unknown_count
                        ReportText.REFUNDS -> R.string.report_refunds
                        ReportText.REFUND_SURCHARGES -> R.string.surcharge_return_fee
                        ReportText.UNKNOWN_REFUND_SURCHARGES -> R.string.surcharge_unknown_refund_count
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
        )
    }

    private fun sampleTexts(localizedContext: Context = this.localizedContext) =
        ReceiptSampleTexts(
            coffee = localizedContext.getString(R.string.receipt_sample_coffee),
            custom = localizedContext.getString(R.string.receipt_sample_item),
            taxed = installation(localizedContext).sampleTaxName,
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
            fullRefund = localizedContext.getString(R.string.receipt_full_refund),
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
            surcharge = localizedContext.getString(R.string.surcharge_label),
            unreported = localizedContext.getString(R.string.surcharge_unknown),
            charged = localizedContext.getString(R.string.surcharge_charged),
            purchase = localizedContext.getString(R.string.surcharge_purchase),
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

    // The demo label and note are the receipt's own, so an email's envelope says what its receipt says.
    private fun emailTexts() =
        EmailTexts(
            demoSubject = receiptTexts().demoLink,
            simulationNote = receiptTexts().simulationNote,
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

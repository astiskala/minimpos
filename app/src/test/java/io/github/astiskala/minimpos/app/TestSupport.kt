package io.github.astiskala.minimpos.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretCipher
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.email.MailTransport
import io.github.astiskala.minimpos.app.terminal.Attempt
import io.github.astiskala.minimpos.app.terminal.DeviceInfo
import io.github.astiskala.minimpos.terminal.checkout.PaymentLink
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkApi
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkRequest
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkResult
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkStatus
import io.github.astiskala.minimpos.terminal.parse.FormEncoding
import io.github.astiskala.minimpos.terminal.paymentsapp.AppLinkExchange
import io.github.astiskala.minimpos.terminal.paymentsapp.BoardingTarget
import io.github.astiskala.minimpos.terminal.paymentsapp.ManagementResult
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppManagement
import io.github.astiskala.minimpos.terminal.simulator.SimulatorConfig
import io.github.astiskala.minimpos.terminal.simulator.TerminalSimulator
import io.github.astiskala.minimpos.terminal.transport.AdyenLocalTransport
import io.github.astiskala.minimpos.terminal.transport.CloudCredentials
import io.github.astiskala.minimpos.terminal.transport.CloudDetection
import io.github.astiskala.minimpos.terminal.transport.CloudDevices
import io.github.astiskala.minimpos.terminal.transport.CloudEndpoint
import io.github.astiskala.minimpos.terminal.transport.CloudRegion
import io.github.astiskala.minimpos.terminal.transport.Delivery
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalHttpClient
import io.github.astiskala.minimpos.terminal.transport.TerminalKey
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import io.github.astiskala.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.security.ProviderException
import java.time.Instant
import java.util.Base64
import java.util.Collections
import javax.crypto.AEADBadTagException
import javax.mail.internet.MimeMessage
import kotlin.experimental.xor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Robolectric application that does not build the production container. */
class TestApplication : Application()

/** Reversible stand-in for the Android Keystore, which Robolectric does not provide. */
class FakeCipher : SecretCipher {
    /** Makes [decrypt] fail as if the ciphertext had been tampered with or the key were gone. */
    var fail = false

    /** Makes [encrypt] fail as if the Keystore were unavailable. */
    var failEncrypt = false

    override fun encrypt(plaintext: ByteArray): ByteArray {
        if (failEncrypt) throw ProviderException("Keystore unavailable")
        return byteArrayOf(1) + plaintext.map { it xor KEY }.toByteArray()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (fail) throw AEADBadTagException("tampered")
        return ciphertext.drop(1).map { it xor KEY }.toByteArray()
    }

    private companion object {
        const val KEY: Byte = 0x5A
    }
}

/**
 * A device for tests; give it a [detectedPoiId] to play an Adyen terminal (see [FakeTerminal]), or [paymentsApps] to
 * play a phone with the Adyen Payments app (see [FakePaymentsApp]).
 */
class FakeDevice(
    override val detectedPoiId: String? = null,
    override val model: String = "Robolectric",
    override val osVersion: String = "13",
    override val country: String = "AU",
    override var paymentsApps: Set<TerminalEnvironment> = emptySet(),
) : DeviceInfo

/** Collects the emails the app sends instead of delivering them. */
class RecordingTransport : MailTransport {
    /** The messages sent so far, in order. */
    val sent = mutableListOf<MimeMessage>()

    /** When set, thrown by every send, as a failing SMTP server would. */
    var failure: Exception? = null

    override fun send(message: MimeMessage) {
        failure?.let { throw it }
        sent += message
    }
}

/**
 * A payment terminal on this device, played by the simulator, that only answers requests protected with
 * [passphrase]; other keys are rejected the way a terminal rejects them. Records the hosts it was reached on.
 */
class FakeTerminal(
    private val passphrase: String = "correct horse battery staple",
) {
    /** Every host a transport was created for, in order (`localhost` when the app runs on the terminal). */
    val hosts = mutableListOf<String>()
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    /** Stands in for [AdyenLocalTransport]: the simulator for the right passphrase, a rejecting transport otherwise. */
    fun connect(
        host: String,
        key: TerminalKey,
    ): TerminalTransport {
        hosts += host
        return if (key.passphrase == passphrase) {
            simulator
        } else {
            TerminalTransport { _, _ ->
                Delivery.NotSent("Terminal rejected the request: Crypto error. ${TerminalHttpClient.KEY_ADVICE}")
            }
        }
    }
}

/**
 * Terminals in the cloud, played by the simulator: [detect] answers with [detection] and every request goes to the
 * simulator. Records the credentials it was created with and how often it looked for the endpoint.
 */
class FakeCloud(
    var detection: CloudDetection =
        CloudDetection.Found(
            CloudEndpoint(TerminalEnvironment.LIVE, CloudRegion.AU),
            listOf("S1F2-000158213605014", "AMS1-000168223606144"),
        ),
) : CloudDevices {
    /** The credentials of every connection, in order. */
    val credentials = mutableListOf<CloudCredentials>()

    /** How often [detect] was called. */
    var detections = 0
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    /** Stands in for `AdyenCloudDevices`. */
    fun connect(credentials: CloudCredentials): CloudDevices = also { this.credentials += credentials }

    override suspend fun detect(
        environment: TerminalEnvironment,
        poiId: String?,
        country: String,
    ): CloudDetection =
        detection.let {
            detections++
            if (it is CloudDetection.Found && it.endpoint.environment != environment) {
                it.copy(
                    endpoint =
                        if (environment ==
                            TerminalEnvironment.TEST
                        ) {
                            CloudEndpoint.TEST
                        } else {
                            CloudEndpoint(environment, CloudRegion.AU)
                        },
                )
            } else {
                it
            }
        }

    override fun transport(endpoint: CloudEndpoint): TerminalTransport = simulator
}

/**
 * The Adyen Payments app on a phone, played by the simulator: it boards (once [boarded] or asked to) and answers
 * payments and refunds encrypted with the shared key [passphrase] (identifier `key`, version 1). Records the links.
 */
class FakePaymentsApp(
    passphrase: String = "correct horse battery staple",
) : AppLinkExchange {
    private val gson = TerminalAPIGsonBuilder.create()
    private val crypto = NexoCrypto(TerminalKey("key", passphrase, 1).toSecurityKey())
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    /** Every link opened, in order. */
    val opened = mutableListOf<String>()

    /** Whether it is boarded, and so answers the boarding check (unless it reboards) with its installation ID. */
    var boarded = false

    override suspend fun exchange(
        link: String,
        packageName: String,
        timeout: Duration,
    ): String {
        opened += link
        val parameters = FormEncoding.decode(link.substringAfter('?'))
        val returnUrl = URLDecoder.decode(parameters.getValue("returnUrl"), "UTF-8")
        return when {
            link.contains("/boarded?") && boarded && "reboard=true" !in link -> "$returnUrl?boarded=true&installationId=$INSTALLATION_ID"
            link.contains("/boarded?") -> "$returnUrl?boarded=false&installationId=$INSTALLATION_ID&boardingRequestToken=BRT"
            link.contains("/board?") -> "$returnUrl?boarded=true&installationId=$INSTALLATION_ID".also { boarded = true }
            else -> "$returnUrl?response=${answer(parameters.getValue("request"))}"
        }
    }

    override fun lateReplies(): List<String> = emptyList()

    private suspend fun answer(request: String): String {
        val secured = gson.fromJson(String(Base64.getUrlDecoder().decode(request)), TerminalAPISecuredRequest::class.java)
        val delivery = simulator.send(gson.fromJson(crypto.decrypt(secured.saleToPOIRequest), TerminalAPIRequest::class.java), 1.seconds)
        val response = checkNotNull((delivery as Delivery.Answered).response)
        val encrypted =
            TerminalAPISecuredResponse().apply {
                saleToPOIResponse =
                    crypto.encrypt(gson.toJson(response), response.saleToPOIResponse.messageHeader)
            }
        return Base64.getUrlEncoder().encodeToString(gson.toJson(encrypted).toByteArray())
    }

    /** The installation ID it boards with. */
    companion object {
        /** The installation ID the fake Payments app reports. */
        const val INSTALLATION_ID = "INSTALLATION-1"
    }
}

/** Adyen's Management API for the Payments app: answers with [result] and records the boarding token requests. */
class FakeManagement(
    var result: ManagementResult = ManagementResult.Done("BT-1"),
) : PaymentsAppManagement {
    /** Every boarding token request, in order. */
    val requests = mutableListOf<BoardingTarget>()

    /** Every revoked installation ID, in order. */
    val revoked = mutableListOf<String>()

    override suspend fun boardingToken(
        target: BoardingTarget,
        boardingRequestToken: String,
    ): ManagementResult = result.also { requests += target }

    override suspend fun revoke(
        merchantAccount: String,
        installationId: String,
    ): ManagementResult = result.also { revoked += installationId }
}

/**
 * Adyen's payment links: answers with a link in [status] (or the result set for a call), and records what was sent.
 * Thread-safe, as the container calls it from its own scope.
 */
class FakeLinkApi(
    @Volatile var status: PaymentLinkStatus = PaymentLinkStatus.ACTIVE,
) : PaymentLinkApi {
    /** Answers the next creations instead of a link, when set. */
    @Volatile var createResult: PaymentLinkResult? = null

    /** Answers the next status checks instead of a link, when set. */
    @Volatile var getResult: PaymentLinkResult? = null

    /** Answers the next expiries instead of an expired link, when set. */
    @Volatile var expireResult: PaymentLinkResult? = null

    /** Every creation, with its idempotency key, in order. */
    val created: MutableList<Pair<PaymentLinkRequest, String>> = Collections.synchronizedList(mutableListOf())

    /** Every link asked about, in order. */
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every link expired, in order. */
    val expired: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The link in [status]. */
    fun link(status: PaymentLinkStatus = this.status) = PaymentLink(LINK_ID, URL, status, EXPIRES)

    override suspend fun create(
        request: PaymentLinkRequest,
        idempotencyKey: String,
    ): PaymentLinkResult = (createResult ?: PaymentLinkResult.Answered(link())).also { created += request to idempotencyKey }

    override suspend fun status(linkId: String): PaymentLinkResult =
        (getResult ?: PaymentLinkResult.Answered(link())).also {
            asked +=
                linkId
        }

    override suspend fun expire(linkId: String): PaymentLinkResult =
        (expireResult ?: PaymentLinkResult.Answered(link(PaymentLinkStatus.EXPIRED))).also { expired += linkId }

    /** The link it answers with. */
    companion object {
        /** Adyen's ID of the link. */
        const val LINK_ID = "PL50C5F751CED39G71"

        /** The link's address. */
        const val URL = "https://test.adyen.link/PL50C5F751CED39G71"

        /** When Adyen says it expires. */
        val EXPIRES: Instant = Instant.parse("2026-10-03T09:30:00Z")
    }
}

/**
 * A complete [AppContainer] for Robolectric tests: an in-memory database, settings and secrets in a temporary
 * directory, [FakeCipher] instead of the Keystore and [RecordingTransport] instead of SMTP. Call [close] after each
 * test, or use it as a rule: compose tests order it outside the compose rule, so the screens and their view models are
 * gone before the database closes. The container's background work ([AppContainer.start]) only runs when a test starts
 * it.
 */
class TestEnvironment(
    device: DeviceInfo = FakeDevice(),
    /** Replaces the real (network) terminal connection. */
    terminal: FakeTerminal? = null,
    /** Runs the container's application scope (payments, refunds and connection checks). */
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Replaces the Cloud device API. */
    cloud: FakeCloud = FakeCloud(),
    /** Replaces the Adyen Payments app. */
    paymentsApp: FakePaymentsApp = FakePaymentsApp(),
    /** Replaces the Management API that boards the Payments app. */
    management: FakeManagement = FakeManagement(),
    /** Replaces Adyen's payment links. */
    links: FakeLinkApi = FakeLinkApi(),
    stores: StoreDetailsApi = StoreDetailsApi { StoreListing.Listed(emptyList()) },
    terminalDetails: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun terminals(environment: TerminalEnvironment) =
                TerminalListing.Listed(
                    listOf("AMS1-000168223606144", "S1F2-000158213605014").map { TerminalDetails(it, "Merchant", "192.168.1.42") },
                    environment,
                )

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = null
        },
    terminalEnvironment: suspend () -> TerminalEnvironment? = { TerminalEnvironment.TEST },
) : ExternalResource() {
    /** Robolectric's application context. */
    val context: Context = ApplicationProvider.getApplicationContext()

    init {
        forgetSharedFileRoots()
    }

    /** Where the DataStore files live; deleted by [close]. */
    val dir: File = Files.createTempDirectory("minimpos").toFile()

    /** The container's cipher, to make secret storage fail. */
    val cipher = FakeCipher()

    /** The emails the container sent. */
    val mail = RecordingTransport()

    /** The container's application scope. */
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The container under test. */
    val container =
        AppContainer(
            context = context,
            // Main-thread queries are allowed so tests can read the database directly.
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build(),
            cipher = cipher,
            device = device,
            mailTransport = mail,
            storageDir = { File(dir, it) },
            appScope = scope,
            terminalTransport = { host, key, tls -> terminal?.connect(host, key) ?: AdyenLocalTransport(host, key, tls) },
            cloudDevices = cloud::connect,
            paymentsAppExchange = paymentsApp,
            paymentsAppManagement = { _, _ -> management },
            paymentLinks = { links },
            storeDetails = { _, _ -> stores },
            terminalDetails = { terminalDetails },
            terminalEnvironment = terminalEnvironment,
        )

    /**
     * The Checkout API is entered (merchant account and API key), so payments to a real destination no longer wait for
     * it; whether it is complete also depends on the environment.
     */
    fun useCheckoutApi() {
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        updateSettings {
            it.copy(
                terminal = it.terminal.copy(merchantAccount = "HarbourCoffeeCOM", environment = TerminalEnvironment.TEST),
            )
        }
    }

    /**
     * Payment links work: the merchant account and API key are saved, and payments go to this terminal (whose
     * environment, TEST, is detected), so the Checkout API is set up.
     */
    fun useLinks() {
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.TERMINAL,
                        merchantAccount = "HarbourCoffeeCOM",
                        environment = TerminalEnvironment.TEST,
                    ),
            )
        }
        runBlocking { withTimeout(5_000) { container.terminalStatus.state.first { it.paymentLinks } } }
    }

    /** Simulator with no delay, and settings loaded into the container's state. */
    fun useSimulator(transform: (AppSettings) -> AppSettings = { it }) =
        updateSettings {
            transform(
                it.copy(
                    terminal = it.terminal.copy(mode = TerminalMode.SIMULATOR),
                    simulator = it.simulator.copy(delayMillis = 0),
                ),
            )
        }

    /** Stores the settings with [transform] applied and waits until the container's settings state shows them. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) =
        runBlocking {
            container.settings.update(transform)
            val expected = container.settings.current()
            withTimeout(5_000) { container.settingsState.first { it == expected } }
        }

    /** Stops the container's background work, closes the database and deletes the temporary files. */
    fun close() {
        scope.cancel()
        container.database.close()
        dir.deleteRecursively()
    }

    override fun after() = close()
}

/**
 * Forgets the folders `FileProvider` resolved for earlier tests. It keeps them in a static cache, and Robolectric gives
 * each test its own data folder in the same class loader, so a later test's shared file would not be found.
 */
fun forgetSharedFileRoots() {
    val cache =
        checkNotNull(
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null),
        )
    synchronized(cache) { (cache as MutableMap<*, *>).clear() }
}

/** Starter tax rates for tests: GST at 10%, then GST-free at 0%. */
val GST_RATES =
    listOf(
        TaxRateEntity(name = "GST", rateMilliPercent = 10_000),
        TaxRateEntity(name = "GST-free", rateMilliPercent = 0),
    )

/** Runs [block] to completion on the calling thread, failing the test after 10 seconds. */
fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

/** What came of a request the gateway sent; fails the test when nothing was sent. */
fun <T> Attempt<T>.made(): T = (this as Attempt.Made).result

/**
 * Waits until [condition] holds. Saves resume on the main looper, which Robolectric pauses, so each check drains it
 * first; a plain `waitUntil` would only sleep while the save stays queued.
 */
fun ComposeTestRule.awaitCondition(
    description: String,
    condition: () -> Boolean,
) {
    waitUntil(description, timeoutMillis = 10_000) {
        waitForIdle()
        condition()
    }
}

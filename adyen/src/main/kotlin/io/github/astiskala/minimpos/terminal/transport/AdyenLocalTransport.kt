package io.github.astiskala.minimpos.terminal.transport

import com.adyen.Client
import com.adyen.Config
import com.adyen.enums.Environment
import com.adyen.httpclient.ClientInterface
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.security.SecurityKey
import com.adyen.service.TerminalLocalAPI
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.security.exception.NexoCryptoException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The terminal's shared key, set up in the Customer Area (Terminal settings > Integrations > Encryption key). Requests
 * and responses are encrypted and signed with keys derived from it by the library's `NexoCrypto`, so a mismatch shows
 * up as a rejected request or an unverifiable reply. [toString] leaves out the passphrase, so a key can be logged.
 *
 * Constructing it with a blank identifier, an empty passphrase or a version below 1 throws [IllegalArgumentException].
 *
 * @property keyIdentifier The key's name in the Customer Area.
 * @property passphrase The key's secret passphrase.
 * @property keyVersion The key's version in the Customer Area, starting at 1.
 */
data class TerminalKey(
    val keyIdentifier: String,
    val passphrase: String,
    val keyVersion: Int,
) {
    init {
        require(keyIdentifier.isNotBlank()) { "Key identifier is required" }
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        require(keyVersion > 0) { "Key version must be positive" }
    }

    /** The key in the Adyen library's form, with Adyen crypto version 1. */
    fun toSecurityKey(): SecurityKey =
        SecurityKey().apply {
            keyIdentifier = this@TerminalKey.keyIdentifier
            passphrase = this@TerminalKey.passphrase
            keyVersion = this@TerminalKey.keyVersion
            adyenCryptoVersion = ADYEN_CRYPTO_VERSION
        }

    override fun toString() = "TerminalKey($keyIdentifier, v$keyVersion)"

    private companion object {
        const val ADYEN_CRYPTO_VERSION = 1
    }
}

/**
 * Local Terminal API through Adyen's [TerminalLocalAPI], which encrypts requests with the shared key and posts them to
 * `https://<host>:8443/nexo` (use `localhost` when the app runs on the terminal itself).
 *
 * [send] runs the library's blocking call on the dispatcher; cancelling the coroutine interrupts it. What the HTTP
 * client throws ([TerminalHttpClient]) decides the [Delivery]: no connection, an untrusted certificate or a rejection is
 * [Delivery.NotSent], anything else [Delivery.MaybeSent]; so is a reply that fails decryption or its HMAC check (with
 * advice to check the shared key) and any other library failure.
 */
class AdyenLocalTransport(
    /** The terminal's IP address or host name, without scheme or port. */
    private val host: String,
    /** The shared key to encrypt requests and verify replies with. */
    private val key: TerminalKey,
    /** Decides which terminals are trusted; used only by the default HTTP client. */
    tls: TerminalTls,
    /**
     * The HTTP client installed on the library's `Client`, replacing its Apache HttpClient, which crashes on Android.
     * Not named `httpClient`: inside `Client(...).apply { }` that name would resolve to `Client.getHttpClient()`, which
     * creates the Apache client.
     */
    private val http: ClientInterface = TerminalHttpClient(tls, NexoCrypto(key.toSecurityKey())),
    /** Where the blocking library calls run. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TerminalTransport {
    /** One API per timeout: the library reads timeouts from the client configuration. */
    private val apis = ConcurrentHashMap<Long, TerminalLocalAPI>()

    override suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery =
        runInterruptible(dispatcher) {
            try {
                Delivery.Answered(api(timeout.inWholeMilliseconds).request(request))
            } catch (e: IOException) {
                e.toDelivery("No response from the terminal")
            } catch (ignored: NexoCryptoException) {
                KEY_MISMATCH
            } catch (ignored: GeneralSecurityException) {
                // A wrong passphrase usually fails AES padding before the HMAC check.
                KEY_MISMATCH
            } catch (
                // TerminalLocalAPI.request is declared to throw Exception.
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                Delivery.MaybeSent("Unexpected response from the terminal")
            }
        }

    private fun api(timeoutMillis: Long): TerminalLocalAPI =
        apis.getOrPut(timeoutMillis) {
            val config =
                Config().apply {
                    // Required by Client, where it only picks the cloud endpoint; the terminal's certificate decides here.
                    environment = Environment.TEST
                    terminalApiLocalEndpoint = "https://$host"
                    connectionTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    readTimeoutMillis = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            TerminalLocalAPI(Client(config).apply { setHttpClient(http) }, key.toSecurityKey())
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        val KEY_MISMATCH = Delivery.MaybeSent("The terminal's reply could not be verified. ${TerminalHttpClient.KEY_ADVICE}")
    }
}

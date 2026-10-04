package io.github.astiskala.minimpos.terminal.transport

import com.adyen.terminal.security.TerminalCommonNameValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for local Terminal API calls, as described in Adyen's "Protect local communications": only certificates chaining
 * to one of Adyen's terminal fleet roots are trusted, and the leaf's common name must be a terminal name of that root's
 * environment, checked with Adyen's [TerminalCommonNameValidator] (the host itself, localhost or an IP, is not in the
 * certificate). Reads the certificate's environment, enforcing [expectedEnvironment] when supplied for a network
 * terminal, and reports it to [onEnvironment] on every verified connection.
 */
class TerminalTls(
    /** The trusted root per environment; by default Adyen's terminal fleet roots bundled with this module. */
    private val roots: Map<TerminalEnvironment, X509Certificate> = TerminalEnvironment.entries.associateWith(::loadRoot),
    /** Told the environment of each verified connection, on the thread making the connection. */
    private val onEnvironment: (TerminalEnvironment) -> Unit = {},
    /** Required environment for a network terminal; null accepts either for the terminal running Mini mPOS. */
    private val expectedEnvironment: TerminalEnvironment? = null,
    /** Runs the blocking certificate-only TLS handshake. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Validates certificate chains against the Adyen roots only, ignoring the device's certificate authorities. */
    val trustManager: X509TrustManager

    /** Opens TLS connections that use [trustManager]. */
    val socketFactory: SSLSocketFactory

    /**
     * Stands in for host name verification: accepts a connection only if [environmentOf] finds an environment for the
     * peer's certificate chain, and reports that environment.
     */
    val hostnameVerifier = HostnameVerifier { _, session -> verifiedEnvironment(session) != null }

    init {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        roots.forEach { (environment, cert) -> keyStore.setCertificateEntry("adyen-${environment.name.lowercase()}", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /**
     * The environment of a certificate [chain] (leaf first) that the trust manager accepted: the environment of the root
     * it chains to, provided the leaf is named as a terminal of that environment. Null for anything else.
     *
     * Only the chain's anchoring and the leaf's name are checked here; validity periods and the rest of the path are
     * the [trustManager]'s job.
     */
    fun environmentOf(chain: List<X509Certificate>): TerminalEnvironment? {
        val leaf = chain.firstOrNull() ?: return null
        val environment = roots.entries.firstOrNull { (_, root) -> chain.any { it == root || it.isSignedBy(root) } }?.key
        return environment?.takeIf { TerminalCommonNameValidator.validateCertificate(leaf, it.adyen) }
    }

    /**
     * Reads a verified terminal certificate without sending an API request or needing a shared key. Safe on any
     * thread. Null means unreachable, untrusted or not in [expectedEnvironment]; cancellation propagates.
     *
     * @param host Terminal address, normally localhost for the device running Mini mPOS.
     * @param port TLS port, 8443 for Adyen terminals; tests use an isolated server.
     */
    suspend fun readEnvironment(
        host: String,
        port: Int = 8443,
    ): TerminalEnvironment? =
        runInterruptible(dispatcher) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS)
                    (socketFactory.createSocket(socket, host, port, true) as SSLSocket).use { tls ->
                        tls.soTimeout = PROBE_TIMEOUT_MILLIS
                        tls.startHandshake()
                        verifiedEnvironment(tls.session)
                    }
                }
            } catch (_: IOException) {
                null
            }
        }

    private fun verifiedEnvironment(session: SSLSession): TerminalEnvironment? {
        val chain = runCatching { session.peerCertificates.filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())
        return environmentOf(chain)?.takeIf { expectedEnvironment == null || it == expectedEnvironment }?.also(onEnvironment)
    }

    private fun X509Certificate.isSignedBy(issuer: X509Certificate) =
        issuerX500Principal == issuer.subjectX500Principal && runCatching { verify(issuer.publicKey) }.isSuccess

    /** Access to the bundled root certificates. */
    companion object {
        private const val PROBE_TIMEOUT_MILLIS = 5_000

        /**
         * Reads the terminal fleet root certificate of [environment] from this module's resources.
         *
         * @throws IllegalStateException If the certificate is not bundled.
         */
        fun loadRoot(environment: TerminalEnvironment): X509Certificate {
            val stream =
                TerminalTls::class.java.getResourceAsStream(environment.certificateResource)
                    ?: error("Missing Adyen root certificate ${environment.certificateResource}")
            return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        }
    }
}

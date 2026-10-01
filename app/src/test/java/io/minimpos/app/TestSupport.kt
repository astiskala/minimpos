package io.minimpos.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.security.SecretCipher
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.email.MailTransport
import io.minimpos.app.terminal.DeviceInfo
import io.minimpos.terminal.simulator.SimulatorConfig
import io.minimpos.terminal.simulator.TerminalSimulator
import io.minimpos.terminal.transport.AdyenLocalTransport
import io.minimpos.terminal.transport.TerminalHttpClient
import io.minimpos.terminal.transport.TerminalKey
import io.minimpos.terminal.transport.TerminalRejectedException
import io.minimpos.terminal.transport.TerminalTransport
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
import java.nio.file.Files
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.mail.internet.MimeMessage
import kotlin.experimental.xor

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

/** A device for tests; give it a [detectedPoiId] to play an Adyen terminal (see [FakeTerminal]). */
class FakeDevice(
    override val detectedPoiId: String? = null,
    override val model: String = "Robolectric",
    override val osVersion: String = "13",
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
                throw TerminalRejectedException("Terminal rejected the request: Crypto error. ${TerminalHttpClient.KEY_ADVICE}")
            }
        }
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
) : ExternalResource() {
    /** Robolectric's application context. */
    val context: Context = ApplicationProvider.getApplicationContext()

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
        )

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

/** Runs [block] to completion on the calling thread, failing the test after 10 seconds. */
fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

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

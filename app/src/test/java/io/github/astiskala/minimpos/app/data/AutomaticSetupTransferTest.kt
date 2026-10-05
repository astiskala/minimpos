package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticSetupTransferTest {
    private val env = TestEnvironment()
    private val seal = TransferSeal(iterations = 1_000)
    private val code = seal.newCode()

    private fun setup(onTerminal: Boolean = false) =
        SetupTransfer(env.container.catalog, env.container.settings, env.container.secrets, seal, onTerminal = onTerminal)

    private fun transfer(
        destination: String = "network",
        secrets: String? = """{"ADYEN_API_KEY":"imported-key"}""",
    ) = Transfer(
        connection =
            """{"destination":"$destination","automatic":true,"environment":"LIVE","liveUrlPrefix":"1797a841fbb37ca7-AdyenDemo"}""",
        sealedSecrets = secrets?.let { SealedSecrets(seal.seal(it.toByteArray(), code)) },
    )

    @After
    fun tearDown() = env.close()

    @Test
    fun `Automatic imports the API key and LIVE prefix without replacing missing manual details`() {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(host = "192.168.1.20", merchantAccount = "ExistingAccount", keyIdentifier = "existing-key"))
        }
        val setup = setup()
        val received = setup.receive(transfer())
        assertThat(received.automatic).isTrue()
        val result = (await { setup.import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported).result
        assertThat(result.automaticSetup).isTrue()
        assertThat(result.secrets).containsExactly(Secret.ADYEN_API_KEY)
        val terminal = await { env.container.settings.current() }.terminal
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(terminal.liveUrlPrefix).isEqualTo("1797a841fbb37ca7-AdyenDemo")
        assertThat(terminal.host).isEqualTo("192.168.1.20")
        assertThat(terminal.merchantAccount).isEqualTo("ExistingAccount")
        assertThat(terminal.keyIdentifier).isEqualTo("existing-key")
    }

    @Test
    fun `automatic setup is offered only for a destination supported by this device`() {
        listOf(false, true).forEach { onTerminal ->
            val setup = setup(onTerminal)
            listOf("thisTerminal", "network", "cloud", "tapToPay").forEach { destination ->
                val received = setup.receive(transfer(destination))
                val result = (await { setup.import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported).result
                val supported = if (onTerminal) destination == "thisTerminal" else destination in listOf("network", "cloud", "tapToPay")
                assertThat(result.automaticSetup).isEqualTo(supported)
            }
        }
        val setup = setup()
        val unspecified = setup.receive(Transfer(connection = """{"automatic":true}"""))
        assertThat((await { setup.import(unspecified, ImportMode.MERGE) } as ImportOutcome.Imported).result.automaticSetup).isFalse()
    }

    @Test
    fun `skipped or absent API secrets never start discovery with an old stored key`() {
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "old-key") }
        val setup = setup()
        val skipped = await { setup.import(setup.receive(transfer()), ImportMode.MERGE) } as ImportOutcome.Imported
        assertThat(skipped.secretsSkipped).isTrue()
        assertThat(skipped.result.automaticSetup).isFalse()
        listOf(null, """{"TERMINAL_PASSPHRASE":"other-secret"}""").forEach { secrets ->
            val received = setup.receive(transfer(secrets = secrets))
            val result = (await { setup.import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported).result
            assertThat(result.automaticSetup).isFalse()
        }
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("old-key")
    }

    @Test
    fun `an API key that cannot be stored leaves Automatic for manual completion`() {
        val setup = setup()
        val received = setup.receive(transfer())
        env.cipher.failEncrypt = true
        val result = (await { setup.import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported).result
        assertThat(result.automaticSetup).isFalse()
        assertThat(result.secrets).isEmpty()
        assertThat(result.secretsError).contains("Keystore")
        assertThat(result.connection).isTrue()
    }

    @Test
    fun `Automatic LIVE codes made by the offline helper import with a discovery handoff`() {
        // docs/js/setup.js, zero-filled test randomness, Automatic network LIVE, only API key and prefix enabled.
        val chunk =
            "MPC1:0000:1/1:CT03VLX+OW+OSK78P1V500D0FFU000000000000000000000000000000000000000350A3P4DOD.7X2RX ROEP5 " +
                "4*WB41GSG6G3ECQNT.J7\$JBTH: 9Z.T/JCR\$9KB3Y/UEAM9ZPVAOIWBN6C6WDTF46\$CBWE..DBWE-3EWE4*F47\$CK4F-KEIE4" +
                "QF48%E+3EIECOEDWE4KWE%\$E3Q51\$CS/E0LE9/D1\$CUUEWF7:S9Z+AIE4\$F4/EDL C.KET7A% C0FDWE4NE47:6207X471B6VJC" +
                "GL6GPC+/60C8RFFD.D0\$CA2EZ2"
        val setup = setup()
        val received = setup.receive(TransferCodec.decode(checkNotNull(QrChunks.parse(chunk)).data))
        val result = (await { setup.import(received, ImportMode.MERGE, "2222-2222-2222") } as ImportOutcome.Imported).result
        assertThat(result.automaticSetup).isTrue()
        assertThat(result.secrets).containsExactly(Secret.ADYEN_API_KEY)
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("demo-checkout-key")
        val terminal = await { env.container.settings.current() }.terminal
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(terminal.liveUrlPrefix).isEqualTo("1797a841fbb37ca7-AdyenDemo")
        assertThat(terminal.merchantAccount).isEmpty()
        assertThat(terminal.host).isEmpty()
    }
}

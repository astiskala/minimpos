package io.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.repo.ImportMode
import io.minimpos.app.data.security.PinCheck
import io.minimpos.app.data.security.PinManager
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.TransferSeal
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.MerchantCopyPolicy
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.SmtpSecurity
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.transfer.ImportOutcome
import io.minimpos.app.data.transfer.SetupTransfer
import io.minimpos.app.data.transfer.TransferContents
import io.minimpos.core.codec.Base45
import io.minimpos.core.codec.QrChunkAssembler
import io.minimpos.core.codec.QrChunks
import io.minimpos.core.codec.SealedSecrets
import io.minimpos.core.codec.Transfer
import io.minimpos.core.codec.TransferCodec
import io.minimpos.core.codec.TransferFormatException
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Setting up a second terminal from a first one: what travels, what stays, and the sealed secrets. */
@RunWith(RobolectricTestRunner::class)
class SetupTransferTest {
    private val source = TestEnvironment()
    private val target = TestEnvironment()
    private val seal = TransferSeal(iterations = 1_000)

    private fun setup(env: TestEnvironment) = SetupTransfer(env.container.catalog, env.container.settings, env.container.secrets, seal)

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun configureSource() {
        val vat =
            await {
                val rate = source.container.catalog.saveTaxRate(TaxRateEntity(name = "VAT", rateMilliPercent = 25_500))
                source.container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = rate))
                rate
            }
        source.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.TERMINAL,
                        environment = TerminalEnvironment.LIVE,
                        host = "192.168.1.20",
                        poiIdOverride = "S1F2-000158200000001",
                        saleId = "Cafe",
                        keyIdentifier = "store-key",
                        keyVersion = 3,
                        timeoutSeconds = 180,
                        merchantAccount = "HarbourCoffeeCOM",
                        liveUrlPrefix = "abc123-Harbour",
                    ),
                payment =
                    it.payment.copy(
                        currencyCode = "EUR",
                        defaultTaxRateId = vat,
                        referencePrefix = "T1",
                        emailCapture = EmailCapture.BEFORE_PAYMENT,
                        emailReferenceSalt = "pepper",
                        tipOnReceiptDefaultOn = true,
                    ),
                receipt =
                    it.receipt.copy(
                        businessName = "Harbour Coffee Co.",
                        footer = "Ta!",
                        printerMode = PrinterMode.ON,
                        merchantCopy = MerchantCopyPolicy.ALWAYS,
                        charsPerLine = 42,
                    ),
                email = it.email.copy(host = "smtp.example.com", port = 465, security = SmtpSecurity.SSL, fromAddress = "shop@example.com"),
                security = it.security.copy(autoLockMinutes = 10),
                simulator = it.simulator.copy(delayMillis = 9),
                history = it.history.copy(retentionDays = 30),
            )
        }
        await {
            source.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse")
            source.container.secrets.set(Secret.SMTP_PASSWORD, "hunter2")
            source.container.secrets.set(Secret.ADYEN_API_KEY, "AQE-key")
            PinManager(source.container.secrets, iterations = 1_000).setPin("2468")
        }
    }

    @Test
    fun `a new terminal gets the catalogue, the shared settings and, with the code, the secrets`() {
        configureSource()
        val export = await { setup(source).export(TransferContents(), "EUR") }
        assertThat(export.code).matches("[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}")
        assertThat(
            export.secrets,
        ).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.SMTP_PASSWORD, Secret.ADYEN_API_KEY, Secret.PIN_VERIFIER)
        assertThat(export.settings).isTrue()
        // Nothing secret can be read from the codes.
        val transfer = TransferCodec.decode(export.payload)
        assertThat(transfer.settings).doesNotContain("hunter2")
        assertThat(String(transfer.sealedSecrets!!.toByteArray(), Charsets.ISO_8859_1)).doesNotContain("hunter2")
        assertThat(transfer.settings).doesNotContain("192.168.1.20")

        target.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.SIMULATOR, host = "10.0.0.9")) }
        val receiving = setup(target)
        val received = receiving.receive(transfer)
        assertThat(received.hasSettings).isTrue()
        assertThat(received.hasSecrets).isTrue()
        // The transferred settings choose EUR, so this terminal's own currency does not matter.
        assertThat(received.currencyMatches("NZD")).isTrue()
        assertThat(received.accepts("abc")).isFalse()
        assertThat(await { receiving.import(received, ImportMode.MERGE, "abc") }).isEqualTo(ImportOutcome.WrongCode)
        assertThat(await { receiving.import(received, ImportMode.MERGE, "2222-2222-2222") }).isEqualTo(ImportOutcome.WrongCode)
        assertThat(
            await {
                target.container.catalog.products
                    .first()
            },
        ).isEmpty()
        // Typed in lower case and without hyphens.
        val imported = await { receiving.import(received, ImportMode.MERGE, export.code!!.lowercase().replace("-", " ")) }
        val result = (imported as ImportOutcome.Imported).result
        assertThat(imported.secretsSkipped).isFalse()
        assertThat(result.catalogue!!.productsAdded).isEqualTo(1)
        assertThat(result.settings).isTrue()
        assertThat(result.secrets).hasSize(4)
        assertThat(result.secretsError).isNull()

        val copied = await { target.container.settings.current() }
        val original = await { source.container.settings.current() }
        // Where payments go, the address, POIID, environment and simulator stay this terminal's own.
        assertThat(copied.terminal.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(copied.terminal.host).isEqualTo("10.0.0.9")
        assertThat(copied.terminal.poiIdOverride).isEmpty()
        assertThat(copied.terminal.environment).isNull()
        assertThat(copied.simulator.delayMillis).isNotEqualTo(9)
        val own = original.terminal
        assertThat(copied.terminal.copy(mode = own.mode, host = own.host, poiIdOverride = own.poiIdOverride, environment = own.environment))
            .isEqualTo(own)
        assertThat(copied.receipt).isEqualTo(original.receipt)
        assertThat(copied.email).isEqualTo(original.email)
        assertThat(copied.security).isEqualTo(original.security)
        assertThat(copied.history).isEqualTo(original.history)
        // The default tax rate is found by name and rate among this terminal's rates.
        val vat =
            await {
                target.container.catalog.taxRates
                    .first()
            }.single { it.name == "VAT" }
        assertThat(copied.payment).isEqualTo(original.payment.copy(defaultTaxRateId = vat.id))

        assertThat(await { target.container.secrets.get(Secret.SMTP_PASSWORD) }).isEqualTo("hunter2")
        assertThat(await { target.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse")
        assertThat(await { target.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("AQE-key")
        // The same PIN works.
        assertThat(await { target.container.pinManager.verify("2468") }).isEqualTo(PinCheck.Accepted)
        assertThat(await { target.container.pinManager.verify("1111") }).isInstanceOf(PinCheck.Rejected::class.java)
    }

    @Test
    fun `defaults replace this terminal's values, and the default tax rate is dropped when no rate matches`() {
        target.updateSettings {
            it.copy(
                payment = it.payment.copy(referencePrefix = "OLD", defaultTaxRateId = 99),
                receipt = it.receipt.copy(footer = "Old footer"),
            )
        }
        val export = await { setup(source).export(TransferContents(catalogue = false, secrets = false), "AUD") }
        assertThat(export.catalogue).isNull()
        assertThat(export.code).isNull()
        assertThat(TransferCodec.decode(export.payload).settings).isEqualTo("{}")
        val outcome = await { setup(target).import(setup(target).receive(TransferCodec.decode(export.payload)), ImportMode.REPLACE) }
        assertThat((outcome as ImportOutcome.Imported).result.catalogue).isNull()
        assertThat(outcome.secretsSkipped).isFalse()
        val copied = await { target.container.settings.current() }
        assertThat(copied.payment.referencePrefix).isEmpty()
        assertThat(copied.payment.defaultTaxRateId).isNull()
        assertThat(copied.receipt.footer).isEqualTo("Thank you!")
    }

    @Test
    fun `secrets are only included when set, and alone they still make a transfer`() {
        val none = await { setup(source).export(TransferContents(catalogue = true, settings = false), "AUD") }
        assertThat(none.code).isNull()
        assertThat(none.secrets).isEmpty()
        assertThat(Base45.decode(none.payload)[0].toInt()).isEqualTo(5)
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val secretsOnly = await { setup(source).export(TransferContents(catalogue = false, settings = false), "AUD") }
        assertThat(secretsOnly.secrets).containsExactly(Secret.SMTP_PASSWORD)
        val received = setup(target).receive(TransferCodec.decode(secretsOnly.payload))
        assertThat(received.catalogue).isNull()
        assertThat(received.hasSettings).isFalse()
        assertThat(received.currencyMatches("AUD")).isTrue()
        assertThat(received.accepts("")).isTrue()
        assertThat(await { setup(target).unlock(received, secretsOnly.code!!) }).containsExactly(Secret.SMTP_PASSWORD, "pw")
        assertThrows(IllegalArgumentException::class.java) {
            await { setup(source).export(TransferContents(catalogue = false, settings = false, secrets = false), "AUD") }
        }
    }

    @Test
    fun `unreadable settings and secrets are rejected`() {
        assertThrows(TransferFormatException::class.java) { setup(target).receive(Transfer(settings = "not json")) }
        assertThrows(TransferFormatException::class.java) { setup(target).receive(Transfer(settings = """{"receipt":"x"}""")) }
        // Values out of range are brought into range.
        val odd =
            setup(target).receive(
                Transfer(
                    settings =
                        """{"receipt":{"charsPerLine":500},"email":{"port":0},""" +
                            """"terminal":{"timeoutSeconds":5,"keyVersion":0},"future":1}""",
                ),
            )
        await { setup(target).import(odd, ImportMode.MERGE) }
        val copied = await { target.container.settings.current() }
        assertThat(copied.receipt.charsPerLine).isEqualTo(64)
        assertThat(copied.email.port).isEqualTo(1)
        assertThat(copied.terminal.timeoutSeconds).isEqualTo(120)
        assertThat(copied.terminal.keyVersion).isEqualTo(1)

        val code = seal.newCode()

        fun sealed(json: String) = setup(target).receive(Transfer(sealedSecrets = SealedSecrets(seal.seal(json.toByteArray(), code))))
        assertThat(await { setup(target).unlock(sealed("[1]"), code) }).isNull()
        // Unknown secrets, empty values and a PIN verifier that could not be checked are left out.
        val mixed = sealed("""{"FUTURE":"x","SMTP_PASSWORD":"","PIN_VERIFIER":"v1:x","TERMINAL_PASSPHRASE":"p"}""")
        val filtered = await { setup(target).unlock(mixed, code) }
        assertThat(filtered).containsExactly(Secret.TERMINAL_PASSPHRASE, "p")
        assertThat(await { setup(target).unlock(setup(target).receive(Transfer(settings = "{}")), code) }).isNull()
    }

    @Test
    fun `the setup helper's connection sets only what it holds, and its secrets open with its code`() {
        target.updateSettings {
            it.copy(
                terminal = it.terminal.copy(mode = TerminalMode.SIMULATOR, environment = TerminalEnvironment.TEST, saleId = "Cafe"),
                receipt = it.receipt.copy(footer = "Keep me"),
            )
        }
        val code = seal.newCode()
        val secrets = """{"TERMINAL_PASSPHRASE":"correct horse","ADYEN_API_KEY":"AQE-key"}"""
        val connection =
            """{"destination":"network","host":" 192.168.1.20 ","poiId":"S1F2-000158213605014","keyIdentifier":"store-key",""" +
                """"keyVersion":2,"merchantAccount":"HarbourCoffeeCOM","liveUrlPrefix":"","future":true}"""
        val payload =
            TransferCodec.encode(
                Transfer(sealedSecrets = SealedSecrets(seal.seal(secrets.toByteArray(), code)), connection = connection),
            )
        val received = setup(target).receive(TransferCodec.decode(payload))
        assertThat(received.hasConnection).isTrue()
        assertThat(received.hasSettings).isFalse()
        assertThat(received.catalogue).isNull()

        val outcome = await { setup(target).import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported
        assertThat(outcome.result.connection).isTrue()
        assertThat(outcome.result.settings).isFalse()
        assertThat(outcome.result.secrets).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.ADYEN_API_KEY)
        val copied = await { target.container.settings.current() }
        // Another destination forgets the environment found for the last one.
        assertThat(copied.terminal.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(copied.terminal.environment).isNull()
        assertThat(copied.terminal.host).isEqualTo("192.168.1.20")
        assertThat(copied.terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
        assertThat(copied.terminal.keyIdentifier).isEqualTo("store-key")
        assertThat(copied.terminal.keyVersion).isEqualTo(2)
        assertThat(copied.terminal.merchantAccount).isEqualTo("HarbourCoffeeCOM")
        // What it does not hold stays as it was.
        assertThat(copied.terminal.saleId).isEqualTo("Cafe")
        assertThat(copied.receipt.footer).isEqualTo("Keep me")
        assertThat(await { target.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse")
        assertThat(await { target.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("AQE-key")

        assertThrows(TransferFormatException::class.java) { setup(target).receive(Transfer(connection = "not json")) }
        assertThrows(TransferFormatException::class.java) { setup(target).receive(Transfer(connection = """{"keyVersion":"x"}""")) }
        // A destination from a newer page is ignored.
        assertThat(setup(target).receive(Transfer(connection = """{"destination":"moon"}""")).hasConnection).isTrue()
    }

    @Test
    fun `codes made by the setup helper web page import`() {
        // Made by docs/js/setup.js (Settings: a terminal on the network), so the page and the app keep one format.
        val chunks =
            listOf(
                "MPC1:VDB9:1/2:5T0UMN5%UGQ7:YOUQ1W50200SF9.2WXM1IXK9.16DD%Y8EDMTJPJIVC5VKMUTCRVT6TJHIYOB M281DD7W" +
                    "3844DUK5N-Q116*32*XMS4302OPC3XKH\$3D\$F0HKBX%ISW4X/0S693%6DI1%D9FG6JJD+O7K LLSGWK9. D  MN0WQ 8D9F:" +
                    "%K%QUM5WXCH+D8\$2DC7WU133IO9E73RSGA0-.KFW9LHCK76KW3Z\$9L%N5HIGWIGAMSJ9WY3*/JMPF6VC QEZEDIEC EDO-DW" +
                    "F71/DPWE04ELOD3Q559D QEWE4NE4HA7Z\$5K%6Z\$5 \$5\$363Q5S9E/DDTTCWF7CNAF*83W5646.96V47+96C%6QW6-96IE4 " +
                    "F4C\$CNC91\$CBWER.C5\$CWE4:F4HWEZKEHX5C\$CIE4%F45\$CNPCCECGVEIPC34EG/DWE41F4GEC:JC6%ESN8O.C\$ C-M8 X93" +
                    "Q5/PDCFF5\$CPQE",
                "MPC1:VDB9:2/2:-3EWE4AH6",
            )
        val assembler = QrChunkAssembler()
        chunks.forEach { assembler.add(checkNotNull(QrChunks.parse(it))) }
        val received = setup(target).receive(TransferCodec.decode(assembler.assemble()))
        val outcome = await { setup(target).import(received, ImportMode.MERGE, "Z7FW-2N9A-8XKG") } as ImportOutcome.Imported
        assertThat(outcome.result.secrets).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.ADYEN_API_KEY)
        val terminal = await { target.container.settings.current() }.terminal
        assertThat(terminal.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(terminal.host).isEqualTo("192.168.1.20")
        assertThat(terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
        assertThat(terminal.keyIdentifier).isEqualTo("store-key")
        assertThat(terminal.keyVersion).isEqualTo(2)
        assertThat(terminal.merchantAccount).isEqualTo("HarbourCoffeeCOM")
        assertThat(await { target.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery")
        assertThat(await { target.container.secrets.get(Secret.ADYEN_API_KEY) }).endsWith("-i1i}2s:=Eb,k7Zg%Yjz")
    }

    @Test
    fun `secrets this terminal cannot store are reported`() {
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val export = await { setup(source).export(TransferContents(catalogue = false), "AUD") }
        val received = setup(target).receive(TransferCodec.decode(export.payload))
        target.cipher.failEncrypt = true
        val result = (await { setup(target).import(received, ImportMode.MERGE, export.code!!) } as ImportOutcome.Imported).result
        assertThat(result.settings).isTrue()
        assertThat(result.secrets).isEmpty()
        assertThat(result.secretsError).contains("Keystore")
    }
}

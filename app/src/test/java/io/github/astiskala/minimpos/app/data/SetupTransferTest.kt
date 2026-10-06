package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.PinCheck
import io.github.astiskala.minimpos.app.data.security.PinManager
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.settings.ConnectionSetup
import io.github.astiskala.minimpos.app.data.settings.EmailCapture
import io.github.astiskala.minimpos.app.data.settings.EmailSettings
import io.github.astiskala.minimpos.app.data.settings.MerchantCopyPolicy
import io.github.astiskala.minimpos.app.data.settings.PrinterMode
import io.github.astiskala.minimpos.app.data.settings.ReceiptTipping
import io.github.astiskala.minimpos.app.data.settings.SmtpSecurity
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.app.data.transfer.TransferContents
import io.github.astiskala.minimpos.core.codec.Base45
import io.github.astiskala.minimpos.core.codec.QrChunkAssembler
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.core.codec.TransferFormatException
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Setting up a second terminal from a first one: what travels, what stays, and the sealed secrets. */
@RunWith(RobolectricTestRunner::class)
class SetupTransferTest {
    private val source = TestEnvironment()
    private val target = TestEnvironment()
    private val seal = TransferSeal(iterations = 1_000)
    private val code = seal.newCode()

    private fun receive(transfer: Transfer) =
        setup(target).receive(
            if (transfer.sealedSecrets != null) {
                transfer
            } else {
                transfer.copy(
                    sealedSecrets = SealedSecrets(seal.seal("{}".toByteArray(), code, TransferCodec.authenticationData(transfer))),
                )
            },
        )

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
                        keyIdentifier = "store-key",
                        keyVersion = 3,
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
                        receiptTipping = ReceiptTipping.DEFAULT_ON,
                    ),
                receipt =
                    it.receipt.copy(
                        businessName = "Harbour Coffee Co.",
                        footer = "Ta!",
                        printerMode = PrinterMode.ON,
                        merchantCopy = MerchantCopyPolicy.ALWAYS,
                        charsPerLine = 42,
                        showTaxAmounts = false,
                        showTaxRateTotals = true,
                        markedTaxRateMilliPercent = 5_500,
                        markedTaxRateMarker = "*",
                        markedTaxRateNote = "* Reduced rate",
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
    fun `interrupted commit resumes without replaying catalogue replacement`() {
        configureSource()
        val export = await { setup(source).export(TransferContents(), "EUR") }
        val receiving = setup(target)
        val received = receiving.receive(TransferCodec.decode(export.payload))
        val prepared = checkNotNull(await { receiving.prepare(received, export.code) })
        val failed = await { receiving.commit(prepared, ImportMode.REPLACE) { throw IOException("Interrupted save") } }
        assertThat(failed).isInstanceOf(ImportOutcome.StorageFailed::class.java)
        assertThat(await { receiving.pending.first() }).isTrue()
        val products =
            await {
                target.container.catalog.products
                    .first()
            }
        val restarted = setup(target)
        val resumed = checkNotNull(await { restarted.recover() }) as ImportOutcome.Imported
        assertThat(resumed.result.catalogue?.productsAdded).isEqualTo(1)
        assertThat(
            await {
                target.container.catalog.products
                    .first()
            },
        ).isEqualTo(products)
        assertThat(await { receiving.pending.first() }).isFalse()
        assertThat(await { receiving.recover() }).isNull()
    }

    @Test
    fun `modified public sections fail authentication before any writes`() {
        configureSource()
        val export = await { setup(source).export(TransferContents(), "EUR") }
        val original = TransferCodec.decode(export.payload)
        val catalogue = checkNotNull(original.catalogue)
        val before = await { target.container.settings.current() }
        listOf(
            original.copy(settings = "{}"),
            original.copy(catalogue = catalogue.copy(currencyCode = "USD")),
            original.copy(connection = "{}"),
            original.copy(settings = null),
            original.copy(catalogue = null),
        ).forEach { modified ->
            val received = setup(target).receive(TransferCodec.decode(TransferCodec.encode(modified)))
            assertThat(await { setup(target).import(received, ImportMode.REPLACE, export.code) }).isEqualTo(ImportOutcome.WrongCode)
            assertThat(await { target.container.settings.current() }).isEqualTo(before)
            assertThat(
                await {
                    target.container.secrets.configured
                        .first()
                },
            ).isEmpty()
            assertThat(
                await {
                    target.container.catalog.products
                        .first()
                },
            ).isEmpty()
        }
    }

    @Test
    fun `catalogue-only transfers require the correct code before any write`() {
        val export = await { setup(source).export(TransferContents(settings = false, secrets = false), "AUD") }
        assertThat(export.code).isNotNull()
        val receiving = setup(target)
        val received = receiving.receive(TransferCodec.decode(export.payload))
        assertThat(received.accepts("")).isFalse()
        assertThat(await { receiving.import(received, ImportMode.MERGE) }).isEqualTo(ImportOutcome.WrongCode)
        assertThat(await { receiving.import(received, ImportMode.MERGE, "2222-2222-2222") }).isEqualTo(ImportOutcome.WrongCode)
        assertThat(
            await {
                receiving.import(received, ImportMode.MERGE, export.code)
            },
        ).isInstanceOf(ImportOutcome.Imported::class.java)
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
        val imported = await { receiving.import(received, ImportMode.MERGE, export.code.lowercase().replace("-", " ")) }
        val result = (imported as ImportOutcome.Imported).result
        assertThat(result.catalogue!!.productsAdded).isEqualTo(1)
        assertThat(result.settings).isTrue()
        assertThat(result.secrets).hasSize(4)

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
        assertThat(export.code).isNotEmpty()
        val transfer = TransferCodec.decode(export.payload)
        assertThat(transfer.settings).contains("\"taxIdLabel\":\"ABN\"")
        assertThat(transfer.settings).contains("\"markedTaxRateMilliPercent\":0")
        val outcome = await { setup(target).import(receive(transfer), ImportMode.REPLACE, export.code) }
        assertThat((outcome as ImportOutcome.Imported).result.catalogue).isNull()
        val copied = await { target.container.settings.current() }
        assertThat(copied.payment.referencePrefix).isEmpty()
        assertThat(copied.payment.defaultTaxRateId).isNull()
        assertThat(copied.receipt.footer).isEqualTo("Thank you!")
        assertThat(copied.receipt).isEqualTo(await { source.container.settings.current() }.receipt)
    }

    @Test
    fun `secrets are only included when set, and alone they still make a transfer`() {
        val none = await { setup(source).export(TransferContents(catalogue = true, settings = false), "AUD") }
        assertThat(none.code).isNotEmpty()
        assertThat(none.secrets).isEmpty()
        assertThat(Base45.decode(none.payload)[0].toInt()).isEqualTo(5)
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val secretsOnly = await { setup(source).export(TransferContents(catalogue = false, settings = false), "AUD") }
        assertThat(secretsOnly.secrets).containsExactly(Secret.SMTP_PASSWORD)
        val received = receive(TransferCodec.decode(secretsOnly.payload))
        assertThat(received.catalogue).isNull()
        assertThat(received.hasSettings).isFalse()
        assertThat(received.currencyMatches("AUD")).isTrue()
        assertThat(received.accepts("")).isFalse()
        assertThat(await { setup(target).unlock(received, secretsOnly.code) }).containsExactly(Secret.SMTP_PASSWORD, "pw")
        assertThrows(IllegalArgumentException::class.java) {
            await { setup(source).export(TransferContents(catalogue = false, settings = false, secrets = false), "AUD") }
        }
    }

    @Test
    fun `unreadable settings and secrets are rejected`() {
        assertThrows(TransferFormatException::class.java) { receive(Transfer(settings = "not json")) }
        assertThrows(TransferFormatException::class.java) { receive(Transfer(settings = """{"receipt":"x"}""")) }
        // Values out of range are brought into range.
        val odd =
            receive(
                Transfer(
                    settings =
                        """{"receipt":{"charsPerLine":500},"email":{"port":0},""" +
                            """"terminal":{"keyVersion":0},"future":1}""",
                ),
            )
        await { setup(target).import(odd, ImportMode.MERGE, code) }
        val copied = await { target.container.settings.current() }
        assertThat(copied.receipt.charsPerLine).isEqualTo(64)
        assertThat(copied.email.port).isEqualTo(1)
        assertThat(copied.terminal.keyVersion).isEqualTo(1)

        fun sealed(json: String) = receive(Transfer(sealedSecrets = SealedSecrets(seal.seal(json.toByteArray(), code))))
        assertThat(await { setup(target).unlock(sealed("[1]"), code) }).isNull()
        // Unknown secrets, empty values and a PIN verifier that could not be checked are left out.
        val mixed = sealed("""{"FUTURE":"x","SMTP_PASSWORD":"","PIN_VERIFIER":"v1:x","TERMINAL_PASSPHRASE":"p"}""")
        val filtered = await { setup(target).unlock(mixed, code) }
        assertThat(filtered).containsExactly(Secret.TERMINAL_PASSPHRASE, "p")
        assertThat(await { setup(target).unlock(receive(Transfer(settings = "{}")), code) }).isEmpty()
    }

    @Test
    fun `the setup helper's connection sets only what it holds, and its secrets open with its code`() {
        target.updateSettings {
            it.copy(
                terminal = it.terminal.copy(mode = TerminalMode.SIMULATOR, environment = TerminalEnvironment.TEST),
                receipt = it.receipt.copy(footer = "Keep me"),
            )
        }
        val secrets = """{"TERMINAL_PASSPHRASE":"correct horse","ADYEN_API_KEY":"AQE-key"}"""
        val connection =
            """{"destination":"network","environment":"LIVE","host":" 192.168.1.20 ","poiId":"S1F2-000158213605014",""" +
                """"keyIdentifier":"store-key",""" +
                """"keyVersion":2,"merchantAccount":"HarbourCoffeeCOM","liveUrlPrefix":"","future":true}"""
        val payload =
            TransferCodec.encode(
                Transfer(
                    sealedSecrets =
                        SealedSecrets(
                            seal.seal(secrets.toByteArray(), code, TransferCodec.authenticationData(Transfer(connection = connection))),
                        ),
                    connection = connection,
                ),
            )
        val received = receive(TransferCodec.decode(payload))
        assertThat(received.hasConnection).isTrue()
        assertThat(received.hasSettings).isFalse()
        assertThat(received.catalogue).isNull()

        val outcome = await { setup(target).import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported
        assertThat(outcome.result.connection).isTrue()
        assertThat(outcome.result.settings).isFalse()
        assertThat(outcome.result.secrets).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.ADYEN_API_KEY)
        val copied = await { target.container.settings.current() }
        // The helper's explicit environment applies to the new network destination.
        assertThat(copied.terminal.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(copied.terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(copied.terminal.host).isEqualTo("192.168.1.20")
        assertThat(copied.terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
        assertThat(copied.terminal.keyIdentifier).isEqualTo("store-key")
        assertThat(copied.terminal.keyVersion).isEqualTo(2)
        assertThat(copied.terminal.merchantAccount).isEqualTo("HarbourCoffeeCOM")
        // What it does not hold stays as it was.
        assertThat(copied.receipt.footer).isEqualTo("Keep me")
        assertThat(await { target.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse")
        assertThat(await { target.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("AQE-key")

        assertThrows(TransferFormatException::class.java) { receive(Transfer(connection = "not json")) }
        assertThrows(TransferFormatException::class.java) { receive(Transfer(connection = """{"keyVersion":"x"}""")) }
        // A destination from a newer page is ignored.
        assertThat(receive(Transfer(connection = """{"destination":"moon"}""")).hasConnection).isTrue()
    }

    @Test
    fun `helper SMTP fields preserve omitted values and unrelated settings`() {
        val saved =
            EmailSettings(
                host = "old.example.com",
                port = 465,
                security = SmtpSecurity.SSL,
                username = "saved-user",
                fromAddress = "old@example.com",
                fromName = "Saved name",
                bcc = "archive@example.com",
                subject = "Saved subject",
            )
        assertThat(ConnectionSetup().emailAppliedTo(saved)).isEqualTo(saved)
        assertThat(
            ConnectionSetup(smtpHost = " ", smtpUsername = "", smtpFromAddress = "", smtpFromName = " ").emailAppliedTo(saved),
        ).isEqualTo(saved)
        target.updateSettings { it.copy(email = saved, receipt = it.receipt.copy(footer = "Keep me")) }
        await { target.container.secrets.set(Secret.SMTP_PASSWORD, "saved-password") }
        val received =
            receive(
                Transfer(
                    connection =
                        """{"smtpHost":" smtp.example.com ","smtpPort":587,"smtpSecurity":"STARTTLS",""" +
                            """"smtpFromAddress":" shop@example.com ","smtpFromName":" Shop "}""",
                ),
            )
        val result = await { setup(target).import(received, ImportMode.MERGE, code) } as ImportOutcome.Imported
        assertThat(result.result.connection).isTrue()
        assertThat(result.result.secrets).isEmpty()
        val settings = await { target.container.settings.current() }
        assertThat(settings.email)
            .isEqualTo(
                saved.copy(
                    host = "smtp.example.com",
                    port = 587,
                    security = SmtpSecurity.STARTTLS,
                    fromAddress = "shop@example.com",
                    fromName = "Shop",
                ),
            )
        assertThat(settings.receipt.footer).isEqualTo("Keep me")
        assertThat(settings.terminal).isEqualTo(await { source.container.settings.current() }.terminal)
        assertThat(await { target.container.secrets.get(Secret.SMTP_PASSWORD) }).isEqualTo("saved-password")
        assertThat(ConnectionSetup(smtpUsername = " new-user ").emailAppliedTo(saved).username).isEqualTo("new-user")
        val invalidPort = receive(Transfer(connection = """{"smtpPort":0}"""))
        await { setup(target).import(invalidPort, ImportMode.MERGE, code) }
        assertThat(await { target.container.settings.current() }.email.port).isEqualTo(1)
    }

    @Test
    fun `codes made by the setup helper web page import`() {
        // Made by docs/js/setup.js (Settings: a terminal on the network), so the page and the app keep one format.
        val chunks =
            listOf(
                "MPC1:0000:1/2:FW0T08K:9VY1TQU3R1X50200SF9000000000000000000000000000000000000000000L-S939+\$5+MP-%14" +
                    "+3UX88BV3CN7\$SXG92L8U0H\$XR:MRQ*JXRM6LS1.EEW5IKGZQDSLC8\$UAWV5CF1H2*F8L66P+MYP92QS\$\$ECQ3PLS:IN" +
                    "827I7AN/62W1ZFV9C6F2QY/OSG22UAT9MZ10DWALC8E-0SM4MDS60E.Q4087F.U**FY2VDERE2H8XFCH7I9WOAMX/DANI8XTZ" +
                    "FO:2SMPF6VC QEZEDIEC EDO-DWF71/DPWE04ELOD3Q51\$CS/E0LE9/D1\$CUUEWF7ITA2OAIE4XF414EUUEWF71A6LF6/96" +
                    "R47Z96NF6IE4-F4 3ENC9WE4CF4EA6KF6646746YW6OF6FL6B46746QQ63Q5/PD:EF6VCG/DREDQEDDJEWF7 QE04EQZC/PD5EF3Q" +
                    "5F\$DXKE 8D",
                "MPC1:0000:2/2:G/D:B8UPC2%EUUEWF7Y69WKE34E1KEX3EN.C3 C61AIE4:F4U\$DY8E14EUUEWF7TQEIWE.%5\$9FQ\$DTVD+%5" +
                    "+3EIE4:F4U\$D09EZ C6%E-ED5EFWF71OA5S93Q5TQEIWE5 A5\$C..DF\$DWE4:F459DQ8EB\$CBECP9ERZCUPC%ZD3Q5TQEIWE" +
                    "Y+8+3E0C8JVC6\$C:OEWF7ZKEKPC\$EDLWEF68\$9FQ\$DTVD+%5+3EIE4:F4U\$DW8E0LE\$ DBECFZCWF79Z8BECP9EDZCOQE/3" +
                    "EIE4 F4C\$CM-A4LE EDO-D3G73Q5TQEIWEQ7A5LEWE41R6DY6",
            )
        val assembler = QrChunkAssembler()
        chunks.forEach { assembler.add(checkNotNull(QrChunks.parse(it))) }
        val received = receive(TransferCodec.decode(assembler.assemble()))
        val outcome = await { setup(target).import(received, ImportMode.MERGE, "2222-2222-2222") } as ImportOutcome.Imported
        assertThat(outcome.result.secrets).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.ADYEN_API_KEY, Secret.SMTP_PASSWORD)
        val settings = await { target.container.settings.current() }
        val terminal = settings.terminal
        assertThat(terminal.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(terminal.host).isEqualTo("192.168.1.20")
        assertThat(terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
        assertThat(terminal.keyIdentifier).isEqualTo("store-key")
        assertThat(terminal.keyVersion).isEqualTo(2)
        assertThat(terminal.merchantAccount).isEqualTo("HarbourCoffeeCOM")
        assertThat(await { target.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery")
        assertThat(await { target.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("demo-checkout-key")
        assertThat(settings.email)
            .isEqualTo(
                EmailSettings(
                    host = "smtp.example.com",
                    port = 465,
                    security = SmtpSecurity.SSL,
                    username = "shop@example.com",
                    fromAddress = "receipts@example.com",
                    fromName = "Example shop",
                ),
            )
        assertThat(await { target.container.secrets.get(Secret.SMTP_PASSWORD) }).isEqualTo(" demo-smtp-password ")
    }

    @Test
    fun `secrets this terminal cannot store are reported`() {
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val export = await { setup(source).export(TransferContents(catalogue = false), "AUD") }
        val received = receive(TransferCodec.decode(export.payload))
        target.cipher.failEncrypt = true
        val before = await { target.container.settings.current() }
        val result = await { setup(target).import(received, ImportMode.MERGE, export.code) } as ImportOutcome.StorageFailed
        assertThat(result.reason).contains("Keystore")
        assertThat(result.pending).isFalse()
        assertThat(await { target.container.settings.current() }).isEqualTo(before)
        assertThat(
            await {
                target.container.secrets.configured
                    .first()
            },
        ).isEmpty()
    }
}

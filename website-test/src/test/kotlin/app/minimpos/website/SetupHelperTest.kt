package app.minimpos.website

import app.minimpos.core.codec.QrChunkAssembler
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.TransferCodec
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Exercises the published helper in a real browser; all page resources are local and outbound DNS is blocked. */
class SetupHelperTest {
    private val script: HelperBrowser get() = browser
    private val docs = Path.of(System.getProperty("minimpos.website"))

    @Before
    fun resetPage() = openPage()

    @Test
    fun `masked secrets cannot be copied out of their fields`() {
        assertThat(script.executeScript("return document.getElementById('apiKey').type")).isEqualTo("text")
        val copy = "return document.getElementById('apiKey').dispatchEvent(new ClipboardEvent('copy', {cancelable: true}))"
        assertThat(script.executeScript(copy)).isEqualTo(false)
    }

    @Test
    fun `a link to one destination opens the helper with it chosen`() {
        script.executeScript("location.hash = 'destination-cloud'; location.reload();")
        waitFor("return document.getElementById('destination-cloud').checked === true")
    }

    @Test
    fun `Make QR codes needs the required fields and fires no form submit`() {
        field("setupMode", "automatic")
        field("apiKey", "")
        script.executeScript("window.__submits = 0; document.addEventListener('submit', () => window.__submits++, true);")
        script.executeScript("document.getElementById('setup-make').click();")
        assertThat(script.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
        field("apiKey", "demo-key")
        script.executeScript("document.getElementById('setup-make').click();")
        ready()
        assertThat(script.executeScript("return window.__submits")).isEqualTo(0L)
    }

    @Test
    fun `every language loads offline and generates genuine SVG QR codes with WebCrypto`() {
        for (language in listOf("", "zh-CN/", "zh-Hant/", "ja/", "pt-BR/", "es-MX/", "fr-FR/")) {
            openPage(language)
            assertThat(script.executeScript("return Boolean(crypto.subtle)")).isEqualTo(true)
            submit()
            ready()
            val svgLength =
                requireNotNull(script.executeScript("return document.querySelector('#setup-qr svg path').getAttribute('d').length")) as Long
            assertThat(svgLength).isGreaterThan(0)
            val (connection, secrets) = readTransfer()
            assertThat(script.executeScript("return document.activeElement.id")).isEqualTo("setup-codes-title")
            assertThat(text("setup-summary-environment")).contains("TEST")
            assertThat(text("setup-summary-destination"))
                .isEqualTo(
                    script.executeScript(
                        "return document.querySelector('input[name=destination]:checked').closest('label')" +
                            ".textContent.trim().replace(/\\s+/g, ' ')",
                    ),
                )
            assertThat(script.executeScript("return document.getElementById('setup-summary-receipt').textContent.length > 0"))
                .isEqualTo(true)
            assertThat(script.executeScript("return document.getElementById('receipt-options').open")).isEqualTo(false)
            assertThat(connection).isEqualTo(receiptUnchanged("""{"destination":"network","environment":"TEST"}"""))
            assertThat(secrets).isEqualTo(json("""{"ADYEN_API_KEY":"demo-key"}"""))
        }
    }

    @Test
    fun `current deterministic helper vector authenticates its connection metadata`() {
        script.executeScript("crypto.getRandomValues = array => { array.fill(0); return array; };")
        mapOf(
            "keyIdentifier" to "store-key",
            "keyVersion" to "2",
            "passphrase" to "correct horse battery",
            "merchantAccount" to "HarbourCoffeeCOM",
            "host" to "192.168.1.20",
            "poiId" to "S1F2-000158213605014",
            "apiKey" to "demo-checkout-key",
            "includeSmtp" to "yes",
            "smtpHost" to "smtp.example.com",
            "smtpPort" to "465",
            "smtpSecurity" to "SSL",
            "smtpUsername" to "shop@example.com",
            "smtpPassword" to " demo-smtp-password ",
            "smtpFromAddress" to "receipts@example.com",
            "smtpFromName" to "Example shop",
            "includeReceipt" to "yes",
            "receiptNameSource" to "manual",
            "receiptBusinessName" to "Vector shop",
            "receiptAddressSource" to "manual",
            "receiptAddressLines" to "1 Main St\nSydney",
            "receiptPhoneSource" to "manual",
            "receiptPhone" to "+123",
            "receiptTaxId" to "TAX123",
            "receiptTitle" to "Sale receipt",
            "receiptFooter" to "Thanks",
        ).forEach(::field)
        submit()
        ready()
        readTransfer()
        assertThat(codes().distinct()).containsExactlyElementsIn(HELPER_VECTOR).inOrder()
    }

    @Test
    fun `editing a generated transfer clears its visible summary and never includes credentials`() {
        submit()
        ready()
        assertThat(text("setup-summary-destination")).isNotEmpty()
        assertThat(requireNotNull(script.executeScript("return document.querySelector('.setup-summary').textContent")) as String)
            .doesNotContain("demo-key")
        field("apiKey", "replacement-demo-key")
        assertThat(text("setup-summary-destination")).isEmpty()
        assertThat(text("setup-summary-environment")).isEmpty()
        assertThat(script.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
    }

    @Test
    fun `input change and reset discard in-flight codes and their transfer code`() {
        for (event in listOf("input", "change", "reset")) {
            openPage()
            deferCrypto()
            submit()
            pending(1)
            script.executeScript("document.getElementById('setup-form').dispatchEvent(new Event(arguments[0]));", event)
            finish(0)
            assertThat(script.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
            assertThat(text("setup-code")).isEmpty()
            assertThat(text("setup-status")).isEmpty()
            assertThat(text("setup-summary-destination")).isEmpty()
            assertThat(codes()).isEmpty()
        }
    }

    @Test
    fun `later submission wins even when first completes last`() {
        deferCrypto()
        submit()
        pending(1)
        field("apiKey", "another-demo-key")
        submit()
        pending(2)
        finish(1)
        ready()
        val latest = codes()
        val code = text("setup-code")
        finish(0)
        assertThat(codes()).isEqualTo(latest)
        assertThat(text("setup-code")).isEqualTo(code)
        assertThat(readTransfer().second).isEqualTo(json("""{"ADYEN_API_KEY":"another-demo-key"}"""))
    }

    @Test
    fun `superseded crypto failure cannot overwrite newer success`() {
        deferCrypto()
        submit()
        pending(1)
        submit()
        pending(2)
        finish(1)
        ready()
        val status = text("setup-status")
        finish(0, fail = true)
        assertThat(text("setup-status")).isEqualTo(status)
        assertThat(script.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(false)
    }

    @Test
    fun `current crypto failure shows unsupported without codes`() {
        deferCrypto()
        submit()
        pending(1)
        finish(0, fail = true)
        assertThat(text("setup-status"))
            .isEqualTo(script.executeScript("return document.getElementById('setup-form').dataset.msgUnsupported"))
        assertThat(script.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
        assertThat(text("setup-code")).isEmpty()
    }

    @Test
    fun `receipt fields support independent Adyen import and manual text in every language`() {
        for (language in listOf("", "zh-CN/", "zh-Hant/", "ja/", "pt-BR/", "es-MX/", "fr-FR/")) {
            openPage(language)
            field("includeReceipt", "yes")
            assertReceiptControls()
            field("receiptNameSource", "manual")
            field("receiptBusinessName", " My shop ")
            field("receiptAddressSource", "adyen")
            field("receiptPhoneSource", "manual")
            field("receiptPhone", " +61212345678 ")
            field("receiptTaxId", " TAX123 ")
            field("receiptTitle", " Receipt ")
            field("receiptFooter", " Thanks ")
            submit()
            ready()
            assertThat(readTransfer().first).isEqualTo(
                json(
                    """{"destination":"network","environment":"TEST","receiptBusinessName":"My shop",
                    |"receiptPhone":"+61212345678","receiptTaxId":"TAX123","receiptTitle":"Receipt","receiptFooter":"Thanks",
                    |"importReceiptName":false,"importReceiptPhone":false}
                    """.trimMargin(),
                ),
            )
            field("receiptNameSource", "adyen")
            field("receiptPhoneSource", "adyen")
            script.executeScript("window.__codes = [];")
            submit()
            ready()
            val connection = readTransfer().first
            assertThat(connection).doesNotContainKey("receiptBusinessName")
            assertThat(connection).doesNotContainKey("receiptPhone")
            assertThat(connection).doesNotContainKey("importReceiptName")
        }
    }

    @Test
    fun `receipt opt-out hides fields and excludes manual text and Adyen lookup in every language`() {
        for (language in listOf("", "zh-CN/", "zh-Hant/", "ja/", "pt-BR/", "es-MX/", "fr-FR/")) {
            openPage(language)
            assertReceiptDisabled(true)
            field("includeReceipt", "yes")
            assertReceiptDisabled(false)
            field("receiptNameSource", "manual")
            field("receiptBusinessName", "Do not import")
            field("receiptTaxId", "Do not import")
            field("includeReceipt", "no")
            assertReceiptDisabled(true)
            submit()
            ready()
            assertThat(readTransfer().first).isEqualTo(
                json(
                    """{"destination":"network","environment":"TEST",
                    |"importReceiptName":false,"importReceiptAddress":false,"importReceiptPhone":false}
                    """.trimMargin(),
                ),
            )
        }
    }

    @Test
    fun `SMTP opt-out excludes previously typed values`() {
        assertSmtpDisabled(true)
        field("includeSmtp", "yes")
        assertSmtpDisabled(false)
        field("smtpHost", "smtp.example.com")
        field("smtpPassword", "demo-smtp-password")
        field("includeSmtp", "no")
        assertSmtpDisabled(true)
        submit()
        ready()
        assertThat(readTransfer())
            .isEqualTo(receiptUnchanged("""{"destination":"network","environment":"TEST"}""") to json("""{"ADYEN_API_KEY":"demo-key"}"""))
    }

    @Test
    fun `SMTP travels with every destination and mode while secrets retain whitespace`() {
        for (destination in listOf("thisTerminal", "network", "cloud", "tapToPay")) {
            for (mode in listOf("automatic", "manual")) {
                openPage()
                field("setupMode", mode)
                field("destination", destination)
                field("includeSmtp", "yes")
                mapOf(
                    "smtpHost" to " smtp.example.com ",
                    "smtpPort" to "465",
                    "smtpSecurity" to "SSL",
                    "smtpUsername" to " shop@example.com ",
                    "smtpPassword" to " demo-smtp-password ",
                    "smtpFromAddress" to " receipts@example.com ",
                    "smtpFromName" to " Example shop ",
                ).forEach(::field)
                submit()
                ready()
                val automatic = if (mode == "automatic") ""","automatic":true""" else ""
                val expected =
                    """{"destination":"$destination"$automatic,"environment":"TEST","smtpHost":"smtp.example.com","smtpPort":465,
                    |"smtpSecurity":"SSL","smtpUsername":"shop@example.com","smtpFromAddress":"receipts@example.com",
                    |"smtpFromName":"Example shop"}
                    """.trimMargin()
                assertThat(readTransfer())
                    .isEqualTo(
                        receiptUnchanged(expected) to json("""{"ADYEN_API_KEY":"demo-key","SMTP_PASSWORD":" demo-smtp-password "}"""),
                    )
            }
        }
    }

    @Test
    fun `blank optional SMTP fields do not clear saved values`() {
        field("includeSmtp", "yes")
        for (name in listOf("smtpUsername", "smtpPassword", "smtpFromName")) field(name, " ")
        submit()
        ready()
        assertThat(readTransfer())
            .isEqualTo(
                receiptUnchanged("""{"destination":"network","environment":"TEST","smtpSecurity":"STARTTLS","smtpPort":587}""") to
                    json("""{"ADYEN_API_KEY":"demo-key"}"""),
            )
    }

    @Test
    fun `Tap to Pay transfers primary credential and shared key without a boarding key`() {
        run {
            openPage()
            field("setupMode", "manual")
            field("destination", "tapToPay")
            assertThat(script.executeScript("return document.getElementById('paymentsAppApiKey')")).isNull()
            assertThat(script.executeScript("return document.querySelector('[data-mode]').disabled")).isEqualTo(false)
            assertThat(script.executeScript("return document.querySelector('[name=setupMode]').closest('fieldset').hidden"))
                .isEqualTo(false)
            mapOf(
                "merchantAccount" to " Merchant ",
                "storeId" to " ST1 ",
                "keyIdentifier" to "manual-key",
                "keyVersion" to "2",
                "passphrase" to " manual secret ",
            ).forEach(::field)
            submit()
            ready()
            assertThat(script.executeScript("return document.getElementById('setup-summary-mode').parentElement.hidden"))
                .isEqualTo(false)
            assertThat(readTransfer())
                .isEqualTo(
                    receiptUnchanged(
                        """{"destination":"tapToPay","environment":"TEST","merchantAccount":"Merchant","storeId":"ST1",
                        |"keyIdentifier":"manual-key","keyVersion":2}
                        """.trimMargin(),
                    ) to
                        json(
                            """{"ADYEN_API_KEY":"demo-key",
                            |"TERMINAL_PASSPHRASE":" manual secret "}
                            """.trimMargin(),
                        ),
                )
        }
    }

    @Test
    fun `every destination transfers explicit LIVE choice in every helper language`() {
        for (language in listOf("", "zh-CN/", "zh-Hant/", "ja/", "pt-BR/", "es-MX/", "fr-FR/")) {
            for (destination in listOf("thisTerminal", "network", "cloud", "tapToPay")) {
                openPage(language)
                field("destination", destination)
                field("environment", "live")
                field("setupMode", "automatic")
                submit()
                ready()
                val automatic = ""","automatic":true"""
                assertThat(readTransfer())
                    .isEqualTo(
                        receiptUnchanged("""{"destination":"$destination"$automatic,"environment":"LIVE"}""") to
                            json("""{"ADYEN_API_KEY":"demo-key"}"""),
                    )
            }
        }
    }

    @Test
    fun `Manual asks for the merchant account only where no terminal can supply it`() {
        val required = "return document.getElementById('merchantAccount').required"
        for (destination in listOf("thisTerminal", "network", "cloud")) {
            field("destination", destination)
            assertWithMessage(destination).that(script.executeScript(required)).isEqualTo(false)
        }
        field("destination", "tapToPay")
        assertThat(script.executeScript(required)).isEqualTo(true)
    }

    @Test
    fun `Automatic carries a shared key only when the operator chooses to enter it`() {
        field("destination", "thisTerminal")
        field("setupMode", "automatic")
        val entered = "return document.getElementById('keyIdentifier').closest('fieldset').hidden"
        assertThat(script.executeScript(entered)).isEqualTo(true)
        field("keySource", "enter")
        assertThat(script.executeScript(entered)).isEqualTo(false)
        field("keyIdentifier", "shared")
        field("passphrase", "demo-passphrase")
        field("keyVersion", "3")
        submit()
        ready()
        assertThat(readTransfer())
            .isEqualTo(
                receiptUnchanged(
                    """{"destination":"thisTerminal","automatic":true,"environment":"TEST","keyIdentifier":"shared","keyVersion":3}""",
                ) to json("""{"TERMINAL_PASSPHRASE":"demo-passphrase","ADYEN_API_KEY":"demo-key"}"""),
            )
    }

    @Test
    fun `Tap to Pay permits device key entry but physical Manual setup still requires key`() {
        field("destination", "tapToPay")
        field("keyVersion", "1")
        submit()
        ready()
        assertThat(readTransfer())
            .isEqualTo(receiptUnchanged("""{"destination":"tapToPay","environment":"TEST"}""") to json("""{"ADYEN_API_KEY":"demo-key"}"""))
        assertRequired(true)
        field("destination", "network")
        assertRequired(true)
    }

    @Test
    fun `guide links select destinations on load and after navigation in every language`() {
        for (language in listOf("", "es-MX/", "fr-FR/", "ja/", "pt-BR/", "zh-CN/", "zh-Hant/")) {
            browser.open(docs.resolve("${language}setup.html"), "#destination-cloud")
            assertThat(script.executeScript("return document.getElementById('setup-form').elements.destination.value")).isEqualTo("cloud")
            assertThat(script.executeScript("return document.getElementById('setup-device-guide').getAttribute('href')"))
                .isEqualTo("quick-start-cloud#connect")
            script.executeScript("location.hash = 'destination-tapToPay'")
            browser.await {
                script.executeScript("return document.getElementById('setup-form').elements.destination.value === 'tapToPay'") == true
            }
            assertThat(script.executeScript("return document.getElementById('setup-device-guide').getAttribute('href')"))
                .isEqualTo("quick-start-tap-to-pay#connect")
            script.executeScript("location.hash = 'apiKey'")
            assertThat(script.executeScript("return document.getElementById('setup-form').elements.environment.value")).isEqualTo("test")
        }
    }

    @Test
    fun `default setup hides optional choices and exposes a successful import handoff`() {
        browser.open(docs.resolve("setup.html"))
        assertThat(script.executeScript("return document.getElementById('setup-method').open")).isEqualTo(false)
        assertThat(script.executeScript("return document.getElementById('setup-tools').open")).isEqualTo(false)
        assertThat(script.executeScript("return document.getElementById('setup-form').elements.setupMode.value")).isEqualTo("automatic")
        field("environment", "live")
        assertThat(script.executeScript("return document.getElementById('liveUrlPrefix').checkVisibility()"))
            .isEqualTo(true)
        field("setupMode", "manual")
        assertThat(script.executeScript("return document.getElementById('setup-method').open")).isEqualTo(true)
        assertThat(script.executeScript("return document.querySelector('#setup-after-import a').getAttribute('href')")).isEqualTo("testing")
        script.executeScript("location.hash = 'make-logo'")
        browser.await { script.executeScript("return document.getElementById('setup-form').elements.make.value === 'logo'") == true }
        assertThat(script.executeScript("return document.getElementById('setup-after-import').hidden")).isEqualTo(true)
        assertThat(script.executeScript("return document.getElementById('setup-tools').open")).isEqualTo(true)
    }

    private fun openPage(language: String = "") {
        browser.open(docs.resolve("${language}setup.html"))
        script.executeScript(
            """
            window.__codes = [];
            const original = qrcodegen.QrCode.encodeText;
            qrcodegen.QrCode.encodeText = function(text, level) {
                window.__codes.push(text);
                return original.call(this, text, level);
            };
            """.trimIndent(),
        )
        field("destination", "network")
        field("environment", "test")
        field("setupMode", "manual")
        field("apiKey", "demo-key")
    }

    private fun field(
        name: String,
        value: String,
    ) {
        script.executeScript(
            """
            const form = document.getElementById('setup-form');
            const [name, value] = arguments;
            const controls = Array.from(form.elements).filter(control => control.name === name);
            if (controls.some(control => control.type === 'radio')) form.elements[name].value = value;
            else controls.filter(control => !control.disabled).forEach(control => { control.value = value; });
            form.dispatchEvent(new Event('change'));
            """.trimIndent(),
            name,
            value,
        )
    }

    /** Makes the codes without the browser's required-field check, so a test fills in only the fields it is about. */
    private fun submit() {
        script.executeScript("document.getElementById('setup-form').reportValidity = () => true;")
        script.executeScript("document.getElementById('setup-make').click();")
    }

    private fun ready() {
        waitFor("return !document.getElementById('setup-codes').hidden")
    }

    private fun waitFor(expression: String) {
        browser.await { script.executeScript(expression) == true }
    }

    private fun text(id: String) =
        requireNotNull(script.executeScript("return document.getElementById(arguments[0]).textContent", id)) as String

    private fun codes() = requireNotNull(script.executeScript("return window.__codes")) as List<*>

    private fun deferCrypto() {
        script.executeScript(
            """
            window.__pending = [];
            window.__jobs = [];
            const original = crypto.subtle.encrypt.bind(crypto.subtle);
            crypto.subtle.encrypt = function(...args) {
                const job = new Promise((resolve, reject) => {
                    window.__pending.push({resolve: () => original(...args).then(resolve, reject), reject});
                });
                window.__jobs.push(job.catch(() => {}));
                return job;
            };
            """.trimIndent(),
        )
    }

    private fun pending(count: Int) = waitFor("return window.__pending.length === $count")

    private fun finish(
        index: Int,
        fail: Boolean = false,
    ) {
        script.executeAsyncScript(
            """
            const [index, fail, done] = arguments;
            const pending = window.__pending[index];
            if (fail) pending.reject(new Error('fixture crypto failure'));
            else pending.resolve();
            window.__jobs[index].then(() => setTimeout(done, 0));
            """.trimIndent(),
            index,
            fail,
        )
    }

    private fun assertReceiptDisabled(disabled: Boolean) = assertGroupDisabled("#receipt-fields", disabled)

    private fun assertReceiptControls() {
        assertThat(
            script.executeScript(
                """
                const sources = Array.from(document.querySelectorAll('select[id^=receipt]'));
                const groups = Array.from(document.querySelectorAll('[data-receipt]'));
                return sources.length === 3 && groups.length === 3 &&
                  sources.every(source => Array.from(source.options).map(option => option.value).join(',') === 'adyen,manual') &&
                  groups.every(group => group.hidden && group.querySelector('input, textarea').disabled);
                """.trimIndent(),
            ),
        ).isEqualTo(true)
    }

    private fun assertSmtpDisabled(disabled: Boolean) = assertGroupDisabled("[data-email]", disabled)

    private fun assertGroupDisabled(
        selector: String,
        disabled: Boolean,
    ) {
        assertThat(script.executeScript("return document.querySelector(arguments[0]).disabled", selector)).isEqualTo(disabled)
        assertThat(script.executeScript("return document.querySelector(arguments[0]).hidden", selector)).isEqualTo(disabled)
    }

    private fun assertRequired(required: Boolean) {
        assertThat(
            script.executeScript(
                "return Array.from(document.querySelectorAll('[data-required-for]')).every(input => input.required === arguments[0])",
                required,
            ),
        ).isEqualTo(true)
    }

    private fun readTransfer(): Pair<JsonObject, JsonObject> {
        val first = requireNotNull(codes().first()) as String
        val total = requireNotNull(QrChunks.parse(first)).total
        repeat(total - 1) { script.executeScript("document.getElementById('setup-next').click()") }
        val assembled = QrChunkAssembler()
        for (code in codes().take(total)) assembled.add(requireNotNull(QrChunks.parse(requireNotNull(code) as String)))
        val transfer = TransferCodec.decode(assembled.assemble())
        val sealed = requireNotNull(transfer.sealedSecrets).toByteArray()
        assertThat(String(sealed, Charsets.ISO_8859_1)).doesNotContain("demo-smtp-password")
        val buffer = ByteBuffer.wrap(sealed)
        assertThat(buffer.get().toInt()).isEqualTo(2)
        val rounds = buffer.int
        assertThat(rounds).isEqualTo(150_000)
        val salt = ByteArray(16).also(buffer::get)
        val iv = ByteArray(12).also(buffer::get)
        val code = text("setup-code")
        assertThat(code).matches("[A-HJ-NP-Z]{4}-[A-HJ-NP-Z]{4}-[A-HJ-NP-Z]{4}")
        val spec = PBEKeySpec(code.replace("-", "").toCharArray(), salt, rounds, 256)
        val key =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(TransferCodec.authenticationData(transfer))
        val secrets = cipher.doFinal(sealed, buffer.position(), sealed.size - buffer.position())
        return json(requireNotNull(transfer.connection)) to json(String(secrets, Charsets.UTF_8))
    }

    private fun receiptUnchanged(text: String) =
        JsonObject(
            json(text) + json("""{"importReceiptName":false,"importReceiptAddress":false,"importReceiptPhone":false}"""),
        )

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    companion object {
        private val HELPER_VECTOR =
            listOf(
                "MPC1:0000:1/3:Q 02F6I0N7I28/T3R1X50200SF9000000000000000000000000000000000000000000CO8A%8:C4WI3C:FFY" +
                    "SPS2 %A6TNV0N1PV2Z02K2*HJ:DMQZQPBG.BFL8P+HNWP54OOUI0MEWI6R0CTODAM:OR\$G%I5+P9ZG7-E64LF9OVBG3-NTMAH8*D" +
                    "4QSL/FC.6HY3+8BCV4RI2FY26U3NDV5CFGIOGFHYVKF09FPNG11*P0\$0CSW9APS+:4/M02-4V IW6H6CUX FDJ1+KDLVSMPF6VC " +
                    "QEZEDIEC EDO-DWF71/DPWE04ELOD3Q51\$CS/E0LE9/D1\$CUUEWF7ITA2OAIE4XF414EUUEWF71A6LF6/96R47Z96NF6IE4-F4 3" +
                    "ENC9WE4CF4EA6KF6646746YW6OF6FL6B46746QQ63Q5/PD:EF6VCG/DREDQEDDJEWF7 QE04EQZC/PD5EF3Q5F\$DXKE 8D",
                "MPC1:0000:2/3:G/D:B8UPC2%EUUEWF7Y69WKE34E1KEX3EN.C3 C61AIE4:F4U\$DY8E14EUUEWF7TQEIWE.%5\$9FQ\$DTVD+%5+3" +
                    "EIE4:F4U\$D09EZ C6%E-ED5EFWF71OA5S93Q5TQEIWE5 A5\$C..DF\$DWE4:F459DQ8EB\$CBECP9ERZCUPC%ZD3Q5TQEIWEY+8+3E" +
                    "0C8JVC6\$C:OEWF7ZKEKPC\$EDLWEF68\$9FQ\$DTVD+%5+3EIE4:F4U\$DW8E0LE\$ DBECFZCWF79Z8BECP9EDZCOQE/3EIE4/F4Z C-" +
                    " CX9E3I8PQE1/DZQE++9F\$DWE4FF4Z CHWEBJEOQE/3EIE4/F4Z C- CX9E0C8JVC6\$C-PEZED6\$CWE4NE4Y347ECM-DYOA\$UB*O" +
                    "AFVCC\$CIE4/F4Z C- CX9EJ7A-3EFZCWF7WK5QF6IE4/F4Z C- CX9E1UAD9FTTCWF7ETAW6BQF6IE4/F4Z C- CX9E9UA",
                "MPC1:0000:3/3:EWEFZCWF7FOATVDQ44Z C- CX9EIE4/F4Z C- CX9EV+824E5\$CWE4DF4 8D7/D:OE3Q5YEDS9E5LEXIAKPC\$E" +
                    "DTVEBECFZCAH7AECLQE3Q5YEDS9E5LEXIAKPC\$EDGVE5VCZKEZQEWE4J.C/VDPZCYF4Q\$D04EXVEZ C- CX9EJ7A-3EFZCAH7AEC" +
                    "LQE3Q5/PDCFF5\$CPQE-3EWE4JF6:F4U\$D.8E04EUUE5G7H%6Z2",
            )
        private lateinit var browser: HelperBrowser

        @BeforeClass
        @JvmStatic
        fun openBrowser() {
            browser = HelperBrowser()
        }

        @AfterClass
        @JvmStatic
        fun closeBrowser() {
            if (::browser.isInitialized) browser.close()
        }
    }
}

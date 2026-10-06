package io.github.astiskala.minimpos.website

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.core.codec.QrChunkAssembler
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.TransferCodec
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
    fun `every language loads offline and generates genuine SVG QR codes with WebCrypto`() {
        for (language in listOf("", "zh-CN/", "ja/")) {
            openPage(language)
            assertThat(script.executeScript("return Boolean(crypto.subtle)")).isEqualTo(true)
            submit()
            ready()
            val svgLength =
                requireNotNull(script.executeScript("return document.querySelector('#setup-qr svg path').getAttribute('d').length")) as Long
            assertThat(svgLength).isGreaterThan(0)
            val (connection, secrets) = readTransfer()
            assertThat(connection).isEqualTo(json("""{"destination":"network","environment":"TEST"}"""))
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
        ).forEach(::field)
        submit()
        ready()
        readTransfer()
        assertThat(codes().distinct()).containsExactlyElementsIn(HELPER_VECTOR).inOrder()
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
            .isEqualTo(json("""{"destination":"network","environment":"TEST"}""") to json("""{"ADYEN_API_KEY":"demo-key"}"""))
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
                val automatic = if (mode == "automatic" && destination != "tapToPay") ""","automatic":true""" else ""
                val environment = if (destination in listOf("network", "cloud")) ""","environment":"TEST"""" else ""
                val expected =
                    """{"destination":"$destination"$automatic$environment,"smtpHost":"smtp.example.com","smtpPort":465,
                    |"smtpSecurity":"SSL","smtpUsername":"shop@example.com","smtpFromAddress":"receipts@example.com",
                    |"smtpFromName":"Example shop"}
                    """.trimMargin()
                assertThat(readTransfer())
                    .isEqualTo(
                        json(expected) to json("""{"ADYEN_API_KEY":"demo-key","SMTP_PASSWORD":" demo-smtp-password "}"""),
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
                json("""{"destination":"network","environment":"TEST","smtpSecurity":"STARTTLS","smtpPort":587}""") to
                    json("""{"ADYEN_API_KEY":"demo-key"}"""),
            )
    }

    @Test
    fun `Tap to Pay always uses manual key fields after either previous setup mode`() {
        for (mode in listOf("automatic", "manual")) {
            openPage()
            field("setupMode", mode)
            field("destination", "tapToPay")
            assertThat(script.executeScript("return document.querySelector('[data-mode]').disabled")).isEqualTo(false)
            assertThat(script.executeScript("return document.querySelector('[name=setupMode]').closest('fieldset').hidden"))
                .isEqualTo(true)
            mapOf(
                "merchantAccount" to " Merchant ",
                "paymentsAppApiKey" to " boarding-key ",
                "storeId" to " ST1 ",
                "keyIdentifier" to "manual-key",
                "keyVersion" to "2",
                "passphrase" to " manual secret ",
            ).forEach(::field)
            submit()
            ready()
            assertThat(readTransfer())
                .isEqualTo(
                    json(
                        """{"destination":"tapToPay","merchantAccount":"Merchant","storeId":"ST1",
                        |"keyIdentifier":"manual-key","keyVersion":2}
                        """.trimMargin(),
                    ) to
                        json(
                            """{"ADYEN_API_KEY":"demo-key","PAYMENTS_APP_API_KEY":"boarding-key",
                            |"TERMINAL_PASSPHRASE":" manual secret "}
                            """.trimMargin(),
                        ),
                )
        }
    }

    @Test
    fun `Tap to Pay permits device key entry but physical Manual setup still requires key`() {
        field("destination", "tapToPay")
        field("keyVersion", "1")
        submit()
        ready()
        assertThat(readTransfer())
            .isEqualTo(json("""{"destination":"tapToPay"}""") to json("""{"ADYEN_API_KEY":"demo-key"}"""))
        assertRequired(true)
        field("destination", "network")
        assertRequired(true)
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

    private fun submit() {
        script.executeScript("document.getElementById('setup-form').dispatchEvent(new Event('submit', {cancelable:true}));")
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

    private fun assertSmtpDisabled(disabled: Boolean) {
        assertThat(script.executeScript("return document.querySelector('[data-email]').disabled")).isEqualTo(disabled)
        assertThat(script.executeScript("return document.querySelector('[data-email]').hidden")).isEqualTo(disabled)
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
        assertThat(code).matches("[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}")
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

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    companion object {
        private val HELPER_VECTOR =
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

package app.minimpos.website

import app.minimpos.core.codec.QrChunkAssembler
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.TransferCodec
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Checks the browser logo pipeline against the same pixel fixtures used by core. */
class SetupLogoTest {
    private val docs = Path.of(System.getProperty("minimpos.website"))

    @Before
    fun openPage() {
        browser.open(docs.resolve("setup.html"))
    }

    @Test
    fun `js pipeline matches every core vector`() {
        val vectors = Json.parseToJsonElement(Files.readString(docs.parent.resolve("core/src/test/resources/logo/vectors.json")))
        val array = if (vectors is JsonArray) vectors else vectors.jsonObject.getValue("vectors").jsonArray
        for (vector in array) {
            val actual =
                requireNotNull(
                    browser.executeScript(
                        """
                        const vector = JSON.parse(arguments[0]);
                        const bytes = Uint8Array.from(atob(vector.argb), c => c.charCodeAt(0));
                        const rgba = new Uint8ClampedArray(bytes.length);
                        for (let i = 0; i < bytes.length; i += 4) {
                          rgba.set([bytes[i + 1], bytes[i + 2], bytes[i + 3], bytes[i]], i);
                        }
                        const options = {...vector.options, mode: vector.options.mode.toLowerCase(), size: vector.options.size.toLowerCase()};
                        const result = MiniMposLogo.convert(vector.width, vector.height, rgba, options);
                        const prepared = MiniMposLogo.prepare(vector.width, vector.height, rgba, options);
                        const first = MiniMposLogo.quantize(prepared, options);
                        first.bits.fill(0);
                        MiniMposLogo.quantize(prepared, {mode: 'photo', threshold: 200});
                        const repeated = MiniMposLogo.quantize(prepared, options);
                        if (JSON.stringify(result) !== JSON.stringify(repeated)) throw new Error('Prepared pixels were mutated');
                        delete result.kind;
                        result.mode = result.mode.toUpperCase();
                        result.bits = Array.from(result.bits, value => value.toString(16).padStart(2, '0')).join('');
                        return JSON.stringify(result);
                        """.trimIndent(),
                        vector.toString(),
                    ),
                ) as String
            assertThat(Json.parseToJsonElement(actual)).isEqualTo(vector.jsonObject.getValue("expected"))
        }
    }

    @Test
    fun `logo only codes carry only the logo without a code and editing clears them`() {
        chooseLogoOnly()
        uploadLogo()
        browser.executeScript(
            """
            window.__codes = [];
            const original = qrcodegen.QrCode.encodeText;
            qrcodegen.QrCode.encodeText = (text, level) => { window.__codes.push(text); return original(text, level); };
            document.getElementById('setup-form').requestSubmit();
            """.trimIndent(),
        )
        browser.await { browser.executeScript("return !document.getElementById('setup-codes').hidden") == true }
        val code = requireNotNull(browser.executeScript("return window.__codes[0]")) as String
        val assembler = QrChunkAssembler()
        assembler.add(requireNotNull(QrChunks.parse(code)))
        val transfer = TransferCodec.decode(assembler.assemble())
        assertThat(requireNotNull(transfer.logo).width).isEqualTo(2)
        assertThat(requireNotNull(transfer.logo).height).isEqualTo(2)
        assertThat(transfer.connection).isNull()
        assertThat(transfer.sealedSecrets).isNull()
        assertThat(browser.executeScript("return document.getElementById('setup-code-card').hidden")).isEqualTo(true)
        browser.executeScript(
            """
            const threshold = document.getElementById('logoThreshold');
            threshold.value = '10';
            threshold.dispatchEvent(new Event('input', {bubbles:true}));
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
    }

    @Test
    fun `choosing another image clears codes before decoding and a stale decode cannot restore them`() {
        chooseLogoOnly()
        uploadLogo()
        browser.executeScript("document.getElementById('setup-form').requestSubmit();")
        browser.await { browser.executeScript("return !document.getElementById('setup-codes').hidden") == true }
        browser.executeScript(
            """
            const original = window.createImageBitmap;
            window.createImageBitmap = file => new Promise((resolve, reject) => {
              window.__decodeLogo = () => original(file).then(resolve, reject);
              window.__rejectLogo = () => reject(new Error('unreadable fixture'));
            });
            // A direct control change also invalidates codes, without relying on event bubbling.
            document.getElementById('logo').dispatchEvent(new Event('change'));
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
        assertThat(browser.executeScript("return document.getElementById('setup-qr').childElementCount")).isEqualTo(0L)
        browser.await { browser.executeScript("return typeof window.__rejectLogo === 'function'") == true }
        browser.executeAsyncScript("const done = arguments[0]; window.__rejectLogo(); setTimeout(done, 0);")
        assertThat(browser.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
        assertThat(browser.executeScript("return document.getElementById('logo-status').textContent"))
            .isEqualTo(browser.executeScript("return document.getElementById('setup-form').dataset.msgLogoUnreadable"))
        browser.executeScript(
            """
            window.__decodeLogo = null;
            document.getElementById('logo').dispatchEvent(new Event('change'));
            """.trimIndent(),
        )
        browser.await { browser.executeScript("return typeof window.__decodeLogo === 'function'") == true }
        browser.executeScript("document.getElementById('logo-remove').click();")
        browser.executeAsyncScript("const done = arguments[0]; window.__decodeLogo().then(() => setTimeout(done, 0));")
        assertThat(browser.executeScript("return document.getElementById('logo-editor').hidden")).isEqualTo(true)
        assertThat(browser.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
    }

    @Test
    fun `changing style keeps manual inversion while resolving its threshold`() {
        chooseLogoOnly()
        uploadLogo()
        browser.executeScript(
            """
            const form = document.getElementById('setup-form');
            form.elements.logoInvert.checked = true;
            form.elements.logoInvert.dispatchEvent(new Event('input'));
            form.elements.logoThreshold.value = '10';
            form.elements.logoMode.value = 'photo';
            form.elements.logoMode.dispatchEvent(new Event('input'));
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return document.getElementById('logoInvert').checked")).isEqualTo(true)
        browser.await { browser.executeScript("return document.getElementById('logoThreshold').value") == "127" }
        assertThat(browser.executeScript("return document.getElementById('logoThreshold').value")).isEqualTo("127")
    }

    @Test
    fun `threshold edits coalesce without preparing the source again and submit flushes the latest edit`() {
        chooseLogoOnly()
        uploadLogo()
        browser.executeScript("document.getElementById('setup-form').requestSubmit();")
        browser.await { browser.executeScript("return !document.getElementById('setup-codes').hidden") == true }
        instrumentLogo()
        browser.executeScript(
            """
            const input = document.getElementById('logoThreshold');
            for (const value of [10, 30, 60]) {
              input.value = value;
              input.dispatchEvent(new Event('input'));
            }
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return document.getElementById('setup-codes').hidden")).isEqualTo(true)
        assertThat(browser.executeScript("return window.__prepares")).isEqualTo(0L)
        assertThat(browser.executeScript("return window.__quantizations.length")).isEqualTo(0L)
        assertThat(browser.executeScript("return window.__logoFrames.length")).isEqualTo(1L)
        browser.executeScript("window.__logoFrames[0]();")
        assertThat(browser.executeScript("return window.__quantizations")).isEqualTo(listOf(60L))
        browser.executeScript(
            """
            const input = document.getElementById('logoThreshold');
            input.value = 80; input.dispatchEvent(new Event('input'));
            document.getElementById('setup-form').requestSubmit();
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return window.__quantizations")).isEqualTo(listOf(60L, 80L))
        assertThat(browser.executeScript("return window.__prepares")).isEqualTo(0L)
        browser.await { browser.executeScript("return !document.getElementById('setup-codes').hidden") == true }
    }

    @Test
    fun `preparation cache changes with inversion trim size and source while removal cancels queued edits`() {
        chooseLogoOnly()
        uploadLogo()
        instrumentLogo()
        for (name in listOf("logoInvert", "logoTrim", "logoSize")) {
            browser.executeScript(
                """
                const input = document.getElementById(arguments[0]);
                if (input.type === 'checkbox') input.checked = !input.checked; else input.value = 'small';
                input.dispatchEvent(new Event('input'));
                window.__logoFrames.at(-1)();
                """.trimIndent(),
                name,
            )
        }
        assertThat(browser.executeScript("return window.__prepares")).isEqualTo(3L)
        browser.executeScript(
            """
            document.getElementById('logoThreshold').dispatchEvent(new Event('input'));
            document.getElementById('logo-remove').click();
            window.__logoFrames.at(-1)();
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return window.__quantizations.length")).isEqualTo(3L)
        assertThat(browser.executeScript("return document.getElementById('logo-editor').hidden")).isEqualTo(true)
        uploadLogo()
        assertThat(browser.executeScript("return window.__prepares")).isEqualTo(4L)
        browser.executeScript(
            """
            document.getElementById('logoThreshold').dispatchEvent(new Event('input'));
            window.__staleLogoFrame = window.__logoFrames.at(-1);
            """.trimIndent(),
        )
        uploadLogo()
        browser.executeScript("window.__staleLogoFrame();")
        assertThat(browser.executeScript("return window.__prepares")).isEqualTo(5L)
        assertThat(browser.executeScript("return window.__quantizations.length")).isEqualTo(5L)
        browser.executeScript(
            """
            document.getElementById('logoThreshold').dispatchEvent(new Event('input'));
            document.getElementById('setup-form').reset();
            window.__logoFrames.at(-1)();
            """.trimIndent(),
        )
        assertThat(browser.executeScript("return window.__quantizations.length")).isEqualTo(5L)
        assertThat(browser.executeScript("return document.getElementById('logo-editor').hidden")).isEqualTo(true)
    }

    private fun instrumentLogo() {
        browser.executeScript(
            """
            const original = window.MiniMposLogo;
            window.__prepares = 0; window.__quantizations = []; window.__logoFrames = [];
            window.MiniMposLogo = {
              ...original,
              prepare: (...args) => { window.__prepares++; return original.prepare(...args); },
              quantize: (prepared, options) => {
                window.__quantizations.push(options.threshold);
                return original.quantize(prepared, options);
              },
            };
            window.requestAnimationFrame = callback => window.__logoFrames.push(callback);
            window.cancelAnimationFrame = () => {};
            """.trimIndent(),
        )
    }

    @Test
    fun `complete setup with logo authenticates the exact logo record`() {
        browser.executeScript(
            """
            const form = document.getElementById('setup-form');
            form.elements.apiKey.value = 'logo-demo-key';
            form.elements.includeReceipt.value = 'yes';
            form.dispatchEvent(new Event('change'));
            """.trimIndent(),
        )
        uploadLogo()
        browser.executeScript(
            """
            window.__codes = [];
            const original = qrcodegen.QrCode.encodeText;
            qrcodegen.QrCode.encodeText = (text, level) => { window.__codes.push(text); return original(text, level); };
            document.getElementById('setup-form').requestSubmit();
            """.trimIndent(),
        )
        browser.await { browser.executeScript("return !document.getElementById('setup-codes').hidden") == true }
        val first = requireNotNull(QrChunks.parse(requireNotNull(browser.executeScript("return window.__codes[0]")) as String))
        repeat(first.total - 1) { browser.executeScript("document.getElementById('setup-next').click()") }
        val codes = requireNotNull(browser.executeScript("return window.__codes")) as List<*>
        val assembler = QrChunkAssembler()
        codes.take(first.total).forEach { assembler.add(requireNotNull(QrChunks.parse(requireNotNull(it) as String))) }
        val transfer = TransferCodec.decode(assembler.assemble())
        val logo = requireNotNull(transfer.logo)
        assertThat(logo.width).isEqualTo(2)
        assertThat(logo.height).isEqualTo(2)
        assertThat(logo.bits()).isEqualTo(byteArrayOf(0xc0.toByte(), 0xc0.toByte()))
        val sealed = requireNotNull(transfer.sealedSecrets).toByteArray()
        val buffer = ByteBuffer.wrap(sealed)
        assertThat(buffer.get().toInt()).isEqualTo(2)
        val iterations = buffer.int
        val salt = ByteArray(16).also(buffer::get)
        val iv = ByteArray(12).also(buffer::get)
        val code = requireNotNull(browser.executeScript("return document.getElementById('setup-code').textContent")) as String
        val spec = PBEKeySpec(code.replace("-", "").toCharArray(), salt, iterations, 256)
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
        assertThat(String(secrets, Charsets.UTF_8)).contains("logo-demo-key")
    }

    @Test
    fun `each helper permits local blob images and shows the same logo controls`() {
        for (language in listOf("", "es-MX/", "fr-FR/", "ja/", "pt-BR/", "zh-CN/", "zh-Hant/")) {
            browser.open(docs.resolve("${language}setup.html"))
            chooseLogoOnly()
            val policy =
                requireNotNull(
                    browser.executeScript("return document.querySelector('meta[http-equiv=Content-Security-Policy]').content"),
                ) as String
            assertThat(policy).contains("img-src 'self' blob:;")
            assertThat(browser.executeScript("return document.getElementById('complete-fields').hidden")).isEqualTo(true)
            assertThat(browser.executeScript("return document.getElementById('logo-fields').hidden")).isEqualTo(false)
        }
    }

    private fun chooseLogoOnly() {
        browser.executeScript(
            """
            const form = document.getElementById('setup-form');
            form.elements.make.value = 'logo';
            form.dispatchEvent(new Event('change'));
            """.trimIndent(),
        )
    }

    private fun uploadLogo() {
        browser.executeAsyncScript(
            """
            const done = arguments[0];
            const canvas = document.createElement('canvas'); canvas.width = 4; canvas.height = 4;
            const context = canvas.getContext('2d'); context.fillStyle = 'white'; context.fillRect(0, 0, 4, 4);
            context.fillStyle = 'black'; context.fillRect(1, 1, 2, 2);
            canvas.toBlob(blob => {
              const files = new DataTransfer(); files.items.add(new File([blob], 'logo.png', {type:'image/png'}));
              const input = document.getElementById('logo'); input.files = files.files;
              input.dispatchEvent(new Event('change', {bubbles:true})); done();
            }, 'image/png');
            """.trimIndent(),
        )
        browser.await { browser.executeScript("return !document.getElementById('logo-editor').hidden") == true }
    }

    companion object {
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

package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Path

/** Exercises bounded metadata reads separately from the browser's raster decoder. */
class SetupLogoDecodingTest {
    private val docs = Path.of(System.getProperty("minimpos.website"))

    @Test
    fun `oversize PNG metadata requests a bounded bitmap while small PNG preserves decoding`() {
        uploadHeader("89504e470d0a1a0a0000000d4948445200001f40000007d0080600000000000000", "image/png")
        decoded()
        assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeWidth")).isEqualTo(4096L)
        assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeHeight")).isEqualTo(1024L)
        assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeQuality")).isEqualTo("high")
        uploadHeader("89504e470d0a1a0a0000000d494844520000001000000008080600000000000000", "image/png")
        decoded()
        assertThat(browser.executeScript("return window.__bitmapOptions[0]")).isNull()
        assertThat(requireNotNull(browser.executeScript("return Math.max(...window.__headerReads)")) as Long).isAtMost(30L)
    }

    @Test
    fun `oversize raster resize dimensions follow EXIF orientation in every container`() {
        val headers =
            listOf(
                (
                    "89504e470d0a1a0a0000000d4948445200001f40000007d00806000000000000000000001a6558496649492a000800000001" +
                        "0012010300010000000600000000000000000000000000000049454e4400000000"
                ) to "image/png",
                ("ffd8ffe1002245786966000049492a0008000000010012010300010000000600000000000000ffc000080807d01f4003") to "image/jpeg",
                "ffd8ffe100224578696600004d4d002a00000008000101120003000000010006000000000000ffc000080807d01f4003" to "image/jpeg",
                (
                    "524946463800000057454250565038580a000000080000003f1f00cf0700455849461a00000049492a000800000001001201" +
                        "0300010000000600000000000000"
                ) to "image/webp",
            )
        headers.forEach { (header, type) ->
            uploadHeader(header, type)
            decoded()
            assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeWidth")).isEqualTo(1024L)
            assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeHeight")).isEqualTo(4096L)
        }
    }

    @Test
    fun `invalid EXIF orientation falls back to normal dimensions`() {
        val headers =
            listOf(
                "ffd8ffe1002245786966000049492a0008000000010012010300010000000900000000000000ffc000080807d01f4003",
                "ffd8ffe1002245786966000049492a00ffffffff010012010300010000000600000000000000ffc000080807d01f4003",
            )
        headers.forEach { header ->
            uploadHeader(header, "image/jpeg")
            decoded()
            assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeWidth")).isEqualTo(4096L)
            assertThat(browser.executeScript("return window.__bitmapOptions[0].resizeHeight")).isEqualTo(1024L)
        }
    }

    @Test
    fun `an actual oversized rotated JPEG keeps portrait proportions in the bounded decoded bitmap`() {
        browser.open(docs.resolve("setup.html"))
        browser.executeAsyncScript(
            """
            const done = arguments[0];
            const originalDecode = window.createImageBitmap;
            window.__decodedShape = null;
            window.createImageBitmap = (...args) => originalDecode(...args).then(bitmap => {
              window.__decodedShape = [bitmap.width, bitmap.height];
              return bitmap;
            });
            const canvas = document.createElement('canvas'); canvas.width = 8000; canvas.height = 2000;
            const context = canvas.getContext('2d'); context.fillStyle = 'white'; context.fillRect(0, 0, 8000, 2000);
            context.fillStyle = 'black'; context.fillRect(1000, 500, 6000, 1000);
            canvas.toBlob(async blob => {
              const original = new Uint8Array(await blob.arrayBuffer());
              const exif = Uint8Array.from([
                255,225,0,34,69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0,
              ]);
              const file = new File([original.slice(0, 2), exif, original.slice(2)], 'portrait.jpg', {type:'image/jpeg'});
              const files = new DataTransfer(); files.items.add(file);
              const input = document.getElementById('logo'); input.files = files.files;
              input.dispatchEvent(new Event('change', {bubbles:true})); done();
            }, 'image/jpeg');
            """.trimIndent(),
        )
        decoded()
        assertThat(browser.executeScript("return window.__decodedShape[0]")).isEqualTo(1024L)
        assertThat(browser.executeScript("return window.__decodedShape[1]")).isEqualTo(4096L)
    }

    @Test
    fun `JPEG frame and all WebP frame types supply dimensions without changing small source decoding`() {
        val headers =
            listOf(
                "ffd8ffe000040000ffc00008080008001003" to "image/jpeg",
                "524946461600000057454250565038580a000000000000000f0000070000" to "image/webp",
                "5249464612000000574542505650384c050000002f0fc0010000" to "image/webp",
                "524946461600000057454250565038200a0000000000009d012a10000800" to "image/webp",
            )
        headers.forEach { (header, type) ->
            uploadHeader(header, type)
            decoded()
            assertThat(browser.executeScript("return window.__bitmapOptions.length")).isEqualTo(1L)
            assertThat(browser.executeScript("return window.__bitmapOptions[0]")).isNull()
            assertThat(requireNotNull(browser.executeScript("return Math.max(...window.__headerReads)")) as Long).isAtMost(30L)
        }
    }

    @Test
    fun `invalid and truncated raster headers are unreadable before bitmap decoding`() {
        val headers =
            listOf(
                "89504e470d0a1a0a0000000d4948445200000010" to "image/png",
                "89504e470d0a1a0a0000000d494844520000000000000008080600000000000000" to "image/png",
                "ffd8ffe00040" to "image/jpeg",
                "ffd8ffc000040800" to "image/jpeg",
                "ffd8ffd9" to "image/jpeg",
                "524946461600000057454250565038580a00000000000000" to "image/webp",
                "524946461600000057454250424144210a000000000000000f0000070000" to "image/webp",
                "5249464612000000574542505650384c05000000000fc0010000" to "image/webp",
            )
        headers.forEach { (header, type) ->
            uploadHeader(header, type)
            browser.await { browser.executeScript("return document.getElementById('logo-status').textContent.length > 0") == true }
            assertThat(browser.executeScript("return window.__bitmapOptions.length")).isEqualTo(0L)
            assertThat(browser.executeScript("return document.getElementById('logo-status').textContent"))
                .isEqualTo(browser.executeScript("return document.getElementById('setup-form').dataset.msgLogoUnreadable"))
        }
    }

    @Test
    fun `JPEG metadata marker work is bounded`() {
        uploadHeader("ffd8" + "ff01".repeat(256), "image/jpeg")
        browser.await { browser.executeScript("return document.getElementById('logo-status').textContent.length > 0") == true }
        assertThat(browser.executeScript("return window.__bitmapOptions.length")).isEqualTo(0L)
    }

    @Test
    fun `SVG keeps local blob image decoding`() {
        browser.open(docs.resolve("setup.html"))
        browser.executeScript(
            """
            window.createImageBitmap = () => { throw new Error('Raster decoding must not handle SVG'); };
            const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4">' +
              '<rect width="4" height="4" fill="white"/><rect x="1" y="1" width="2" height="2"/></svg>';
            const files = new DataTransfer(); files.items.add(new File([svg], 'logo.svg', {type:'image/svg+xml'}));
            const input = document.getElementById('logo'); input.files = files.files;
            input.dispatchEvent(new Event('change', {bubbles:true}));
            """.trimIndent(),
        )
        decoded()
        assertThat(browser.executeScript("return document.getElementById('logo-status').textContent")).isEqualTo("")
    }

    private fun decoded() {
        browser.await { browser.executeScript("return !document.getElementById('logo-editor').hidden") == true }
    }

    private fun uploadHeader(
        header: String,
        type: String,
    ) {
        browser.open(docs.resolve("setup.html"))
        browser.executeScript(
            """
            const [header, type] = arguments;
            window.__bitmapOptions = []; window.__headerReads = [];
            const originalRead = Blob.prototype.arrayBuffer;
            Blob.prototype.arrayBuffer = function() { window.__headerReads.push(this.size); return originalRead.call(this); };
            const originalDecode = window.createImageBitmap;
            const canvas = document.createElement('canvas'); canvas.width = 4; canvas.height = 4;
            const ctx = canvas.getContext('2d'); ctx.fillStyle = 'white'; ctx.fillRect(0, 0, 4, 4);
            ctx.fillStyle = 'black'; ctx.fillRect(1, 1, 2, 2);
            // Only the metadata is under test here; decoder output is a fixed, valid bounded bitmap.
            window.createImageBitmap = (file, options) => {
              window.__bitmapOptions.push(options || null);
              return originalDecode(canvas);
            };
            const bytes = Uint8Array.from(header.match(/../g), pair => parseInt(pair, 16));
            const files = new DataTransfer(); files.items.add(new File([bytes], 'fixture', {type}));
            const input = document.getElementById('logo'); input.files = files.files;
            input.dispatchEvent(new Event('change', {bubbles:true}));
            """.trimIndent(),
            header,
            type,
        )
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

package app.minimpos.app.scan

import androidx.camera.core.ImageProxy
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class BarcodeAnalyzerTest {
    @Test
    fun `padded luminance frames decode while throttled and failed frames always close`() {
        var now = 1_000L
        var closed = 0
        val codes = mutableListOf<String>()
        val analyzer = BarcodeAnalyzer(listOf(BarcodeFormat.QR_CODE), false, clock = { now }, onCode = codes::add)
        val width = 320
        val stride = 352
        val matrix = QRCodeWriter().encode("MPR1*scanner-test", BarcodeFormat.QR_CODE, width, width)
        val pixels =
            ByteArray(stride * width) { index ->
                if (index % stride < width && matrix[index % stride, index / stride]) 0 else 255.toByte()
            }

        fun frame(bytes: ByteArray): ImageProxy {
            val plane =
                Proxy.newProxyInstance(
                    ImageProxy.PlaneProxy::class.java.classLoader,
                    arrayOf(ImageProxy.PlaneProxy::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getBuffer" -> ByteBuffer.wrap(bytes)
                        "getRowStride" -> stride
                        "getPixelStride" -> 1
                        else -> error(method.name)
                    }
                } as ImageProxy.PlaneProxy
            return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "getPlanes" -> {
                        arrayOf(plane)
                    }

                    "getWidth", "getHeight" -> {
                        width
                    }

                    "close" -> {
                        closed++
                        null
                    }

                    else -> {
                        error(method.name)
                    }
                }
            } as ImageProxy
        }
        analyzer.analyze(frame(pixels))
        analyzer.analyze(frame(pixels))
        assertThat(codes).containsExactly("MPR1*scanner-test")
        repeat(4) {
            now += 100
            analyzer.analyze(frame(ByteArray(stride * width) { 255.toByte() }))
        }
        now += 100
        analyzer.analyze(frame(pixels))
        assertThat(codes).containsExactly("MPR1*scanner-test", "MPR1*scanner-test")
        assertThat(closed).isEqualTo(7)
    }
}

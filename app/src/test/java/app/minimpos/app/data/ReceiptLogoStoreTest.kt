package app.minimpos.app.data

import androidx.datastore.core.CorruptionException
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.settings.ReceiptLogoFile
import app.minimpos.app.data.settings.ReceiptLogoSerializer
import app.minimpos.app.data.settings.StorageState
import app.minimpos.core.logo.MonoBitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class ReceiptLogoStoreTest {
    @Test
    fun `missing file reads as no logo`() {
        val env = TestEnvironment()
        try {
            assertThat(await { env.container.receiptLogo.current() }).isNull()
        } finally {
            env.close()
        }
    }

    @Test
    fun `replace then read and remove writes empty`() {
        val env = TestEnvironment()
        val image = MonoBitmap(3, 1, byteArrayOf(0xA0.toByte()))
        try {
            await {
                env.container.receiptLogo.replace(image)
                assertThat(env.container.receiptLogo.current()).isEqualTo(image)
                assertThat(env.dir.resolve("receipt-logo.bin").readBytes()).isEqualTo(byteArrayOf(1) + image.toBytes())
                env.container.receiptLogo.replace(null)
                assertThat(env.container.receiptLogo.current()).isNull()
            }
            assertThat(env.dir.resolve("receipt-logo.bin").readBytes()).isEmpty()
        } finally {
            env.close()
        }
    }

    @Test
    fun `corrupt logo reports storage unavailable and is kept`() {
        val env = TestEnvironment(storedFiles = mapOf("receipt-logo.bin" to "\u0002\u0001\u0001\u0000"))
        try {
            await {
                env.container.storage.state
                    .first { it == StorageState.UNAVAILABLE }
            }
            assertThat(env.dir.resolve("receipt-logo.bin").readBytes()).isEqualTo(byteArrayOf(2, 1, 1, 0))
        } finally {
            env.close()
        }
    }

    @Test
    fun `released settings without print business name default to true`() {
        val settings = checkNotNull(javaClass.getResource("/stored/1.3.2/settings.json")).readText()
        val env = TestEnvironment(storedFiles = mapOf("settings.json" to settings))
        try {
            assertThat(await { env.container.settings.current() }.receipt.printBusinessName).isTrue()
        } finally {
            env.close()
        }
    }

    @Test
    fun `serializer rejects malformed and oversized records`() {
        listOf(byteArrayOf(1), byteArrayOf(1, 3, 1, 1), ByteArray(10 * 1024 + 2) { 1 }).forEach { bytes ->
            assertThrows(CorruptionException::class.java) {
                await { ReceiptLogoSerializer.readFrom(ByteArrayInputStream(bytes)) }
            }
        }
    }

    @Test
    fun `serializer round trips empty and image files`() {
        listOf(ReceiptLogoFile(), ReceiptLogoFile(MonoBitmap(1, 1, byteArrayOf(0x80.toByte())))).forEach { value ->
            val output = ByteArrayOutputStream()
            await {
                ReceiptLogoSerializer.writeTo(value, output)
                assertThat(ReceiptLogoSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))).isEqualTo(value)
            }
        }
    }
}

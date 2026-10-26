package app.minimpos.core

import app.minimpos.core.catalogue.Catalogue
import app.minimpos.core.catalogue.CatalogueCategory
import app.minimpos.core.catalogue.CatalogueProduct
import app.minimpos.core.codec.Base45
import app.minimpos.core.codec.QrChunkAssembler
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.core.codec.TransferFormatException
import app.minimpos.core.money.CurrencySpec
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.Deflater

/** The exact limits of the QR formats and money types, found by mutation testing (`./gradlew mutationTest`). */
class BoundariesTest {
    @Test
    fun `a set needing more than 999 codes is spread over exactly 999 longer ones`() {
        assertThat(QrChunks.split("A".repeat(999), "SET1", maxDataChars = 1)).hasSize(999)
        val doubled = QrChunks.split("A".repeat(1_998), "SET1", maxDataChars = 1)
        assertThat(doubled).hasSize(999)
        assertThat(doubled.map { it.data.length }.distinct()).containsExactly(2)
        assertThat(QrChunks.split("A".repeat(1_999), "SET1", maxDataChars = 1)).hasSize(667)
    }

    @Test
    fun `set IDs and positions are checked at their limits`() {
        listOf("0000", "9999", "AAAA", "ZZZZ").forEach { assertThat(QrChunks.split("x", it)).hasSize(1) }
        listOf("000/", ":AAA", "@AAA", "[AAA", "aaaa", "AAA").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { QrChunks.split("x", id) }
        }
        assertThat(QrChunks.parse("MPC1:SET1:999/999:x")).isEqualTo(QrChunks.Chunk("SET1", 999, 999, "x"))
        assertThat(QrChunks.parse("MPC1:SET1:1/1:x")).isNotNull()
        listOf(
            "",
            "\n",
            "MPC1:SET1:1/1000:x",
            "MPC1:SET1:0/1:x",
            "MPC1:SET1:2/1:x",
            "MPC1:SET1:1/0:x",
            "MPC1:SET1:0/0:x",
        ).forEach {
            assertThat(QrChunks.parse(it)).isNull()
        }
    }

    @Test
    fun `codes of an earlier set are forgotten when another set starts, and reset forgets everything`() {
        val assembler = QrChunkAssembler()
        assertThat(assembler.isComplete).isFalse()
        assertThrows(IllegalStateException::class.java) { assembler.assemble() }
        assembler.add(QrChunks.Chunk("SETA", 2, 3, "a2"))
        assembler.add(QrChunks.Chunk("SETB", 1, 3, "b1"))
        assembler.add(QrChunks.Chunk("SETB", 3, 3, "b3"))
        assertThat(assembler.isComplete).isFalse()
        assertThat(assembler.missing).containsExactly(2)
        assembler.reset()
        assertThat(assembler.received).isEqualTo(0)
        assertThat(assembler.isComplete).isFalse()
        assertThrows(IllegalStateException::class.java) { assembler.assemble() }
        assembler.add(QrChunks.Chunk("SETB", 1, 1, "b"))
        assertThat(assembler.assemble()).isEqualTo("b")
    }

    @Test
    fun `the largest three-character and two-character Base45 groups are accepted, and one more is rejected`() {
        assertThat(Base45.decode("FGW").toList()).isEqualTo(listOf(0xFF.toByte(), 0xFF.toByte()))
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("GGW") }
        assertThat(Base45.encode(byteArrayOf(-1, -1))).isEqualTo("FGW")
        assertThat(Base45.decode("U5").toList()).isEqualTo(listOf(0xFF.toByte()))
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("V5") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("\u007F1") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("\u00801") }
    }

    @Test
    fun `a transfer carries settings, connection and secrets up to their limits and rejects one byte more`() {
        val settings = "s".repeat(TransferCodec.MAX_SETTINGS_BYTES)
        val connection = "c".repeat(TransferCodec.MAX_CONNECTION_BYTES)
        val secrets = SealedSecrets(ByteArray(TransferCodec.MAX_SECRETS_BYTES) { it.toByte() })
        val largest = Transfer(settings = settings, sealedSecrets = secrets, connection = connection)
        assertThat(TransferCodec.decode(TransferCodec.encode(largest))).isEqualTo(largest)
        listOf(
            largest.copy(settings = "$settings!"),
            largest.copy(connection = "$connection!"),
            largest.copy(sealedSecrets = SealedSecrets(ByteArray(TransferCodec.MAX_SECRETS_BYTES + 1))),
        ).forEach { tooLarge -> assertThrows(IllegalArgumentException::class.java) { TransferCodec.encode(tooLarge) } }
    }

    @Test
    fun `transfer binary reader and writer enforce exact string, count, varint and body size limits`() {
        val maxName = "a".repeat(1024)
        val catAtLimit =
            Catalogue(
                currencyCode = "AUD",
                taxRates = emptyList(),
                categories = listOf(CatalogueCategory(maxName)),
                products = listOf(CatalogueProduct(maxName, 0, null, 0, maxName)),
            )
        assertThat(TransferCodec.decode(TransferCodec.encode(Transfer(catalogue = catAtLimit))).catalogue).isEqualTo(catAtLimit)
        val categoryAtEnd =
            Catalogue(
                currencyCode = "AUD",
                taxRates = emptyList(),
                categories = listOf(CatalogueCategory("Coffee")),
                products = emptyList(),
            )
        assertThat(TransferCodec.decode(TransferCodec.encode(Transfer(catalogue = categoryAtEnd))).catalogue).isEqualTo(categoryAtEnd)
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(Transfer(catalogue = catAtLimit.copy(categories = listOf(CatalogueCategory(maxName + "!")))))
        }

        // Header boundary: 4 bytes is truncated before inflate; 5 bytes with empty deflate is truncated in inflate.
        val fourBytes = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(Base45.encode(ByteArray(4))) }
        assertThat(fourBytes.message).isEqualTo("Transfer data is truncated")
        val fiveBytesWrongVersion =
            assertThrows(TransferFormatException::class.java) {
                TransferCodec.decode(Base45.encode(byteArrayOf(9, 0, 0, 0, 0)))
            }
        assertThat(fiveBytesWrongVersion.message).isEqualTo("Unsupported transfer version 9")
        val fiveBytes =
            assertThrows(TransferFormatException::class.java) {
                TransferCodec.decode(Base45.encode(byteArrayOf(6, 0, 0, 0, 0)))
            }
        assertThat(fiveBytes.message).isEqualTo("Transfer data is truncated")

        // Inflate body limit: 2 MiB of zeros passes inflate (and fails checksum), 2 MiB + 1 fails as too large.
        val exactMaxBody = rawPacket(ByteArray(2 * 1024 * 1024), crcOverride = 123)
        val checksumErr = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(exactMaxBody) }
        assertThat(checksumErr.message).isEqualTo("Transfer checksum mismatch")
        val overMaxBody = rawPacket(ByteArray(2 * 1024 * 1024 + 1))
        val tooLargeErr = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(overMaxBody) }
        assertThat(tooLargeErr.message).isEqualTo("Transfer is too large")
    }

    @Test
    fun `transfer binary reader enforces count, string length and 9-byte varint limits`() {
        // Count limit: 100,000 passes readCount and hits truncated data; 100,001 fails with too many entries.
        val atMaxItems = rawPacket(varint(1) + "AUD".toByteArray() + varint(100_000))
        val truncatedItems = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(atMaxItems) }
        assertThat(truncatedItems.message).isEqualTo("Transfer data is truncated")
        val overMaxItems = rawPacket(varint(1) + "AUD".toByteArray() + varint(100_001))
        val tooManyItems = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(overMaxItems) }
        assertThat(tooManyItems.message).isEqualTo("Transfer has too many entries")

        // String limit in decoder: 1024 passes length check and hits truncated/invalid text; 1025 fails immediately.
        val overMaxString =
            rawPacket(
                varint(1) + "AUD".toByteArray() + varint(0) + varint(1) + varint(1025) + ByteArray(1025) { 'a'.code.toByte() } + varint(0),
            )
        val badText = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(overMaxString) }
        assertThat(badText.message).isEqualTo("Invalid text in transfer")
        val truncatedString =
            rawPacket(
                varint(1) + "AUD".toByteArray() + varint(0) + varint(1) + varint(4) + "abc".toByteArray(),
            )
        val badTruncatedText = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(truncatedString) }
        assertThat(badTruncatedText.message).isEqualTo("Invalid text in transfer")

        // 9-byte varint (shift == 56) is decoded as a valid Long; 10-byte varint (shift == 63) is rejected.
        val nineByteZero = ByteArray(8) { 0x80.toByte() } + byteArrayOf(0)
        val validNineByte =
            rawPacket(
                varint(1) + "AUD".toByteArray() + nineByteZero + nineByteZero + nineByteZero,
            )
        assertThat(TransferCodec.decode(validNineByte).catalogue)
            .isEqualTo(Catalogue("AUD", emptyList(), emptyList(), emptyList()))
        val nineByteLargeRate =
            rawPacket(
                varint(1) + "AUD".toByteArray() + varint(1) + varint(1) + "G".toByteArray() +
                    (ByteArray(8) { 0x80.toByte() } + byteArrayOf(1)),
            )
        val rateErr = assertThrows(TransferFormatException::class.java) { TransferCodec.decode(nineByteLargeRate) }
        assertThat(rateErr.message).contains("Invalid tax rate")
    }

    @Test
    fun `refund codes and currencies are validated at their limits`() {
        val at = Instant.ofEpochMilli(1)
        assertThat(RefundQrPayload("T", at, 0, "AAZ").amountMinor).isEqualTo(0)
        assertThat(RefundQrPayload("T", at, 1, "ZZA").currency).isEqualTo("ZZA")
        listOf(
            { RefundQrPayload("T", at, -1, "AUD") },
            { RefundQrPayload("T", at, 1, "AU") },
            { RefundQrPayload("T", at, 1, "AU@") },
            { RefundQrPayload("T", at, 1, "AU[") },
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
        assertThat(CurrencySpec("AAZ", 0).fractionDigits).isEqualTo(0)
        assertThat(CurrencySpec("ZZA", 4).fractionDigits).isEqualTo(4)
        listOf({ CurrencySpec("AU@", 2) }, { CurrencySpec("AU[", 2) }, { CurrencySpec("AUD", 5) }, { CurrencySpec("AUD", -1) })
            .forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun rawPacket(
        body: ByteArray,
        crcOverride: Int? = null,
    ): String {
        val crc = crcOverride ?: CRC32().apply { update(body) }.value.toInt()
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        deflater.setInput(body)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val deflated = out.toByteArray()
        val packet =
            ByteBuffer
                .allocate(5 + deflated.size)
                .put(6.toByte())
                .putInt(crc)
                .put(deflated)
                .array()
        return Base45.encode(packet)
    }
}

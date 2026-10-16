package app.minimpos.core

import app.minimpos.core.codec.Base45
import app.minimpos.core.codec.QrChunkAssembler
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.core.money.CurrencySpec
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

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
        listOf("MPC1:SET1:1/1000:x", "MPC1:SET1:0/1:x", "MPC1:SET1:2/1:x", "MPC1:SET1:1/0:x").forEach {
            assertThat(QrChunks.parse(it)).isNull()
        }
    }

    @Test
    fun `codes of an earlier set are forgotten when another set starts, and reset forgets everything`() {
        val assembler = QrChunkAssembler()
        assertThat(assembler.isComplete).isFalse()
        assembler.add(QrChunks.Chunk("SETA", 2, 3, "a2"))
        assembler.add(QrChunks.Chunk("SETB", 1, 3, "b1"))
        assembler.add(QrChunks.Chunk("SETB", 3, 3, "b3"))
        assertThat(assembler.isComplete).isFalse()
        assertThat(assembler.missing).containsExactly(2)
        assembler.reset()
        assertThat(assembler.received).isEqualTo(0)
        assertThat(assembler.isComplete).isFalse()
        assembler.add(QrChunks.Chunk("SETB", 1, 1, "b"))
        assertThat(assembler.assemble()).isEqualTo("b")
    }

    @Test
    fun `the largest three-character Base45 group is two bytes, and one more is rejected`() {
        assertThat(Base45.decode("FGW").toList()).isEqualTo(listOf(0xFF.toByte(), 0xFF.toByte()))
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("GGW") }
        assertThat(Base45.encode(byteArrayOf(-1, -1))).isEqualTo("FGW")
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
}

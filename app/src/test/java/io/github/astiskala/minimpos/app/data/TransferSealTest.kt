package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.security.PinManager
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** Plain JUnit: sealing needs only the JDK's crypto. */
class TransferSealTest {
    private val seal = TransferSeal(SecureRandom(), iterations = 1_000)

    @Test
    fun `codes are twelve unambiguous characters in groups of four, typed loosely`() {
        val codes = List(50) { seal.newCode() }
        codes.forEach { code ->
            assertThat(code).matches("[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}")
            assertThat(TransferSeal.isValidCode(code)).isTrue()
        }
        assertThat(codes.toSet().size).isEqualTo(50)
        assertThat(TransferSeal.normalize(" k7pq-8z3d 2rxm ")).isEqualTo("K7PQ8Z3D2RXM")
        assertThat(TransferSeal.isValidCode("k7pq 8z3d 2rxm")).isTrue()
        listOf("", "K7PQ-8Z3D", "K7PQ-8Z3D-2RXM-A", "K7PQ-8Z3D-2RX0", "O7PQ-8Z3D-2RXM").forEach {
            assertThat(TransferSeal.isValidCode(it)).isFalse()
        }
    }

    @Test
    fun `sealed secrets open only with their code and unchanged`() {
        val code = seal.newCode()
        val sealed = seal.seal("hunter2".toByteArray(), code)
        assertThat(String(sealed, Charsets.ISO_8859_1)).doesNotContain("hunter2")
        assertThat(seal.open(sealed, code.lowercase())).isEqualTo("hunter2".toByteArray())
        // Fresh salt and IV every time.
        assertThat(seal.seal("hunter2".toByteArray(), code)).isNotEqualTo(sealed)
        // A seal with the default (slower) iterations opens too: the count travels with the data.
        assertThat(TransferSeal().open(sealed, code)).isEqualTo("hunter2".toByteArray())

        assertThat(seal.open(sealed, "2222-2222-2222")).isNull()
        assertThat(seal.open(sealed, "short")).isNull()
        assertThat(seal.open(sealed.copyOf(10), code)).isNull()
        assertThat(seal.open(sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }, code)).isNull()
        assertThat(seal.open(sealed.copyOf().also { it[0] = 1 }, code)).isNull()
        // An iteration count meant to keep the terminal busy is refused.
        assertThat(seal.open(sealed.copyOf().also { it[1] = 0x7F }, code)).isNull()
        assertThrows(IllegalArgumentException::class.java) { seal.seal(byteArrayOf(1), "nope") }
    }

    @Test
    fun `public transfer data must match exactly when opening a seal`() {
        val code = seal.newCode()
        val publicData = "connection metadata".toByteArray()
        val sealed = seal.seal("{}".toByteArray(), code, publicData)
        assertThat(seal.open(sealed, code, publicData)).isEqualTo("{}".toByteArray())
        assertThat(seal.open(sealed, code)).isNull()
        assertThat(seal.open(sealed, code, "changed metadata".toByteArray())).isNull()
    }

    @Test
    fun `code normalization does not silently discard punctuation or expand Unicode letters`() {
        listOf("K7PQ/8Z3D/2RXM", "K7PQ_8Z3D_2RXM", "K7PQ8Z3D2RXM!", "K7PQ8Z3D2Rß").forEach {
            assertThat(TransferSeal.isValidCode(it)).isFalse()
        }
    }

    @Test
    fun `PIN verifiers copied from another terminal are checked before they are stored`() {
        val hash = Base64.getEncoder().encodeToString(ByteArray(32))
        val salt = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThat(PinManager.isValidVerifier("v1:20000:$salt:$hash")).isTrue()
        listOf(
            "v2:20000:$salt:$hash",
            "v1:0:$salt:$hash",
            "v1:x:$salt:$hash",
            "v1:20000::$hash",
            "v1:20000:$salt:${hash.dropLast(4)}",
            "v1:20000:$salt:!!",
            "v1:20000:$salt",
        ).forEach { assertThat(PinManager.isValidVerifier(it)).isFalse() }
    }
}

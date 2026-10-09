package app.minimpos.app.data.security

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals secrets for a transfer to another device with a freshly generated transfer code, displayed separately from
 * the QR codes and typed on the receiving device. The QR codes alone do not reveal secrets; possession of both the
 * codes and the transfer code permits decryption again, without expiration or a single-use check.
 *
 * A code is [CODE_LENGTH] characters from an alphabet without look-alikes (no 0, O, 1, I or L), about 59 bits, shown in
 * groups of four. The key is PBKDF2-HMAC-SHA256 of the code with a random salt, and the secrets are encrypted with
 * AES-256-GCM with the canonical public transfer sections as authenticated data. Sealed format version 2:
 * `version(1) | iterations(4) | salt(16) | iv(12) | ciphertext and tag`.
 *
 * Key derivation is deliberately slow: call [seal] and [open] off the main thread.
 *
 * @param random Source of codes, salts and IVs.
 * @param iterations PBKDF2 iterations for new seals; sealed data carries its own count. Tests use fewer.
 */
class TransferSeal(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    /** A new random transfer code, formatted in groups of four such as "K7PQ-8Z3D-2RXM". */
    fun newCode(): String =
        List(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(GROUP)
            .joinToString("-") { it.joinToString("") }

    /**
     * Encrypts [plaintext] with [code] (as [normalize] leaves it).
     *
     * @throws IllegalArgumentException if [code] is not [isValidCode].
     */
    fun seal(
        plaintext: ByteArray,
        code: String,
        publicData: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(isValidCode(code)) { "Invalid transfer code" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(normalize(code), salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(publicData)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer
            .allocate(HEADER_BYTES + ciphertext.size)
            .put(VERSION)
            .putInt(iterations)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    /** Decrypts [sealed] from [seal] with [code]; null when the code is wrong or the data was changed or damaged. */
    fun open(
        sealed: ByteArray,
        code: String,
        publicData: ByteArray = byteArrayOf(),
    ): ByteArray? {
        if (!isValidCode(code) || sealed.size < HEADER_BYTES || sealed[0] != VERSION) return null
        val buffer = ByteBuffer.wrap(sealed)
        buffer.get()
        val rounds = buffer.int
        if (rounds !in 1..MAX_ITERATIONS) return null
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(normalize(code), salt, rounds), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(publicData)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (ignored: GeneralSecurityException) {
            null
        }
    }

    private fun key(
        code: String,
        salt: ByteArray,
        rounds: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, rounds, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Code format and crypto parameters. */
    companion object {
        /** Characters in a transfer code, not counting the separators. */
        const val CODE_LENGTH = 12

        /** Codes use digits and upper-case letters that cannot be mistaken for one another. */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        private const val GROUP = 4
        private const val VERSION: Byte = 2
        private const val DEFAULT_ITERATIONS = 150_000

        /** Sealed data asking for more is rejected, so a crafted code cannot keep the terminal busy for long. */
        private const val MAX_ITERATIONS = 2_000_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val HEADER_BYTES = 1 + Int.SIZE_BYTES + SALT_BYTES + IV_BYTES
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** [code] as typed, in upper case without spaces, hyphens or other separators. */
        fun normalize(code: String): String =
            code
                .map {
                    if (it in
                        'a'..'z'
                    ) {
                        it.uppercaseChar()
                    } else {
                        it
                    }
                }.filterNot { it == '-' || it.isWhitespace() }
                .joinToString("")

        /** Whether [code], once [normalize]d, has [CODE_LENGTH] characters of [ALPHABET]. */
        fun isValidCode(code: String): Boolean = normalize(code).let { it.length == CODE_LENGTH && it.all(ALPHABET::contains) }
    }
}

package app.minimpos.app.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Fresh terminal-key values; string conversion never exposes the secret.
 * @property identifier Random installation-independent identifier prefixed with `minimpos-`.
 * @property passphrase A 32-character secret meeting Adyen's uppercase, lowercase, digit and special-character requirements.
 */
class SharedKeyMaterial(
    val identifier: String,
    val passphrase: String,
)

/** Generates shared keys using cryptographic randomness; callers encrypt results before sending them.
 * @param random Source of identifier, passphrase and shuffle entropy.
 */
class SharedKeyGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    /** Creates independent identifier and passphrase values; no merchant-entered secret is reused. */
    fun generate(): SharedKeyMaterial {
        val characters = GROUPS.map { it[random.nextInt(it.length)] }.toMutableList()
        repeat(PASSPHRASE_LENGTH - GROUPS.size) { characters += ALPHABET[random.nextInt(ALPHABET.length)] }
        for (index in characters.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val value = characters[index]
            characters[index] = characters[other]
            characters[other] = value
        }
        val identifier =
            "minimpos-" + List(IDENTIFIER_LENGTH) { IDENTIFIER_ALPHABET[random.nextInt(IDENTIFIER_ALPHABET.length)] }.joinToString("")
        return SharedKeyMaterial(identifier, characters.joinToString(""))
    }

    internal fun identity(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private companion object {
        const val PASSPHRASE_LENGTH = 32
        const val IDENTIFIER_LENGTH = 16
        val GROUPS = listOf("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz", "0123456789", "!@#$%^&*_+=?")
        val ALPHABET = GROUPS.joinToString("")
        val IDENTIFIER_ALPHABET = GROUPS.take(3).joinToString("")
    }
}

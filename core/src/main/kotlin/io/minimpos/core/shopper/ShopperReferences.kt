package io.minimpos.core.shopper

import java.security.MessageDigest
import java.util.Locale

/** How a shopper's email address is turned into the Adyen shopperReference when it doubles as the reference. */
enum class EmailReferenceMode {
    /** A stable SHA-256 based reference, so no PII is sent in shopperReference (Adyen's guidance). */
    HASHED,

    /** The normalised email address itself. */
    RAW,
}

/**
 * Validation and derivation of the Adyen shopperReference, which identifies a shopper for saving cards (tokenisation).
 * It comes either from a customer reference typed at checkout or from the shopper's email address.
 */
object ShopperReferences {
    /** The fewest characters, after trimming, that a typed customer reference needs; see [isValidReference]. */
    const val MIN_LENGTH = 3
    private const val HASH_PREFIX = "em_"

    /** The first 128 bits of the digest. */
    private const val HASH_HEX_CHARS = 32

    /** A shape check (local part, '@', domain with a dot and a TLD of two or more characters), not RFC 5322. */
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /** Trims and lower-cases [email], so differently typed forms of one address give the same reference. */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /** Whether [email], ignoring surrounding whitespace, has the shape of an address: name@domain.tld, no spaces. */
    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

    /** Whether [reference] has at least [MIN_LENGTH] characters once trimmed. */
    fun isValidReference(reference: String): Boolean = reference.trim().length >= MIN_LENGTH

    /**
     * Derives a shopperReference from [email], normalised with [normalizeEmail]. With [EmailReferenceMode.HASHED] the
     * result is "em_" and 32 hex digits of SHA-256 over the address and [salt]; the same email and [salt] always give
     * the same reference on every terminal, so tokens can be reused across devices sharing the salt. The email is not
     * validated here; check it with [isValidEmail] first.
     */
    fun fromEmail(
        email: String,
        mode: EmailReferenceMode,
        salt: String = "",
    ): String {
        val normalized = normalizeEmail(email)
        return when (mode) {
            EmailReferenceMode.RAW -> {
                normalized
            }

            EmailReferenceMode.HASHED -> {
                // Changing this input format would give returning shoppers new references and orphan their saved cards.
                val digest = MessageDigest.getInstance("SHA-256").digest("minimpos:$salt:$normalized".toByteArray())
                HASH_PREFIX + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(HASH_HEX_CHARS)
            }
        }
    }
}

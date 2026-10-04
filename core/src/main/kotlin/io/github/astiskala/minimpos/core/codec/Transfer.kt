package io.github.astiskala.minimpos.core.codec

import io.github.astiskala.minimpos.core.catalogue.Catalogue

/**
 * What one terminal hands another by QR code (see [TransferCodec]) to set it up the same way: the product catalogue,
 * the settings and the secrets, each optional; or what the setup helper web page hands a device: the connection and
 * the secrets. The constructor throws [IllegalArgumentException] when all four are missing.
 *
 * @property catalogue the products, categories and tax rates, or null when not transferred.
 * @property settings the settings as text the app writes and reads (JSON), opaque here; null when not transferred.
 * @property sealedSecrets the secrets, encrypted by the app with a code the operator types on the receiving terminal;
 *   null when not transferred.
 * @property connection where payments go and how they get there, as text the setup helper writes and the app reads
 *   (JSON), opaque here; null when not transferred.
 */
data class Transfer(
    val catalogue: Catalogue? = null,
    val settings: String? = null,
    val sealedSecrets: SealedSecrets? = null,
    val connection: String? = null,
) {
    init {
        require(catalogue != null || settings != null || sealedSecrets != null || connection != null) {
            "A transfer needs something to transfer"
        }
    }
}

/**
 * Encrypted secrets in a [Transfer], opaque here: the app seals and opens them. Compared by content.
 *
 * @param bytes the sealed bytes, copied.
 */
class SealedSecrets(
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    /** The sealed bytes (a copy). */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is SealedSecrets && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SealedSecrets(${bytes.size} bytes)"
}

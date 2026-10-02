package io.minimpos.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The credentials the app keeps; each is stored under its name in [SecretBlob]. */
enum class Secret {
    /** Passphrase of the terminal's shared key, which encrypts Terminal API messages (NexoCrypto). */
    TERMINAL_PASSPHRASE,

    /** Password for the SMTP server in [io.minimpos.app.data.settings.EmailSettings]. */
    SMTP_PASSWORD,

    /** The admin PIN's salted hash, written by [PinManager]; the PIN itself is never stored. */
    PIN_VERIFIER,

    /**
     * API key for Adyen's Checkout API, for captures and authorisation adjustments (see `TerminalSettings`), and for the
     * Cloud device API when payments go to a terminal in the cloud (the stored name predates that).
     */
    CHECKOUT_API_KEY,

    /** API key with the Adyen Payments app role, for boarding and revoking the Payments app on this phone. */
    PAYMENTS_APP_API_KEY,
}

/** Encrypts secrets at rest. Implementations may block (Keystore calls), so [SecretStore] calls them off the main thread. */
interface SecretCipher {
    /**
     * Encrypts [plaintext] into a self-contained blob that [decrypt] accepts.
     *
     * @throws java.security.GeneralSecurityException if encryption fails.
     */
    fun encrypt(plaintext: ByteArray): ByteArray

    /**
     * Decrypts a blob from [encrypt].
     *
     * @throws java.security.GeneralSecurityException if [ciphertext] is corrupt or was encrypted with another key.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore; output is `iv(12) | ciphertext+tag`. The key is
 * created on first use under [alias]. It belongs to this installation on this device, so secrets encrypted with a key
 * that has since gone cannot be decrypted and must be entered again.
 */
class KeystoreSecretCipher(
    private val alias: String = "minimpos.secrets",
) : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, ciphertext, 0, IV_LENGTH))
        return cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.size - IV_LENGTH)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}

/**
 * The stored form of all secrets (DataStore file `secrets.json`).
 *
 * @property values Base64 of each secret's ciphertext, keyed by [Secret] name; a secret that is not set has no entry.
 */
@Serializable
data class SecretBlob(
    val values: Map<String, String> = emptyMap(),
)

/** The device could not encrypt a secret (e.g. the Android Keystore failed), so it was not stored. */
class SecretStoreException(
    cause: Throwable,
) : Exception(listOfNotNull(cause::class.simpleName, cause.message).joinToString(": "), cause)

/**
 * Encrypted key/value storage for credentials; values never leave this class unencrypted except via [get]. Keystore
 * operations can be slow on terminals, so they run off the main thread.
 */
class SecretStore(
    private val store: DataStore<SecretBlob>,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The secrets that are stored, emitted again after every change. A stored secret may still fail to decrypt (see
     * [get]), which callers report as "could not be read" rather than "not set".
     */
    val configured: Flow<Set<Secret>> =
        store.data.map { blob -> Secret.entries.filter { it.name in blob.values }.toSet() }

    /** The secret, or null when it is not stored or can no longer be decrypted. */
    suspend fun get(secret: Secret): String? {
        val stored = store.data.first().values[secret.name] ?: return null
        return withContext(io) {
            try {
                cipher.decrypt(Base64.getDecoder().decode(stored)).decodeToString()
            } catch (ignored: GeneralSecurityException) {
                null
            } catch (ignored: IllegalArgumentException) {
                null
            } catch (ignored: ProviderException) {
                null
            } catch (ignored: IOException) {
                null
            }
        }
    }

    /** Stores [value], or clears the secret when it is null or empty. Throws [SecretStoreException] if it cannot be encrypted. */
    suspend fun set(
        secret: Secret,
        value: String?,
    ) {
        val encrypted = value?.takeIf { it.isNotEmpty() }?.let { encrypt(it) }
        store.updateData { blob ->
            SecretBlob(if (encrypted == null) blob.values - secret.name else blob.values + (secret.name to encrypted))
        }
    }

    private suspend fun encrypt(value: String): String =
        withContext(io) {
            try {
                Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray()))
            } catch (e: GeneralSecurityException) {
                throw SecretStoreException(e)
            } catch (e: ProviderException) {
                throw SecretStoreException(e)
            } catch (e: IOException) {
                throw SecretStoreException(e)
            }
        }
}

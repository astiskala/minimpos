package io.minimpos.app.data.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The outcome of [PinManager.verify]. */
sealed interface PinCheck {
    /** The PIN is correct, or no PIN is set. */
    data object Accepted : PinCheck

    /**
     * The PIN is wrong.
     *
     * @property attemptsLeft Wrong attempts left before entry is locked out.
     */
    data class Rejected(
        val attemptsLeft: Int,
    ) : PinCheck

    /**
     * Too many wrong attempts: no PIN is checked until the lockout ends, not even the right one.
     *
     * @property untilMillis When the lockout ends, in epoch milliseconds.
     */
    data class LockedOut(
        val untilMillis: Long,
    ) : PinCheck
}

/**
 * Admin PIN: stored as a salted PBKDF2-SHA256 verifier inside [SecretStore] (`v1:<iterations>:<salt>:<hash>`, Base64),
 * so the PIN itself is never stored. Every [MAX_ATTEMPTS] wrong attempts lock entry out, for [LOCKOUT_MILLIS] and then
 * twice as long each time (up to 32 times as long). The failure count is kept in memory only, so it starts over when
 * the app restarts.
 */
class PinManager(
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** PBKDF2 iterations for new PINs; stored verifiers carry their own count. Tests use fewer to run faster. */
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    /** Whether a PIN is set, emitted again whenever that changes. */
    val pinConfigured: Flow<Boolean> = secrets.configured.map { Secret.PIN_VERIFIER in it }

    private var failures = 0
    private var lockedUntil = 0L

    /**
     * Sets a new PIN and clears any lockout.
     *
     * @throws IllegalArgumentException if [pin] is not [isValidPin].
     * @throws SecretStoreException if the verifier cannot be encrypted on this device.
     */
    suspend fun setPin(pin: String) {
        require(isValidPin(pin)) { "PIN must be $MIN_LENGTH-$MAX_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val encoder = Base64.getEncoder()
        secrets.set(
            Secret.PIN_VERIFIER,
            "v1:$iterations:${encoder.encodeToString(salt)}:${encoder.encodeToString(hash(pin, salt, iterations))}",
        )
        failures = 0
        lockedUntil = 0
    }

    /** Removes the PIN, which leaves the admin area unprotected. */
    suspend fun clearPin() = secrets.set(Secret.PIN_VERIFIER, null)

    /**
     * Checks [pin] against the stored verifier, comparing the hashes in constant time. Accepts any PIN when none is
     * set.
     */
    suspend fun verify(pin: String): PinCheck {
        val now = clock()
        if (now < lockedUntil) return PinCheck.LockedOut(lockedUntil)
        val stored = secrets.get(Secret.PIN_VERIFIER) ?: return PinCheck.Accepted
        val parts = stored.split(':')
        val decoder = Base64.getDecoder()
        val matches =
            parts.size == 4 &&
                MessageDigest.isEqual(hash(pin, decoder.decode(parts[2]), parts[1].toInt()), decoder.decode(parts[3]))
        if (matches) {
            failures = 0
            return PinCheck.Accepted
        }
        failures++
        if (failures % MAX_ATTEMPTS == 0) {
            val round = failures / MAX_ATTEMPTS
            lockedUntil = now + LOCKOUT_MILLIS * (1L shl (round - 1).coerceAtMost(MAX_LOCKOUT_SHIFT))
            return PinCheck.LockedOut(lockedUntil)
        }
        return PinCheck.Rejected(MAX_ATTEMPTS - failures % MAX_ATTEMPTS)
    }

    private fun hash(
        pin: String,
        salt: ByteArray,
        rounds: Int,
    ): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, HASH_BITS)
        return SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
            .also { spec.clearPassword() }
    }

    /** PIN rules and lockout timing. */
    companion object {
        /** Fewest digits in a PIN. */
        const val MIN_LENGTH = 4

        /** Most digits in a PIN. */
        const val MAX_LENGTH = 8

        /** Wrong attempts in a row that trigger a lockout. */
        const val MAX_ATTEMPTS = 5

        /** Length of the first lockout, in milliseconds; each following one doubles. */
        const val LOCKOUT_MILLIS = 30_000L
        private const val MAX_LOCKOUT_SHIFT = 5
        private const val DEFAULT_ITERATIONS = 20_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256

        /** Whether [pin] is [MIN_LENGTH] to [MAX_LENGTH] ASCII digits. */
        fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
    }
}

/**
 * Tracks whether the admin area is unlocked. It re-locks on [lock] (when the admin screens are left) or when [touch]
 * finds no activity within its time-out. Starts locked; call from the main thread.
 */
class SessionLock(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _unlocked = MutableStateFlow(false)

    /** Whether the admin area is unlocked now. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var lastActivity = 0L

    /** Unlocks the admin area (after the PIN was accepted or set) and starts the inactivity time-out. */
    fun unlock() {
        lastActivity = clock()
        _unlocked.value = true
    }

    /** Locks the admin area. */
    fun lock() {
        _unlocked.value = false
    }

    /**
     * Records activity and returns whether the session is still unlocked. It locks instead when more than
     * [autoLockMillis] have passed since the last activity; 0 or less never times out.
     */
    fun touch(autoLockMillis: Long): Boolean {
        if (!_unlocked.value) return false
        val now = clock()
        if (autoLockMillis > 0 && now - lastActivity > autoLockMillis) {
            lock()
            return false
        }
        lastActivity = now
        return true
    }
}

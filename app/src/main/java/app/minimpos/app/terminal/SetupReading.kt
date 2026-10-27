package app.minimpos.app.terminal

import app.minimpos.core.payment.PaymentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One memory-only reading of Adyen-side facts for the current setup, such as wallet offers ([WalletDiscovery]) or
 * terminal capabilities ([CapabilityDiscovery]): the one owner of how such a reading follows its setup. A refresh begins
 * on the setup stored now, keeps an earlier reading only while its setup identity and payment context are unchanged,
 * shows itself as checking, and publishes a result only while the setup it began on is still stored with the same
 * secrets; a stale result is dropped, and checking always ends. Refreshes and other [locked] work run one at a time.
 *
 * @param S The reading.
 * @param setups Resolves the setup and its fence.
 * @param empty The reading before anything was read, kept when the setup changed.
 * @param identityOf The setup fingerprint a reading was made for; null before discovery.
 * @param contextOf The payment context a reading was made for; null before discovery.
 * @param idle The reading with checking ended.
 */
internal class SetupReading<S>(
    private val setups: TerminalSetupSource,
    private val empty: S,
    private val identityOf: (S) -> String?,
    private val contextOf: (S) -> PaymentContext?,
    private val idle: (S) -> S,
) {
    private val current = MutableStateFlow(empty)
    private val mutex = Mutex()

    /** The reading now. */
    val state: StateFlow<S> get() = current

    /** Forgets the reading, as when its setup changed; a later refresh starts from the empty reading. */
    fun reset() {
        current.value = empty
    }

    /** Runs [block] while no refresh or other locked work runs. */
    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Refreshes the reading for the setup stored now and returns it once checking ended: [begin] makes the reading shown
     * while it runs from the earlier one, kept only for the same setup (else the empty reading); [read] then publishes
     * its results through the [Refresh], which drops them once the setup changed.
     */
    suspend fun refresh(
        begin: Refresh.(previous: S) -> S,
        read: suspend Refresh.(initial: S) -> Unit,
    ): S =
        mutex.withLock {
            val origin = setups.unlocked()
            val refresh = Refresh(origin, identity(origin), origin.setup.paymentContext())
            val previous = current.value.takeIf { identityOf(it) == refresh.identity && contextOf(it) == refresh.context } ?: empty
            val initial = refresh.begin(previous)
            current.value = initial
            try {
                refresh.read(initial)
            } finally {
                // This lock still owns the reading, including when its caller is cancelled or its setup changes.
                current.value = idle(current.value)
            }
            current.value
        }

    /** Publishes [result] read for [origin] only while that setup is still stored with the same secrets. */
    suspend fun publish(
        origin: UnlockedSetup,
        result: S,
    ) {
        if (setups.SetupFence(origin, SetupFreshness.SECRETS).stillCurrent()) current.value = result
    }

    /** The fingerprint of [origin]'s checked connection and payment secrets a reading is made for. */
    fun identity(origin: UnlockedSetup): String? = setups.SetupFence(origin, SetupFreshness.SECRETS).identity

    /** Why work begun on [origin] must not apply now, because its setup or secrets changed; null when it may. */
    suspend fun changed(origin: UnlockedSetup) = setups.SetupFence(origin, SetupFreshness.SECRETS).changed()

    /** Whether [reading] was made for the setup stored now, which has no setup problem; checked without networking. */
    suspend fun madeForCurrent(reading: S): Boolean {
        val actual = setups.unlocked()
        return actual.setup.problem == null && identity(actual) == identityOf(reading) &&
            actual.setup.paymentContext() == contextOf(reading)
    }

    /**
     * One refresh, begun on the setup stored then.
     *
     * @property origin The setup it began on, with its secrets.
     * @property identity The setup fingerprint it is made for.
     * @property context The payment context it is made for.
     */
    inner class Refresh internal constructor(
        val origin: UnlockedSetup,
        val identity: String?,
        val context: PaymentContext?,
    ) {
        /** The setup it began on. */
        val setup: TerminalSetup get() = origin.setup

        /** Publishes [result] only while the setup this refresh began on is still stored with the same secrets. */
        suspend fun publish(result: S) = publish(origin, result)
    }
}

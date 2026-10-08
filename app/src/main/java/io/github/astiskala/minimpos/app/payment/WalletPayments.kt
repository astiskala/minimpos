package io.github.astiskala.minimpos.app.payment

import io.github.astiskala.minimpos.app.terminal.NativeScanner
import io.github.astiskala.minimpos.app.terminal.TerminalGateway
import io.github.astiskala.minimpos.app.terminal.WalletAvailability
import io.github.astiskala.minimpos.app.terminal.WalletDiscovery
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.payment.ScanWallet
import io.github.astiskala.minimpos.terminal.client.BarcodeScan
import io.github.astiskala.minimpos.terminal.client.ScannedPayment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** Where the transient wallet scan stands; only PAYING hands off to the persisted transaction lifecycle. */
enum class WalletScanStage {
    /** No prepared checkout, including after process restart. */
    CLOSED,

    /** Operator chooses an explicitly configured wallet. */
    CHOOSING,

    /** One scanner is active, or an explicit offline demo is offered. */
    SCANNING,

    /** A valid code is being checked against the current checkout and setup. */
    VALIDATING,

    /** Explicit Scan again is required, including after background or a native timeout. */
    PAUSED,

    /** The original persisted sale lifecycle now owns payment and recovery. */
    PAYING,
}

/** Source of a code; DEMO is allowed only for a captured simulator context. */
enum class WalletScanSource {
    /** Camera on the device running Mini mPOS. */
    CAMERA,

    /** Confirmed native scanner on the payment terminal, possibly remote. */
    NATIVE,

    /** Explicit synthetic code, never a real-payment fallback. */
    DEMO,
}

/** Local scan problem, worded only at the presentation boundary and containing no payment data. */
enum class WalletScanProblem {
    /** Code does not match the selected wallet's numeric contract. */
    INVALID_CODE,

    /** Native scanner did not return a code; no payment was started. */
    SCAN_FAILED,

    /** Checkout or connection changed; return and review it before trying again. */
    CHECKOUT_CHANGED,

    /** Another financial operation is already running. */
    BUSY,
}

/**
 * Presentation facts only; no raw code, settings object or request payload can enter saved UI state.
 * @property stage Current step.
 * @property epoch Attempt identity; callbacks from an older scanner are ignored.
 * @property amountMinor Cart total in currency minor units.
 * @property currency Currency and Adyen decimal convention.
 * @property wallets Explicitly available choices for the prepared checkout.
 * @property selected Chosen wallet, or null before selection.
 * @property source Chosen scanner, or null before selection.
 * @property nativeScanner Whether a native scanner was confirmed for the original destination.
 * @property terminalId Original payment terminal identity; null before preparation.
 * @property simulated Whether this is an offline demo, never proof of provider support.
 * @property savingRequested Whether consent and shopper-reference rules requested best-effort saving.
 * @property problem Latest local error; null when none.
 */
data class WalletPaymentState(
    val stage: WalletScanStage = WalletScanStage.CLOSED,
    val epoch: Long = 0,
    val amountMinor: Long = 0,
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val wallets: List<ScanWallet> = emptyList(),
    val selected: ScanWallet? = null,
    val source: WalletScanSource? = null,
    val nativeScanner: Boolean = false,
    val terminalId: String? = null,
    val simulated: Boolean = false,
    val savingRequested: Boolean = false,
    val problem: WalletScanProblem? = null,
)

/**
 * Prepares one sale snapshot, collects a code once and delegates to the existing persisted payment lifecycle.
 * Entry methods are serialized; validation and native reads run on the application scope. Cancellation invalidates
 * callbacks immediately and ends native scanning on its original connection, never a later destination.
 * @param session Original sale session; only it validates the session revision.
 * @param payments Existing payment persistence, settlement, receipts and recovery.
 * @param discovery Supplies verified, context-bound wallet availability.
 * @param gateway Opens an original-context native scanner and validates financial send context.
 * @param scope Work that must survive screen changes, including native-session cancellation.
 * @param currentCheckout Reads a fresh coherent checkout without network calls.
 * @param clock Stamps generated payment references and scan-session identities.
 * @param zone Merchant-reference time zone.
 */
class WalletPayments(
    private val session: SaleSession,
    private val payments: TransactionLifecycle<PaymentStart>,
    private val discovery: WalletDiscovery,
    private val gateway: TerminalGateway,
    private val scope: CoroutineScope,
    private val currentCheckout: suspend () -> Checkout,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutable = MutableStateFlow(WalletPaymentState())
    private val epochs = AtomicLong(clock.millis())
    private var prepared: Checkout? = null
    private var origin = WalletAvailability()
    private var nativeJob: Job? = null
    private var native: Pair<NativeScanner, Long>? = null
    private var ending: Job? = null

    /** Scan presentation; financial progress is published separately by the existing lifecycle. */
    val state: StateFlow<WalletPaymentState> = mutable.asStateFlow()

    /** Captures [checkout]; false when unavailable or a payment is already running. A sole wallet skips selection. */
    @Synchronized
    fun begin(checkout: Checkout): Boolean {
        if (!checkout.canScanWallet || payments.state.value is TransactionState.Processing) return false
        stopNative()
        prepared = checkout
        origin = discovery.state.value
        mutable.value =
            WalletPaymentState(
                stage = WalletScanStage.CHOOSING,
                epoch = epochs.incrementAndGet(),
                amountMinor = checkout.totals.amounts.gross,
                currency = checkout.currency,
                wallets = checkout.wallets,
                nativeScanner = origin.nativeScanner,
                terminalId = origin.context?.poiId,
                simulated = origin.simulated,
                savingRequested = checkout.tokenize,
            )
        checkout.wallets.singleOrNull()?.let(::choose)
        return true
    }

    /** Chooses [wallet] only from the original offered list; defaults to native hardware, otherwise camera or offline demo. */
    @Synchronized
    fun choose(wallet: ScanWallet?) {
        val current = mutable.value
        if (current.stage in setOf(WalletScanStage.CLOSED, WalletScanStage.PAYING, WalletScanStage.VALIDATING)) return
        if (wallet == null) {
            stopNative()
            mutable.value =
                current.copy(
                    stage = WalletScanStage.CHOOSING,
                    selected = null,
                    source = null,
                    epoch = epochs.incrementAndGet(),
                    problem = null,
                )
            return
        }
        if (wallet !in current.wallets) return
        mutable.value = current.copy(selected = wallet)
        useSource(
            when {
                current.nativeScanner -> WalletScanSource.NATIVE
                current.simulated -> WalletScanSource.DEMO
                else -> WalletScanSource.CAMERA
            },
        )
    }

    /** Starts a fresh attempt from [source]; unsupported native/demo sources are ignored. */
    @Synchronized
    fun useSource(source: WalletScanSource) {
        val current = mutable.value
        if (current.selected == null ||
            current.stage in setOf(WalletScanStage.CLOSED, WalletScanStage.PAYING, WalletScanStage.VALIDATING)
        ) {
            return
        }
        if (source == WalletScanSource.NATIVE && !current.nativeScanner) return
        if (source == WalletScanSource.DEMO && !current.simulated) return
        stopNative()
        val next = current.copy(stage = WalletScanStage.SCANNING, source = source, epoch = epochs.incrementAndGet(), problem = null)
        mutable.value = next
        if (source == WalletScanSource.NATIVE) startNative(next.epoch)
    }

    /** Explicit retry after pause or timeout; never resends a financial operation. */
    fun scanAgain() {
        state.value.source?.let(::useSource)
    }

    /** Stops pre-payment scanning when backgrounded; returning requires an explicit new attempt. */
    @Synchronized
    fun background() {
        if (mutable.value.stage !in setOf(WalletScanStage.SCANNING, WalletScanStage.VALIDATING)) return
        stopNative()
        mutable.value = mutable.value.copy(stage = WalletScanStage.PAUSED, epoch = epochs.incrementAndGet())
    }

    /** Abandons transient scanning without changing checkout or aborting a payment already handed off. */
    @Synchronized
    fun close() {
        stopNative()
        prepared = null
        mutable.value = WalletPaymentState(epoch = epochs.incrementAndGet())
    }

    /** Checks transient [code] for active attempt [epoch], leaving camera scanning active after invalid input. */
    @Synchronized
    fun accepts(
        code: String,
        epoch: Long,
    ): Boolean {
        val current = mutable.value
        if (current.epoch != epoch || current.stage != WalletScanStage.SCANNING) return false
        val valid = current.selected?.accepts(code) == true
        if (!valid) {
            mutable.value =
                current.copy(
                    problem = WalletScanProblem.INVALID_CODE,
                    stage = if (current.source == WalletScanSource.NATIVE) WalletScanStage.PAUSED else WalletScanStage.SCANNING,
                )
        }
        return valid
    }

    /** Accepts [code] once for active [epoch]; fresh checkout/setup validation precedes persistence and sending. */
    @Synchronized
    fun scanned(
        code: String,
        epoch: Long,
    ): Boolean {
        if (!accepts(code, epoch)) return false
        val current = mutable.value
        val checkout = prepared ?: return false
        val captured = origin
        mutable.value = current.copy(stage = WalletScanStage.VALIDATING, problem = null)
        scope.launch {
            val actual = currentCheckout()
            val ready =
                discovery.current(captured) && current.selected in discovery.state.value.offered(actual.currency.code) &&
                    actual.currency == checkout.currency && actual.totals == checkout.totals && actual.form == checkout.form &&
                    actual.payment == checkout.payment
            synchronized(this@WalletPayments) {
                if (mutable.value.epoch != epoch || mutable.value.stage != WalletScanStage.VALIDATING) return@synchronized
                val start = session.paymentStart(checkout, clock.instant(), zone(), scannedWallet = true).takeIf { ready }
                when {
                    start == null -> {
                        mutable.value =
                            current.copy(stage = WalletScanStage.PAUSED, problem = WalletScanProblem.CHECKOUT_CHANGED)
                    }

                    payments.state.value is TransactionState.Processing -> {
                        mutable.value =
                            current.copy(stage = WalletScanStage.PAUSED, problem = WalletScanProblem.BUSY)
                    }

                    else -> {
                        val payment =
                            start.withScanned(
                                ScannedPayment(checkNotNull(current.selected).brand, code),
                                checkNotNull(captured.context),
                                checkNotNull(captured.identity),
                            )
                        payments.start(payment)
                        mutable.value = current.copy(stage = WalletScanStage.PAYING)
                    }
                }
            }
        }
        return true
    }

    /** Explicit synthetic input only for the original simulator context; never a fallback in a real scan. */
    fun demo() {
        val current = state.value
        if (current.simulated && current.source == WalletScanSource.DEMO) current.selected?.let { scanned(it.demoCode, current.epoch) }
    }

    private fun startNative(epoch: Long) {
        val captured = origin
        val previousEnd = ending
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                previousEnd?.join()
                val scanner = gateway.nativeScanner(checkNotNull(captured.context), checkNotNull(captured.identity))
                if (scanner == null) {
                    failedScan(epoch)
                    return@launch
                }
                synchronized(this@WalletPayments) {
                    if (mutable.value.epoch == epoch && mutable.value.stage == WalletScanStage.SCANNING) native = scanner to epoch
                }
                if (state.value.epoch != epoch || state.value.stage != WalletScanStage.SCANNING) return@launch
                when (val result = scanner.read(epoch)) {
                    is BarcodeScan.Read -> scanned(result.code, epoch)
                    BarcodeScan.NotRead -> failedScan(epoch)
                }
                synchronized(this@WalletPayments) { if (native?.second == epoch) native = null }
            }
        nativeJob = job
        job.start()
    }

    @Synchronized
    private fun failedScan(epoch: Long) {
        if (mutable.value.epoch == epoch && mutable.value.stage == WalletScanStage.SCANNING) {
            mutable.value = mutable.value.copy(stage = WalletScanStage.PAUSED, problem = WalletScanProblem.SCAN_FAILED)
        }
    }

    private fun stopNative() {
        nativeJob?.cancel()
        nativeJob = null
        val old = native
        native = null
        if (old != null) ending = scope.launch { old.first.end(old.second) }
    }
}

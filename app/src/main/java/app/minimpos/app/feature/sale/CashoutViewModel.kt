package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.repo.cashHandover
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.history.StoredPayments
import app.minimpos.app.payment.CashTenders
import app.minimpos.app.payment.Cashback
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock

/** Standalone entry and frozen request; ordinary card approval never records cash handover. */
internal class CashoutViewModel(
    private val settings: StateFlow<AppSettings>,
    private val discovery: StateFlow<WalletAvailability>,
    configured: Flow<Boolean>,
    private val currency: (AppSettings) -> CurrencySpec,
    private val payments: TransactionLifecycle<PaymentStart>,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val amount = MutableStateFlow(AmountEntry())
    private val confirmed = MutableStateFlow<CashbackSettings?>(null)
    val state =
        combine(settings, discovery, configured, amount, confirmed) { app, facts, pin, entered, card ->
            val code = currency(app).code
            CashoutState(
                code,
                entered,
                card == app.payment.cashback,
                pin,
                Cashback.ready(app.payment.cashback, facts, code, true),
                app.payment.cashback.limitMinor,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CashoutState())

    fun digit(value: Int) {
        amount.value = amount.value.append(value)
    }

    fun doubleZero() {
        amount.value = amount.value.appendDoubleZero()
    }

    fun backspace() {
        amount.value = amount.value.backspace()
    }

    fun confirmCard(value: Boolean) {
        confirmed.value =
            settings.value.payment.cashback
                .takeIf { value }
    }

    fun start(): Boolean {
        val current = state.value
        val app = settings.value
        val facts = discovery.value
        val code = currency(app)
        if (confirmed.value != app.payment.cashback) return false
        if (!current.ready || code.code != current.currency) return false
        if (!Cashback.ready(app.payment.cashback, facts, code.code, true) ||
            current.amount.minor > app.payment.cashback.limitMinor
        ) {
            return false
        }
        val request =
            Checkout.cashoutStart(facts, app.payment, code, current.amount.minor, clock.instant().atZone(clock.zone))
                ?: return false
        return payments.start(request) != null
    }
}

internal data class CashoutState(
    val currency: String = "AUD",
    val amount: AmountEntry = AmountEntry(),
    val cardConfirmed: Boolean = false,
    val managerConfigured: Boolean = false,
    val available: Boolean = false,
    val limitMinor: Long = 0,
) {
    val ready: Boolean get() = available && cardConfirmed && managerConfigured && amount.minor in 1..limitMinor
}

internal data class CashHandoverState(
    val ready: Boolean = false,
    val confirmed: Boolean = false,
    val managerConfigured: Boolean = false,
    val currency: String = PaymentSettings.FALLBACK_CURRENCY,
    val amountMinor: Long? = null,
)

/** Confirmation writes survive leaving the screen and can be recorded only once by CashTenders. */
internal class CashHandoverViewModel(
    private val id: String,
    sales: StoredPayments,
    private val cash: CashTenders,
    private val operations: FinancialOperations,
    configured: Flow<Boolean>,
) : ViewModel() {
    private var writing = false
    val state =
        combine(sales.observe(id), configured) { stored, pin ->
            val sale = stored?.sale
            CashHandoverState(
                pin && sale?.cashHandover?.canRecord == true,
                sale?.cashHandedOverAt != null,
                pin,
                sale?.currency ?: PaymentSettings.FALLBACK_CURRENCY,
                sale?.cashHandover?.approvedMinor,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CashHandoverState())

    fun finish() = operations.acknowledgePayment()

    fun confirm() {
        if (writing || !state.value.ready) return
        writing = true
        launchWrite({ cash.handOverCashback(id) }) { writing = false }
    }
}

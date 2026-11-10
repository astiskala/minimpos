package app.minimpos.app.terminal

import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.made
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalSoftwareEligibilityTest {
    private var paymentsSent = 0
    private var rejectDiagnosis = false
    private var onDiagnosis: () -> Unit = {}
    private val terminal =
        FakeTerminal(
            passphrase = "passphrase",
            reply = {
                if (it.saleToPOIRequest.paymentRequest != null) paymentsSent++
                if (it.saleToPOIRequest.diagnosisRequest != null) onDiagnosis()
                if (rejectDiagnosis && it.saleToPOIRequest.diagnosisRequest != null) Delivery.Failed(Fault.Unsupported) else null
            },
        )
    private val env = TestEnvironment(terminal = terminal)
    private val gateway = env.container.gateway
    private val payment = PaymentParams(100, "AUD", "SOFTWARE")

    @After
    fun close() = env.close()

    @Test
    fun `Manager approval is checked after software diagnosis before sending a payment`() {
        env.useLinks()
        var approved = true
        onDiagnosis = { approved = false }
        val tipped = payment.copy(terminalTip = TerminalTipOptions(TerminalTipSuggestions.Percentages(listOf(10))))
        val result = await { gateway.pay(tipped, "APPROVAL", authorized = { approved }) }
        assertThat(result).isEqualTo(Attempt.NotSetUp(SetupProblem.MANAGER_APPROVAL))
        assertThat(paymentsSent).isEqualTo(0)
    }

    @Test
    fun `a mismatched audio guidance terminal is rejected without a diagnosis`() {
        env.useLinks()
        env.updateSettings { it.copy(payment = it.payment.copy(audioGuidance = true)) }
        var diagnoses = 0
        onDiagnosis = { diagnoses++ }
        val result =
            await { gateway.pay(payment.copy(audioGuidance = true), "WRONGPOI", PaymentConfirmations(audioGuidancePoiId = "S1F2-OTHER")) }
        assertThat(result.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        assertThat(diagnoses).isEqualTo(0)
        assertThat(paymentsSent).isEqualTo(0)
        assertThat(terminal.acknowledged).isEmpty()
        (result as Attempt.Made).onStored()
        assertThat(terminal.acknowledged).containsExactly("WRONGPOI")
    }

    @Test
    fun `a connection changed during diagnosis cannot send to the previously opened terminal`() {
        env.useLinks()
        onDiagnosis = { env.updateSettings { it.copy(terminal = it.terminal.copy(host = "192.0.2.11")) } }
        val tipped = payment.copy(terminalTip = TerminalTipOptions(TerminalTipSuggestions.Percentages(listOf(10))))
        val result = await { gateway.pay(tipped, "CHANGED") }
        assertThat(result).isEqualTo(Attempt.NotSetUp(SetupProblem.SETUP_CHANGED))
        assertThat(paymentsSent).isEqualTo(0)
    }

    @Test
    fun `credentials changed during diagnosis cannot send with the previously opened key`() {
        env.useLinks()
        onDiagnosis = { await { env.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "changed") } }
        val tipped = payment.copy(terminalTip = TerminalTipOptions(TerminalTipSuggestions.Percentages(listOf(10))))
        val result = await { gateway.pay(tipped, "KEYCHANGE") }
        assertThat(result).isEqualTo(Attempt.NotSetUp(SetupProblem.SETUP_CHANGED))
        assertThat(paymentsSent).isEqualTo(0)
    }

    @Test
    fun `audio guidance disabled during diagnosis is rejected before requesting approval`() {
        env.useLinks()
        env.updateSettings { it.copy(payment = it.payment.copy(audioGuidance = true)) }
        onDiagnosis = { env.updateSettings { it.copy(payment = it.payment.copy(audioGuidance = false)) } }
        var approvals = 0
        val result =
            await {
                gateway.pay(
                    payment.copy(audioGuidance = true),
                    "AUDIOOFF",
                    PaymentConfirmations(audioGuidancePoiId = "S1F2-000158213605014"),
                    authorized = {
                        approvals++
                        true
                    },
                )
            }
        assertThat(result.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        assertThat(approvals).isEqualTo(0)
        assertThat(paymentsSent).isEqualTo(0)
    }

    @Test
    fun `old unknown and changed software cannot send saved tip overrides but ordinary tipping still works`() {
        env.useLinks()
        val tipped = payment.copy(terminalTip = TerminalTipOptions(TerminalTipSuggestions.Percentages(listOf(10))))
        listOf(null, "unknown", "1.95.1", "1.97.0", "1.100.9").forEachIndexed { index, version ->
            terminal.softwareVersion = version
            assertThat(await { gateway.pay(tipped, "OLD$index") }.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        }
        assertThat(paymentsSent).isEqualTo(0)
        terminal.softwareVersion = "1.101.0"
        assertThat(await { gateway.pay(tipped, "NEW") }.made()).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(paymentsSent).isEqualTo(1)
        rejectDiagnosis = true
        assertThat(await { gateway.pay(tipped, "NODIAG") }.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        assertThat(paymentsSent).isEqualTo(1)
        rejectDiagnosis = false
        terminal.softwareVersion = "1.95.1"
        assertThat(await { gateway.pay(tipped, "DOWNGRADE") }.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        assertThat(paymentsSent).isEqualTo(1)
        assertThat(await { gateway.pay(payment.copy(terminalTip = TerminalTipOptions()), "ORDINARY") }.made())
            .isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(paymentsSent).isEqualTo(2)
    }

    @Test
    fun `audio guidance and Brazilian account selection enforce their own software minimums before sending`() {
        env.useLinks()
        env.updateSettings { it.copy(payment = it.payment.copy(audioGuidance = true)) }
        val poiId = "S1F2-000158213605014"
        terminal.softwareVersion = "1.116.9"
        assertThat(
            await { gateway.pay(payment.copy(audioGuidance = true), "AUDIOOLD", PaymentConfirmations(audioGuidancePoiId = poiId)) }.made(),
        ).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        terminal.softwareVersion = "1.117.0"
        assertThat(
            await { gateway.pay(payment.copy(audioGuidance = true), "AUDIONEW", PaymentConfirmations(audioGuidancePoiId = poiId)) }.made(),
        ).isInstanceOf(TransactionOutcome.Completed::class.java)
        val voucher = payment.copy(currency = "BRL", mealVoucherAccount = true)
        terminal.softwareVersion = "1.128.9"
        assertThat(await { gateway.pay(voucher, "VOUCHEROLD") }.made()).isEqualTo(TransactionOutcome.NotProcessed(Fault.Unsupported))
        assertThat(paymentsSent).isEqualTo(1)
        terminal.softwareVersion = "1.129.0"
        val setup = await { env.container.terminalSetup.unlocked() }
        assertThat(
            await {
                gateway.pay(
                    voucher,
                    "VOUCHERNEW",
                    PaymentConfirmations(expected = setup.setup.paymentContext(), setupIdentity = setup.validationIdentity),
                )
            }.made(),
        ).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(paymentsSent).isEqualTo(2)
    }

    @Test
    fun `diagnosis evidence is scoped to the full destination and discarded when a refresh fails`() {
        env.useLinks()
        await { gateway.diagnose() }
        val first =
            await {
                env.container.terminalStatus.state
                    .first { it.softwareVersion != null }
            }
        assertThat(first.tipOptionsAvailable).isTrue()
        val original = env.container.settingsState.value.terminal
        listOf(
            original.copy(host = "192.0.2.11"),
            original.copy(poiIdOverride = "S1F2-987654321"),
            original.copy(merchantAccount = "OtherMerchant"),
            original.copy(environment = TerminalEnvironment.LIVE),
        ).forEach { target ->
            env.updateSettings { it.copy(terminal = target) }
            await {
                env.container.terminalStatus.state
                    .first { it.softwareVersion == null }
            }
        }
        await { gateway.diagnose() }
        await {
            env.container.terminalStatus.state
                .first { it.softwareVersion != null }
        }
        rejectDiagnosis = true
        await { gateway.diagnose() }
        val unknown =
            await {
                env.container.terminalStatus.state
                    .first { it.softwareVersion == null }
            }
        assertThat(unknown.tipOptionsAvailable).isFalse()
        assertThat(paymentsSent).isEqualTo(0)
    }
}

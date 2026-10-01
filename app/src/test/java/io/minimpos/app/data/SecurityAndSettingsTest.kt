package io.minimpos.app.data

import androidx.datastore.core.CorruptionException
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.AppContainer
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.security.PinCheck
import io.minimpos.app.data.security.PinManager
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStoreException
import io.minimpos.app.data.security.SessionLock
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.JsonDataStoreSerializer
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.terminal.AndroidDeviceInfo
import io.minimpos.core.money.CurrencySpec
import io.minimpos.terminal.client.RecurringModel
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SecurityAndSettingsTest {
    private val env = TestEnvironment()
    private val secrets = env.container.secrets

    @After
    fun tearDown() = env.close()

    @Test
    fun `secrets are stored encrypted and can be cleared`() =
        await {
            secrets.set(Secret.SMTP_PASSWORD, "hunter2")
            assertThat(secrets.get(Secret.SMTP_PASSWORD)).isEqualTo("hunter2")
            assertThat(secrets.configured.first()).containsExactly(Secret.SMTP_PASSWORD)
            assertThat(env.dir.resolve("secrets.json").readText()).doesNotContain("hunter2")
            env.cipher.fail = true
            assertThat(secrets.get(Secret.SMTP_PASSWORD)).isNull()
            env.cipher.fail = false
            // A device that cannot encrypt reports it, and keeps what was stored before.
            env.cipher.failEncrypt = true
            val error = assertThrows(SecretStoreException::class.java) { runBlocking { secrets.set(Secret.SMTP_PASSWORD, "other") } }
            assertThat(error).hasMessageThat().isEqualTo("ProviderException: Keystore unavailable")
            env.cipher.failEncrypt = false
            assertThat(secrets.get(Secret.SMTP_PASSWORD)).isEqualTo("hunter2")
            secrets.set(Secret.SMTP_PASSWORD, "")
            assertThat(secrets.get(Secret.SMTP_PASSWORD)).isNull()
            assertThat(secrets.configured.first()).isEmpty()
        }

    @Test
    fun `pins verify, reject and lock out`() =
        await {
            var now = 0L
            val pins = PinManager(secrets, clock = { now }, iterations = 1_000)
            assertThat(pins.verify("0000")).isEqualTo(PinCheck.Accepted)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { pins.setPin("12") } }
            pins.setPin("1234")
            assertThat(pins.pinConfigured.first()).isTrue()
            assertThat(pins.verify("1234")).isEqualTo(PinCheck.Accepted)
            repeat(4) { assertThat(pins.verify("9999")).isEqualTo(PinCheck.Rejected(4 - it)) }
            val locked = pins.verify("9999") as PinCheck.LockedOut
            assertThat(locked.untilMillis).isEqualTo(PinManager.LOCKOUT_MILLIS)
            assertThat(pins.verify("1234")).isInstanceOf(PinCheck.LockedOut::class.java)
            now = PinManager.LOCKOUT_MILLIS + 1
            repeat(4) { pins.verify("9999") }
            assertThat((pins.verify("9999") as PinCheck.LockedOut).untilMillis).isEqualTo(now + 2 * PinManager.LOCKOUT_MILLIS)
            now += 10 * PinManager.LOCKOUT_MILLIS
            assertThat(pins.verify("1234")).isEqualTo(PinCheck.Accepted)
            secrets.set(Secret.PIN_VERIFIER, "corrupt")
            assertThat(pins.verify("1234")).isInstanceOf(PinCheck.Rejected::class.java)
            pins.clearPin()
            assertThat(pins.pinConfigured.first()).isFalse()
            assertThat(PinManager.isValidPin("123a")).isFalse()
            assertThat(PinManager.isValidPin("123456789")).isFalse()
        }

    @Test
    fun `session lock expires without activity`() {
        var now = 0L
        val lock = SessionLock { now }
        assertThat(lock.touch(1_000)).isFalse()
        lock.unlock()
        now = 500
        assertThat(lock.touch(1_000)).isTrue()
        now = 1_400
        assertThat(lock.touch(1_000)).isTrue()
        now = 10_000
        assertThat(lock.touch(0)).isTrue()
        now = 20_000
        assertThat(lock.touch(1_000)).isFalse()
        assertThat(lock.unlocked.value).isFalse()
        lock.unlock()
        lock.lock()
        assertThat(lock.unlocked.value).isFalse()
    }

    @Test
    fun `settings persist and derived flags follow`() =
        await {
            env.container.settings.update { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.BOTH)) }
            val current = env.container.settings.current()
            assertThat(current.payment.captureEmailBefore).isTrue()
            assertThat(PaymentSettings(emailCapture = EmailCapture.OFF).captureEmailBefore).isFalse()

            // With the email as shopper reference it is always asked for before payment.
            fun byEmail(capture: EmailCapture) =
                PaymentSettings(emailCapture = capture, shopperReferenceSource = ShopperReferenceSource.EMAIL).effectiveEmailCapture
            assertThat(EmailCapture.entries.map(::byEmail))
                .containsExactly(EmailCapture.BEFORE_PAYMENT, EmailCapture.BEFORE_PAYMENT, EmailCapture.BOTH, EmailCapture.BOTH)
                .inOrder()
            assertThat(
                PaymentSettings(emailCapture = EmailCapture.OFF, shopperReferenceSource = ShopperReferenceSource.EMAIL).captureEmailBefore,
            ).isTrue()
            assertThat(
                PaymentSettings(emailCapture = EmailCapture.AFTER_PAYMENT).effectiveEmailCapture,
            ).isEqualTo(EmailCapture.AFTER_PAYMENT)

            // A customer reference is asked for exactly when it is the shopper reference.
            assertThat(PaymentSettings().asksCustomerReference).isTrue()
            assertThat(PaymentSettings(shopperReferenceSource = ShopperReferenceSource.EMAIL).asksCustomerReference).isFalse()
            assertThat(PaymentSettings().referencePrefix).isEmpty()
            assertThat(EmailSettings(host = "smtp", fromAddress = "a@b.co").isConfigured).isTrue()
            assertThat(EmailSettings().isConfigured).isFalse()
        }

    @Test
    fun `currency is the chosen one, else the device country's own`() {
        val auto = PaymentSettings()
        assertThat(auto.resolvedCurrency("AU")).isEqualTo("AUD")
        assertThat(auto.resolvedCurrency("SE")).isEqualTo("SEK")
        assertThat(auto.resolvedCurrency("ZZ")).isEqualTo(PaymentSettings.FALLBACK_CURRENCY)
        // Any Adyen currency can be chosen, whatever the device's country.
        assertThat(PaymentSettings(currencyCode = " jpy ").resolvedCurrency("AU")).isEqualTo("JPY")
        assertThat(PaymentSettings(currencyCode = "BRL").resolvedCurrency("GB")).isEqualTo("BRL")
        // A currency Adyen does not support falls back to the device's own.
        assertThat(PaymentSettings(currencyCode = "XAU").resolvedCurrency("NZ")).isEqualTo("NZD")
        assertThat(AppContainer.resolveCurrency(PaymentSettings(), Locale.JAPAN)).isEqualTo(CurrencySpec("JPY", 0))
        assertThat(AppContainer.resolveCurrency(PaymentSettings(currencyCode = "ISK"), Locale.US).fractionDigits).isEqualTo(2)
        assertThat(PaymentSettings().recurringModel()).isEqualTo(RecurringModel.UNSCHEDULED_CARD_ON_FILE)
        assertThat(PaymentSettings(recurringProcessingModel = "Subscription").recurringModel())
            .isEqualTo(RecurringModel.SUBSCRIPTION)
        assertThat(PaymentSettings.RECURRING_MODELS).containsExactly("Subscription", "CardOnFile", "UnscheduledCardOnFile")
        assertThat(AndroidDeviceInfo.POI_ID.matches("S1F2-000158213605014")).isTrue()
        assertThat(AndroidDeviceInfo.POI_ID.matches("AMS1-12345678901")).isTrue()
        assertThat(AndroidDeviceInfo.POI_ID.matches("Pixel 4a")).isFalse()
    }

    @Test
    fun `serializer round trips and reports corruption`() {
        await {
            val serializer = JsonDataStoreSerializer(AppSettings.serializer(), AppSettings())
            val out = ByteArrayOutputStream()
            val settings = AppSettings(payment = PaymentSettings(currencyCode = "NZD"))
            serializer.writeTo(settings, out)
            assertThat(serializer.readFrom(ByteArrayInputStream(out.toByteArray()))).isEqualTo(settings)
            assertThat(serializer.readFrom(ByteArrayInputStream("{\"unknown\":1}".toByteArray()))).isEqualTo(AppSettings())
            // Settings saved by earlier versions, which also had a country/region and a customer reference switch, still load.
            val older = "{\"payment\":{\"region\":\"JP\",\"currencyCode\":\"USD\",\"askCustomerReference\":false}}"
            val olderPayment = serializer.readFrom(ByteArrayInputStream(older.toByteArray())).payment
            assertThat(olderPayment.currencyCode).isEqualTo("USD")
            // The customer reference is the shopper reference there, so it is asked for whatever the old switch said.
            assertThat(olderPayment.asksCustomerReference).isTrue()
            // Earlier versions chose the environment and stored localhost; both still load.
            val chosen = "{\"terminal\":{\"environment\":\"LIVE\",\"host\":\"localhost\"}}"
            val terminal = serializer.readFrom(ByteArrayInputStream(chosen.toByteArray())).terminal
            assertThat(terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
            assertThat(terminal.host).isEqualTo("localhost")
            assertThat(AppSettings().terminal.environment).isNull()
            assertThat(AppSettings().terminal.host).isEmpty()
            assertThrows(CorruptionException::class.java) { runBlocking { serializer.readFrom(ByteArrayInputStream("{".toByteArray())) } }
            assertThrows(CorruptionException::class.java) {
                runBlocking { serializer.readFrom(ByteArrayInputStream("{\"payment\":[]}".toByteArray())) }
            }
        }
    }
}

package app.minimpos.app.data

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.CorruptionException
import app.minimpos.app.AppContainer
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.security.PinCheck
import app.minimpos.app.data.security.PinManager
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.JsonDataStoreSerializer
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.terminal.AndroidDeviceInfo
import app.minimpos.core.money.CurrencySpec
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun `manager and admin PINs are independent and the Adyen key uses its own name`() =
        await {
            val admin = PinManager(secrets, iterations = 1_000)
            val manager = PinManager(secrets, iterations = 1_000, verifierSecret = Secret.MANAGER_PIN_VERIFIER)
            admin.setPin("1234")
            manager.setPin("2468")
            assertThat(manager.verify("1234")).isInstanceOf(PinCheck.Rejected::class.java)
            assertThat(manager.verify("2468")).isEqualTo(PinCheck.Accepted)
            assertThat(admin.verify("2468")).isInstanceOf(PinCheck.Rejected::class.java)
            manager.clearPin()
            assertThat(admin.pinConfigured.first()).isTrue()
            secrets.set(Secret.ADYEN_API_KEY, "key")
            assertThat(env.dir.resolve("secrets.json").readText()).contains("ADYEN_API_KEY")
            assertThat(secrets.get(Secret.ADYEN_API_KEY)).isEqualTo("key")
        }

    @Test
    fun `an unreadable configured PIN never grants access`() =
        await {
            val pins = PinManager(secrets, iterations = 1_000)
            pins.setPin("1234")
            env.cipher.fail = true
            assertThat(pins.verify("0000")).isNotEqualTo(PinCheck.Accepted)
            assertThat(pins.verify("1234")).isNotEqualTo(PinCheck.Accepted)
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
            assertThat(pins.verify("1234")).isEqualTo(PinCheck.Unreadable)
            pins.clearPin()
            assertThat(pins.pinConfigured.first()).isFalse()
            assertThat(PinManager.isValidPin("123a")).isFalse()
            assertThat(PinManager.isValidPin("123456789")).isFalse()
        }

    @Test
    fun `inactivity expiry does not refresh the last interaction`() {
        var now = 0L
        val lock = SessionLock { now }
        lock.unlock()
        now = 500
        lock.expire(1_000)
        assertThat(lock.unlocked.value).isTrue()
        now = 1_000
        lock.expire(1_000)
        assertThat(lock.unlocked.value).isFalse()
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
            env.container.settings.update { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT)) }
            val current = env.container.settings.current()
            assertThat(current.payment.captureEmailBefore).isTrue()
            assertThat(PaymentSettings(emailCapture = EmailCapture.OFF).captureEmailBefore).isFalse()

            // With the email as shopper reference it is always asked for before payment.
            fun byEmail(capture: EmailCapture) =
                PaymentSettings(emailCapture = capture, shopperReferenceSource = ShopperReferenceSource.EMAIL).effectiveEmailCapture
            assertThat(EmailCapture.entries.map(::byEmail))
                .containsExactly(EmailCapture.BEFORE_PAYMENT, EmailCapture.BEFORE_PAYMENT, EmailCapture.BEFORE_PAYMENT)
                .inOrder()
            assertThat(
                PaymentSettings(emailCapture = EmailCapture.OFF, shopperReferenceSource = ShopperReferenceSource.EMAIL).captureEmailBefore,
            ).isTrue()
            assertThat(
                PaymentSettings(emailCapture = EmailCapture.AFTER_PAYMENT).effectiveEmailCapture,
            ).isEqualTo(EmailCapture.AFTER_PAYMENT)

            // A customer reference is asked for exactly when it is the shopper reference.
            assertThat(PaymentSettings().asksCustomerReference).isTrue()
            assertThat(PaymentSettings(shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE).asksCustomerReference).isTrue()
            assertThat(PaymentSettings(shopperReferenceSource = ShopperReferenceSource.EMAIL).asksCustomerReference).isFalse()
            assertThat(PaymentSettings().referencePrefix).isEmpty()
            assertThat(EmailSettings(host = "smtp", fromAddress = "a@b.co").isConfigured).isTrue()
            assertThat(EmailSettings().isConfigured).isFalse()
        }

    @Test
    fun `app language cannot change the device country used for automatic pricing`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(LocaleManager::class.java)
        val originalLocales = manager.applicationLocales
        val originalLocale = Locale.getDefault()
        val device = AndroidDeviceInfo(application)
        val country = device.country
        try {
            manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertThat(device.country).isEqualTo(country)
        } finally {
            manager.applicationLocales = originalLocales
            Locale.setDefault(originalLocale)
        }
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
            val settings =
                AppSettings(
                    payment = PaymentSettings(currencyCode = "NZD"),
                    receipt =
                        ReceiptSettings(
                            showTaxAmounts = false,
                            showTaxRateTotals = true,
                            markedTaxRateMilliPercent = 5_500,
                            markedTaxRateMarker = "*",
                            markedTaxRateNote = "* Reduced rate",
                        ),
                )
            serializer.writeTo(settings, out)
            assertThat(serializer.readFrom(ByteArrayInputStream(out.toByteArray()))).isEqualTo(settings)
            assertThat(serializer.readFrom(ByteArrayInputStream("{\"unknown\":1}".toByteArray()))).isEqualTo(AppSettings())
            val minimal = "{\"payment\":{\"currencyCode\":\"USD\"}}"
            val payment = serializer.readFrom(ByteArrayInputStream(minimal.toByteArray())).payment
            assertThat(payment.currencyCode).isEqualTo("USD")
            assertThat(payment.asksCustomerReference).isTrue()
            ShopperReferenceSource.entries.forEach { source ->
                val saved = """{"payment":{"shopperReferenceSource":"$source"}}"""
                val restored = serializer.readFrom(ByteArrayInputStream(saved.toByteArray()))
                assertThat(restored.payment.shopperReferenceSource).isEqualTo(source)
                val stored = ByteArrayOutputStream()
                serializer.writeTo(restored, stored)
                assertThat(serializer.readFrom(ByteArrayInputStream(stored.toByteArray()))).isEqualTo(restored)
            }
            assertThat(payment.askTransactionReference).isFalse()
            assertThat(AppSettings().terminal.environment).isNull()
            assertThat(AppSettings().terminal.host).isEmpty()
            assertThrows(CorruptionException::class.java) { runBlocking { serializer.readFrom(ByteArrayInputStream("{".toByteArray())) } }
            assertThrows(CorruptionException::class.java) {
                runBlocking { serializer.readFrom(ByteArrayInputStream("{\"payment\":[]}".toByteArray())) }
            }
        }
    }
}

package app.minimpos.app

import android.content.res.Configuration
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.JsonDataStoreSerializer
import app.minimpos.app.receipt.ActionResult
import app.minimpos.app.terminal.AndroidDeviceInfo
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.serializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rHK")
class AppLanguageTest {
    @get:Rule
    val env = TestEnvironment(device = FakeDevice(country = "HK"))

    @Test
    @Config(sdk = [28, 33])
    fun `language resources work on terminals and newer Android without changing global locale or country`() {
        val originalLocale = Locale.getDefault()
        val originalConfiguration = Configuration(env.context.resources.configuration)
        val device = AndroidDeviceInfo(env.context)
        val country = device.country
        mapOf("en" to "Settings", "zh-CN" to "设置", "zh-Hant" to "設定", "ja" to "設定").forEach { (tag, label) ->
            val localized = env.context.withAppLanguage(tag)
            assertWithMessage(tag).that(localized.getString(R.string.settings_title)).isEqualTo(label)
            assertWithMessage(tag)
                .that(
                    localized.resources.configuration.locales[0]
                        .country,
                ).isEqualTo("HK")
            assertWithMessage(tag).that(Locale.getDefault()).isEqualTo(originalLocale)
            assertWithMessage(tag).that(device.country).isEqualTo(country)
            assertWithMessage(tag).that(env.context.resources.configuration).isEqualTo(originalConfiguration)
        }
        assertThat(env.context.withAppLanguage("")).isSameInstanceAs(env.context)
    }

    @Test
    fun `language is persisted without changing merchant text payment settings or automatic currency`() =
        await {
            val original = env.container.settings.current()
            env.updateSettings { it.copy(languageTag = "zh-Hant") }
            val stored =
                JsonDataStoreSerializer(serializer<AppSettings>(), AppSettings())
                    .readFrom(ByteArrayInputStream(env.dir.resolve("settings.json").readBytes()))
            assertThat(stored).isEqualTo(original.copy(languageTag = "zh-Hant"))
            assertThat(env.container.currency(stored).code).isEqualTo("HKD")
            val document = env.container.sampleReceipt(stored)
            val text = PlainTextReceiptRenderer().render(document)
            assertThat(text).contains("總計")
            assertThat(text).contains("HK$12.00")
            assertThat(text).contains(original.receipt.title)
            assertThat(text).contains(original.receipt.footer)
            assertThat(text).doesNotContain("稅額")
            env.updateSettings { it.copy(languageTag = "ja") }
            assertThat(PlainTextReceiptRenderer().render(env.container.sampleReceipt(env.container.settings.current()))).contains("合計")
            assertThat(env.container.currency().code).isEqualTo("HKD")
        }

    @Test
    @Config(qualifiers = "zh-rHK")
    fun `English can be selected on a Traditional Chinese device`() {
        assertThat(env.context.getString(R.string.settings_title)).isEqualTo("設定")
        assertThat(env.context.withAppLanguage("en").getString(R.string.settings_title)).isEqualTo("Settings")
        assertThat(env.context.withAppLanguage("").getString(R.string.settings_title)).isEqualTo("設定")
    }

    @Test
    fun `delivered receipt labels and email subjects use the override rather than the system language`() =
        await {
            env.updateSettings {
                it.copy(languageTag = "zh-Hant", email = it.email.copy(host = "smtp.example.com", fromAddress = "shop@example.com"))
            }
            val settings = env.container.settings.current()
            val receipt =
                env.container.receiptFactory.sample(
                    settings.receipt,
                    env.container.currency(settings),
                    settings.payment.taxMode,
                    settings.payment.asksCustomerReference,
                )
            assertThat(PlainTextReceiptRenderer().render(receipt)).contains("總計")
            assertThat(env.container.receipts.sendTestEmail("customer@example.com")).isEqualTo(ActionResult.Success)
            assertThat(
                env.mail.sent
                    .single()
                    .subject,
            ).isEqualTo("Mini mPOS 測試郵件")
            env.updateSettings { it.copy(languageTag = "") }
            assertThat(env.container.receipts.sendTestEmail("customer@example.com")).isEqualTo(ActionResult.Success)
            assertThat(
                env.mail.sent
                    .last()
                    .subject,
            ).isEqualTo("Mini mPOS test email")
        }

    @Test
    fun `receipt preview uses the supplied language even before settings have been stored`() =
        await {
            val settings = env.container.settings.current()
            val text = PlainTextReceiptRenderer().render(env.container.sampleReceipt(settings.copy(languageTag = "zh-Hant")))
            assertThat(text).contains("總計")
            assertThat(text).contains("自訂商品")
            assertThat(env.container.settings.current()).isEqualTo(settings)
        }
}

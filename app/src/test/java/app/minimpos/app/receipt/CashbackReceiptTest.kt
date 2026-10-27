package app.minimpos.app.receipt

import android.content.res.Configuration
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.security.Secret
import app.minimpos.app.history.ReceiptStanding
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import javax.mail.Multipart
import javax.mail.Part

@RunWith(RobolectricTestRunner::class)
class CashbackReceiptTest {
    @get:Rule val env = TestEnvironment()
    private val cashout =
        SaleEntity(
            "cashout",
            createdAt = 100,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 0,
            taxMinor = 0,
            totalMinor = 0,
            status = SaleStatus.APPROVED,
            merchantReference = "CASHOUT-1",
            terminalPaidMinor = 500,
            cashBackMinor = 500,
            requestedCashBackMinor = 500,
            surchargeMinor = 0,
        )

    @Test
    fun `cashout receipts in all seven languages distinguish approved money from physical handover`() {
        await { env.container.sales.createPending(cashout, emptyList()) }
        listOf("en", "es-MX", "fr-FR", "ja", "pt-BR", "zh-CN", "zh-Hant").forEach { tag ->
            env.updateSettings { it.copy(languageTag = tag) }
            val context =
                env.context.createConfigurationContext(
                    Configuration(env.context.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(tag))
                    },
                )
            val record = await { checkNotNull(env.container.sales.get(cashout.id)) }
            val document = env.container.receiptFactory.sale(record, env.container.settingsState.value.receipt)
            val text = PlainTextReceiptRenderer(80).render(document)
            assertThat(ReceiptKind.of(ReceiptStanding.of(record.sale))).isEqualTo(ReceiptKind.CASHOUT)
            listOf(
                R.string.cashout_title,
                R.string.cashback_requested,
                R.string.cashback_approved,
                R.string.cashback_card_charge,
                R.string.cashback_not_handed_over,
            ).forEach {
                assertThat(text).contains(context.getString(it))
            }
            assertThat(document.qrCodes).isEmpty()
            assertThat(await { checkNotNull(env.container.sales.get(cashout.id)).sale.cashHandedOverAt }).isNull()
        }
    }

    @Test
    fun `email uses the cashout introduction without recording cash handover`() {
        env.updateSettings {
            it.copy(
                email =
                    it.email.copy(
                        host = "smtp.example.com",
                        fromAddress = "shop@example.com",
                        username = "user",
                    ),
            )
        }
        await {
            env.container.secrets.set(Secret.SMTP_PASSWORD, "pw")
            env.container.sales.createPending(cashout, emptyList())
        }
        assertThat(await { env.container.receipts.email(StoredTransaction.Sale(cashout.id), "a@b.co") }).isEqualTo(ActionResult.Success)
        assertThat(
            env.mail.sent
                .single()
                .allText(),
        ).contains("Here is your cashout receipt.")
        assertThat(await { checkNotNull(env.container.sales.get(cashout.id)).sale.cashHandedOverAt }).isNull()
    }

    private fun Part.allText(): String =
        when (val body = content) {
            is String -> body
            is Multipart -> (0..<body.count).joinToString("\n") { body.getBodyPart(it).allText() }
            else -> ""
        }
}

package app.minimpos.app.data

import app.minimpos.app.data.settings.InstallationText
import app.minimpos.app.data.settings.NewInstallation
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** [NewInstallation]: every regional default decided once, worded through its text adapter. */
class NewInstallationTest {
    private fun installation(
        country: String,
        language: String = "",
    ) = NewInstallation(country, language) { it.name }

    @Test
    fun `each country names its tax ID and standard rate once`() {
        mapOf(
            "AU" to (InstallationText.TAX_ID_ABN to InstallationText.TAX_GST),
            "NZ" to (InstallationText.TAX_ID_GST to InstallationText.TAX_GST),
            "SG" to (InstallationText.TAX_ID_GST_REGISTRATION to InstallationText.TAX_GST),
            "MY" to (InstallationText.TAX_ID_SST_REGISTRATION to null),
            "CA" to (InstallationText.TAX_ID_GST_HST to null),
            "MX" to (InstallationText.TAX_ID_RFC to InstallationText.TAX_IVA),
            "BR" to (InstallationText.TAX_ID_CPF_CNPJ to null),
            " gb " to (InstallationText.TAX_ID_VAT to InstallationText.TAX_VAT),
            "HK" to (InstallationText.TAX_ID_DEFAULT to null),
            "" to (InstallationText.TAX_ID_DEFAULT to null),
        ).forEach { (country, expected) ->
            val (label, rate) = expected
            val installation = installation(country)
            assertWithMessage(country).that(installation.settings.receipt.taxIdLabel).isEqualTo(label.name)
            val rates = installation.starterTaxRates
            assertWithMessage(country).that(rates.last().name).isEqualTo(InstallationText.TAX_ZERO.name)
            assertWithMessage(country).that(rates.last().rateMilliPercent).isEqualTo(0)
            if (rate != null) assertWithMessage(country).that(rates.first().name).isEqualTo(rate.name)
        }
    }

    @Test
    fun `Australia marks GST-free items once, unless the receipt follows the Japanese style`() {
        val english = installation("AU").settings.receipt
        assertThat(english.markedTaxRateMilliPercent).isEqualTo(0)
        assertThat(english.markedTaxRateNote).isEqualTo(InstallationText.NO_TAX_NOTE.name)
        val japanese = installation("AU", "ja").settings.receipt
        assertThat(japanese.markedTaxRateMilliPercent).isEqualTo(8_000)
        assertThat(japanese.markedTaxRateNote).isEqualTo(InstallationText.TAX_MARKER_NOTE.name)
        assertThat(installation("GB").settings.receipt.markedTaxRateMilliPercent).isNull()
    }

    @Test
    fun `texts come from the adapter and pricing from the country`() {
        val settings = installation("US").settings
        assertThat(settings.receipt.title).isEqualTo(InstallationText.RECEIPT_TITLE.name)
        assertThat(settings.receipt.footer).isEqualTo(InstallationText.RECEIPT_FOOTER.name)
        assertThat(settings.email.subject).isEqualTo(InstallationText.EMAIL_SUBJECT.name)
        assertThat(settings.payment.taxMode).isEqualTo(TaxMode.EXCLUSIVE)
        assertThat(installation("HK").settings.payment.chargeTax).isFalse()
        assertThat(installation("HK").starterTaxRates.single().rateMilliPercent).isEqualTo(0)
        assertThat(installation("US").sampleTaxName).isEqualTo(InstallationText.SAMPLE_TAX.name)
        assertThat(installation("DE").sampleTaxName).isEqualTo(InstallationText.TAX_VAT.name)
        assertThat(installation("AU").zeroRateName).isEqualTo(InstallationText.TAX_ZERO.name)
    }
}

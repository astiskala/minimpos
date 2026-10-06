package io.github.astiskala.minimpos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.feature.settings.TaxRateForm
import io.github.astiskala.minimpos.app.feature.settings.TaxRateFormState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

/** The tax dialog's content, tested on its own: Robolectric never idles with a text field inside a dialog window. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class TaxRateFormTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `names come from quick picks or typing, rates take up to three decimals`() {
        val form = TaxRateFormState(TaxRateEntity(name = "", rateMilliPercent = 0, sortOrder = 3), isDefault = false)
        compose.setContent { MaterialTheme { TaxRateForm(form) } }

        listOf(
            "GST",
            "VAT",
            "SST",
            "IVA",
            "Consumption tax",
            "Sales tax",
        ).forEach { compose.onNodeWithTag("taxSuggestion_$it").assertExists() }
        compose.onNodeWithTag("taxSuggestion_VAT").performClick()
        compose.onNodeWithTag("taxSuggestion_VAT").assertIsSelected()
        compose.onNodeWithTag("taxName").assertTextContains("VAT")
        assertThat(form.valid).isFalse()

        compose.onNodeWithText("e.g. 5, 8.1 or 25.5").assertExists()
        compose.onNodeWithTag("taxRate").performTextInput("25.5555")
        compose.onNodeWithText("Enter 0 to 100, with at most 3 decimals").assertExists()
        assertThat(form.valid).isFalse()
        compose.onNodeWithTag("taxRate").performTextReplacement("25,5")
        compose.onNode(hasText("Shown as “VAT 25.5%” on receipts")).assertExists()
        assertThat(form.valid).isTrue()

        compose.onNodeWithTag("taxName").performTextReplacement(" Sales tax NYC ")
        compose.onNodeWithTag("taxRate").performTextReplacement("8.875")
        compose.onNodeWithText("Default for new products and custom items").performClick()
        assertThat(form.toEntity()).isEqualTo(TaxRateEntity(name = "Sales tax NYC", rateMilliPercent = 8_875, sortOrder = 3))
        assertThat(form.makeDefault).isTrue()

        compose.onNodeWithTag("taxRate").performTextReplacement("100.5")
        assertThat(form.valid).isFalse()
    }

    @Test
    fun `Other clears a preset label and preserves custom labels when chosen again`() {
        val form = TaxRateFormState(TaxRateEntity(name = "VAT", rateMilliPercent = 20_000), isDefault = false)
        compose.setContent { MaterialTheme { TaxRateForm(form) } }
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsNotSelected().performClick()
        compose.onNodeWithTag("taxName").assertTextContains("")
        assertThat(form.name).isEmpty()
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsSelected()
        compose.onNodeWithTag("taxName").performTextInput("Local levy")
        compose.onNodeWithTag("taxSuggestion_OTHER").performClick()
        assertThat(form.name).isEqualTo("Local levy")
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsSelected()
        compose.onNodeWithTag("taxSuggestion_GST").performClick()
        compose.onNodeWithTag("taxSuggestion_GST").assertIsSelected()
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsNotSelected()
        compose.onNodeWithTag("taxName").performTextReplacement("City tax")
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsSelected()
    }

    @Test
    fun `custom labels select Other and blank labels leave it unselected`() {
        val form = TaxRateFormState(TaxRateEntity(name = "City tax", rateMilliPercent = 5_000), isDefault = false)
        compose.setContent { MaterialTheme { TaxRateForm(form) } }
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsSelected()
        compose.onNodeWithTag("taxName").performTextReplacement("")
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsNotSelected().performClick()
        compose.onNodeWithTag("taxSuggestion_OTHER").assertIsSelected()
        assertThat(form.name).isEmpty()
    }

    @Test
    @Config(qualifiers = "ja-rJP-w411dp-h891dp-xhdpi")
    fun `Other is localized in Japanese and never becomes the tax name`() {
        val form = TaxRateFormState(TaxRateEntity(name = "GST", rateMilliPercent = 10_000), isDefault = false)
        compose.setContent { MaterialTheme { TaxRateForm(form) } }
        compose.onNodeWithTag("taxSuggestion_OTHER").assertTextContains("その他").performClick()
        assertThat(form.name).isEmpty()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun `Other is localized in Chinese and preserves an existing custom name`() {
        val form = TaxRateFormState(TaxRateEntity(name = "地方税", rateMilliPercent = 5_000), isDefault = false)
        compose.setContent { MaterialTheme { TaxRateForm(form) } }
        compose
            .onNodeWithTag("taxSuggestion_OTHER")
            .assertTextContains("其他")
            .assertIsSelected()
            .performClick()
        assertThat(form.name).isEqualTo("地方税")
        compose.onNodeWithTag("taxSuggestion_增值税").performClick()
        compose.onNodeWithTag("taxSuggestion_增值税").assertIsSelected()
    }

    @Test
    fun `existing rates start with their values`() {
        val form = TaxRateFormState(TaxRateEntity(id = 7, name = "SST", rateMilliPercent = 8_000), isDefault = true)
        assertThat(form.name).isEqualTo("SST")
        assertThat(form.rate).isEqualTo("8")
        assertThat(form.makeDefault).isTrue()
        assertThat(form.valid).isTrue()
    }
}

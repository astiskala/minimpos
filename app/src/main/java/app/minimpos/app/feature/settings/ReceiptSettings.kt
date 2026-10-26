package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.MerchantCopyPolicy
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.OutcomeDialog
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.core.tax.TaxRates

/** Receipt content and printing, including reviewed business import and the live sample. */
@Composable
internal fun ColumnScope.ReceiptsSection(
    state: SettingsUiState,
    actions: SettingsActions,
    businessImport: ReceiptBusinessImportState,
    events: SettingsEvents,
) {
    val receipt = state.settings.receipt
    val terminal by LocalAppContainer.current.terminalStatus.state
        .collectAsStateWithLifecycle()
    val printBlocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { terminal.capabilities.supportsPrinting }

    fun update(transform: (ReceiptSettings) -> ReceiptSettings) = events.onUpdate { it.copy(receipt = transform(it.receipt)) }
    ReceiptBusinessImport(
        businessImport,
        events.businessImport,
        receipt.businessName.isNotBlank() || receipt.addressLines.isNotBlank() || receipt.phone.isNotBlank(),
    )
    ReceiptLogoSettings()
    key(businessImport.revision) { ReceiptTextSettings(receipt, ::update) }
    SectionHeader(stringResource(R.string.settings_content))
    ReceiptTaxSettings(receipt, ::update)
    SettingSwitch(stringResource(R.string.settings_show_references), receipt.showReferences, { value ->
        update { it.copy(showReferences = value) }
    }, subtitle = stringResource(R.string.settings_show_references_hint))
    SettingSwitch(
        stringResource(R.string.settings_show_qr),
        receipt.showRefundQr,
        { value -> update { it.copy(showRefundQr = value) } },
        subtitle = stringResource(R.string.settings_show_qr_hint),
    )
    ReceiptPrintingSettings(receipt, printBlocked, ::update)
    SectionHeader(stringResource(R.string.settings_preview))
    state.sampleReceipt?.let { document ->
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ReceiptPreview(document) }
    }
    SettingActions {
        GatedButton(
            stringResource(R.string.settings_print_test),
            events::onTestPrint,
            blocked = terminal.printBlocked?.let(Failure::NotSetUp),
            loading = actions.print.running,
            icon = Icons.Default.Print,
            requiresSetup = true,
            modifier = Modifier.testTag("printTest"),
        )
    }
    OutcomeDialog(R.string.settings_print_test, actions.print, "testPrintResult") { events.onTestResultDismiss(SettingsResult.PRINT) }
}

@Composable
private fun ColumnScope.ReceiptTaxSettings(
    receipt: ReceiptSettings,
    update: ((ReceiptSettings) -> ReceiptSettings) -> Unit,
) {
    SettingSwitch(stringResource(R.string.settings_show_tax_amounts), receipt.showTaxAmounts, { value ->
        update { it.copy(showTaxAmounts = value) }
    }, subtitle = stringResource(R.string.settings_show_tax_amounts_hint), tag = "showTaxAmounts")
    if (receipt.showTaxAmounts) {
        SettingSwitch(stringResource(R.string.settings_show_tax), receipt.showTaxBreakdown, { value ->
            update { it.copy(showTaxBreakdown = value) }
        }, subtitle = stringResource(R.string.settings_show_tax_hint), tag = "showTaxBreakdown")
    }
    SettingSwitch(stringResource(R.string.settings_show_tax_rate_totals), receipt.showTaxRateTotals, { value ->
        update { it.copy(showTaxRateTotals = value) }
    }, subtitle = stringResource(R.string.settings_show_tax_rate_totals_hint), tag = "showTaxRateTotals")
    SettingTextField(
        label = stringResource(R.string.settings_marked_tax_rate),
        value = receipt.markedTaxRateMilliPercent?.let(TaxRates::format).orEmpty(),
        onCommit = { value -> update { it.copy(markedTaxRateMilliPercent = TaxRates.parse(value)) } },
        supporting = stringResource(R.string.settings_marked_tax_rate_hint),
        isError = { it.isNotBlank() && TaxRates.parse(it) == null },
        keyboardType = KeyboardType.Decimal,
        tag = "markedTaxRate",
    )
    if (receipt.markedTaxRateMilliPercent != null) {
        SettingTextField(stringResource(R.string.settings_tax_marker), receipt.markedTaxRateMarker, { value ->
            update { it.copy(markedTaxRateMarker = value) }
        }, isError = { it.isBlank() }, tag = "taxMarker")
        SettingTextField(stringResource(R.string.settings_tax_marker_note), receipt.markedTaxRateNote, { value ->
            update { it.copy(markedTaxRateNote = value) }
        }, tag = "taxMarkerNote")
    }
}

/** The business details, title and footer printed on every receipt. */
@Composable
private fun ColumnScope.ReceiptTextSettings(
    receipt: ReceiptSettings,
    update: ((ReceiptSettings) -> ReceiptSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_header_footer))
    SettingTextField(stringResource(R.string.settings_business_name), receipt.businessName, { value ->
        update { it.copy(businessName = value) }
    }, tag = "businessName")
    SettingSwitch(
        stringResource(R.string.settings_print_business_name),
        receipt.printBusinessName,
        { value -> update { it.copy(printBusinessName = value) } },
        subtitle = stringResource(R.string.settings_print_business_name_hint),
        tag = "printBusinessName",
    )
    SettingTextField(stringResource(R.string.settings_address), receipt.addressLines, { value ->
        update { it.copy(addressLines = value) }
    }, singleLine = false)
    SettingTextField(stringResource(R.string.settings_tax_id_label), receipt.taxIdLabel, { value ->
        update { it.copy(taxIdLabel = value) }
    })
    SettingTextField(stringResource(R.string.settings_tax_id), receipt.taxId, { value -> update { it.copy(taxId = value) } })
    SettingTextField(stringResource(R.string.settings_phone), receipt.phone, { value ->
        update { it.copy(phone = value) }
    }, keyboardType = KeyboardType.Phone)
    SettingTextField(stringResource(R.string.settings_receipt_title), receipt.title, { value -> update { it.copy(title = value) } })
    SettingTextField(stringResource(R.string.settings_footer), receipt.footer, { value ->
        update { it.copy(footer = value) }
    }, singleLine = false)
}

/** Whether and when receipts print, the merchant copy, and the line width. */
@Composable
private fun ColumnScope.ReceiptPrintingSettings(
    receipt: ReceiptSettings,
    blocked: String?,
    update: ((ReceiptSettings) -> ReceiptSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_printing))
    SettingChoice(
        title = stringResource(R.string.settings_printer),
        options =
            listOf(
                PrinterMode.AUTO to stringResource(R.string.settings_printer_auto),
                PrinterMode.ON to stringResource(R.string.settings_printer_on),
                PrinterMode.OFF to stringResource(R.string.settings_printer_off),
            ),
        selected = receipt.printerMode,
        onSelect = { mode -> update { it.copy(printerMode = mode) } },
        subtitle = stringResource(R.string.settings_printer_hint),
        tag = "receiptPrinter",
        blocked = blocked,
    )
    SettingSwitch(stringResource(R.string.settings_auto_print), receipt.autoPrint, { value ->
        update { it.copy(autoPrint = value) }
    }, subtitle = stringResource(R.string.settings_auto_print_hint), tag = "autoPrint", blocked = blocked)
    SettingChoice(
        title = stringResource(R.string.settings_merchant_copy),
        options =
            listOf(
                MerchantCopyPolicy.NEVER to stringResource(R.string.settings_merchant_never),
                MerchantCopyPolicy.SIGNATURE_ONLY to stringResource(R.string.settings_merchant_signature),
                MerchantCopyPolicy.ALWAYS to stringResource(R.string.settings_merchant_always),
            ),
        selected = receipt.merchantCopy,
        onSelect = { policy -> update { it.copy(merchantCopy = policy) } },
        subtitle = stringResource(R.string.settings_merchant_copy_hint),
        tag = "merchantCopy",
        blocked = blocked,
    )
    SettingNumberField(
        stringResource(R.string.settings_chars_per_line),
        receipt.charsPerLine,
        ReceiptSettings.CHARS_PER_LINE,
        { chars -> update { it.copy(charsPerLine = chars) } },
        supporting = stringResource(R.string.settings_chars_per_line_hint),
        tag = "charsPerLine",
    )
}

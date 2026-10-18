package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.core.shopper.EmailReferenceMode

/** Currency, payment references, card-entry offers, card saving and link expiry; no connection setup or receipt delivery. */
@Composable
internal fun ColumnScope.PaymentsSection(
    state: SettingsUiState,
    events: SettingsEvents,
) {
    val payment = state.settings.payment

    fun update(transform: (PaymentSettings) -> PaymentSettings) = events.onUpdate { it.copy(payment = transform(it.payment)) }
    SectionHeader(stringResource(R.string.settings_pricing), Modifier.testTag("pricingHeader"))
    CurrencySetting(
        selectedCode = payment.currencyCode,
        automatic = PaymentSettings().resolvedCurrency(LocalAppContainer.current.device.country),
        onSelect = { code -> update { it.copy(currencyCode = code) } },
    )
    SectionHeader(stringResource(R.string.settings_references))
    SettingTextField(
        stringResource(R.string.settings_reference_prefix),
        payment.referencePrefix,
        { value -> update { it.copy(referencePrefix = value.trim()) } },
        supporting = stringResource(R.string.settings_reference_prefix_hint),
        autoCorrect = false,
    )
    SettingSwitch(stringResource(R.string.settings_ask_transaction_reference), payment.askTransactionReference, { value ->
        update { it.copy(askTransactionReference = value) }
    }, subtitle = stringResource(R.string.settings_ask_transaction_reference_hint))
    SectionHeader(stringResource(R.string.settings_card_payments))
    SettingSwitch(
        stringResource(R.string.settings_allow_partial_auth),
        payment.allowPartialAuthorisation,
        { value -> update { it.copy(allowPartialAuthorisation = value) } },
        subtitle = stringResource(R.string.settings_allow_partial_auth_hint),
        tag = "allowPartialAuthorisation",
    )
    SettingSwitch(
        stringResource(R.string.settings_allow_moto),
        payment.allowMoto,
        { value -> update { it.copy(allowMoto = value) } },
        subtitle = stringResource(R.string.settings_allow_moto_hint),
        tag = "allowMoto",
    )
    SettingSwitch(
        stringResource(R.string.settings_allow_mke),
        payment.allowMke,
        { value -> update { it.copy(allowMke = value) } },
        subtitle = stringResource(R.string.settings_allow_mke_hint),
        tag = "allowMke",
    )
    SectionHeader(stringResource(R.string.settings_tokenization))
    ShopperReferenceSettings(payment, ::update)
    SectionHeader(stringResource(R.string.settings_payment_links))
    PaymentLinkSettings(payment, ::update)
}

/** How long payment links remain payable; API readiness belongs to Terminal settings. */
@Composable
private fun ColumnScope.PaymentLinkSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingNote(stringResource(R.string.settings_links_hint))
    SettingNumberField(
        stringResource(R.string.settings_link_expiry),
        payment.linkExpiryHours,
        PaymentSettings.LINK_EXPIRY_HOURS,
        { hours -> update { it.copy(linkExpiryHours = hours) } },
        supporting = stringResource(R.string.settings_link_expiry_hint),
        tag = "linkExpiry",
    )
}

/** Shopper reference source, email hashing and the dependent card-saving options. */
@Composable
private fun ColumnScope.ShopperReferenceSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val source = payment.shopperReferenceSource
    ShopperReferenceSourceChoice(source) { choice -> update { it.copy(shopperReferenceSource = choice) } }
    if (source == ShopperReferenceSource.NONE) return
    if (source == ShopperReferenceSource.EMAIL) EmailReferenceSettings(payment, update)
    CardSavingSettings(payment, update)
}

/** How the shopper's email becomes the shopper reference: hashed (with its salt) or the raw address. */
@Composable
private fun ColumnScope.EmailReferenceSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingChoice(
        title = stringResource(R.string.settings_email_reference_mode),
        options =
            listOf(
                EmailReferenceMode.HASHED to stringResource(R.string.settings_email_hashed),
                EmailReferenceMode.RAW to stringResource(R.string.settings_email_raw),
            ),
        selected = payment.emailReferenceMode,
        onSelect = { mode -> update { it.copy(emailReferenceMode = mode) } },
        subtitle = stringResource(R.string.settings_email_reference_hint),
    )
    if (payment.emailReferenceMode == EmailReferenceMode.HASHED) {
        SettingTextField(
            stringResource(R.string.settings_email_salt),
            payment.emailReferenceSalt,
            { value -> update { it.copy(emailReferenceSalt = value) } },
            supporting = stringResource(R.string.settings_email_salt_hint),
        )
    }
}

/** Whether checkout offers card saving, its initial selections and recurring processing model. */
@Composable
private fun ColumnScope.CardSavingSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.settings_offer_card_saving),
        payment.offerCardSaving,
        { value -> update { it.copy(offerCardSaving = value) } },
        subtitle = stringResource(R.string.settings_offer_card_saving_hint),
        tag = "offerCardSaving",
    )
    if (!payment.offerCardSaving) return
    SaveCardDefaults(payment, update)
    SettingChoice(
        title = stringResource(R.string.settings_recurring_model),
        options =
            PaymentSettings.RECURRING_MODELS.map {
                it to
                    stringResource(
                        when (it) {
                            "CardOnFile" -> R.string.recurring_card_on_file
                            "Subscription" -> R.string.recurring_subscription
                            else -> R.string.recurring_unscheduled
                        },
                    )
            },
        selected = payment.recurringProcessingModel,
        onSelect = { model -> update { it.copy(recurringProcessingModel = model) } },
        subtitle = stringResource(R.string.settings_recurring_model_hint),
    )
}

/** What supplies the shopper reference; checkout asks for a customer reference only when selected here. */
@Composable
private fun ShopperReferenceSourceChoice(
    source: ShopperReferenceSource,
    onSelect: (ShopperReferenceSource) -> Unit,
) = SettingChoice(
    title = stringResource(R.string.settings_shopper_reference_source),
    options =
        listOf(
            ShopperReferenceSource.NONE to stringResource(R.string.settings_source_none),
            ShopperReferenceSource.CUSTOMER_REFERENCE to stringResource(R.string.settings_source_customer),
            ShopperReferenceSource.EMAIL to stringResource(R.string.settings_source_email),
        ),
    selected = source,
    onSelect = onSelect,
    subtitle =
        stringResource(
            when (source) {
                ShopperReferenceSource.NONE -> R.string.settings_source_none_hint
                ShopperReferenceSource.CUSTOMER_REFERENCE -> R.string.settings_source_customer_hint
                ShopperReferenceSource.EMAIL -> R.string.settings_source_email_hint
            },
        ),
    tag = "referenceSource",
)

/** Whether "Save card" starts switched on at checkout, for sales and for pre-authorisations. */
@Composable
private fun ColumnScope.SaveCardDefaults(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.settings_tokenize_default),
        payment.tokenizeDefaultOn,
        { value -> update { it.copy(tokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_tokenize_default_hint),
        tag = "tokenizeDefault",
    )
    SettingSwitch(
        stringResource(R.string.settings_pre_auth_tokenize_default),
        payment.preAuthTokenizeDefaultOn,
        { value -> update { it.copy(preAuthTokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_pre_auth_tokenize_default_hint),
        tag = "preAuthTokenizeDefault",
    )
}

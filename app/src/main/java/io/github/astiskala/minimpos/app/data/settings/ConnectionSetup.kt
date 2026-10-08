package io.github.astiskala.minimpos.app.data.settings

import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where payments go, as the setup helper web page names it (its own names, so either side can rename its values). */
@Serializable
enum class ConnectionDestination {
    /** Mini mPOS runs on the Adyen terminal that takes the payments. */
    @SerialName("thisTerminal")
    THIS_TERMINAL,

    /** A terminal on the same network as the tablet or phone. */
    @SerialName("network")
    NETWORK,

    /** A terminal reached over the internet, through Adyen's Cloud device API. */
    @SerialName("cloud")
    CLOUD,

    /** Tap to Pay on the phone itself, through the Adyen Payments app. */
    @SerialName("tapToPay")
    TAP_TO_PAY,
}

/**
 * Connection, receipt text and optional SMTP fields sent by the setup helper, typed on a computer instead
 * of the device. Only what it holds is set, so the device's other settings stay as they are; unlike a
 * transfer between terminals it may set the device fields ([TerminalSettings.withDeviceFieldsOf]). Its secrets travel
 * sealed beside it, as in any transfer. The page leaves out what was not typed; a blank value counts as left out too.
 *
 * @property destination Where payments go; null leaves it as it is.
 * @property environment Selected environment for a network or cloud terminal; ignored on Adyen terminals and Tap to Pay.
 * @property host The IP address or host name of a terminal on the network.
 * @property poiId The terminal's POIID, for a terminal on the network or in the cloud.
 * @property keyIdentifier The shared key's identifier.
 * @property keyVersion The shared key's version.
 * @property merchantAccount The merchant account the Checkout API (and the cloud) takes payments for.
 * @property liveUrlPrefix The live endpoint prefix for the Checkout API.
 * @property storeId The store Tap to Pay is set up for.
 * @property automatic Whether to continue optional setup lookup after importing the API key; never boards Tap to Pay.
 * @property smtpHost SMTP server name; null or blank keeps the saved server.
 * @property smtpPort SMTP server port; null keeps the saved port, normalized by [SettingsRepository].
 * @property smtpSecurity SMTP connection security; null keeps the saved choice.
 * @property smtpUsername SMTP login name; null or blank keeps the saved login.
 * @property smtpFromAddress Receipt sender address; null or blank keeps the saved address.
 * @property smtpFromName Receipt sender display name; null or blank keeps the saved name.
 * @property receiptBusinessName Explicit receipt business name; null or blank keeps saved text.
 * @property receiptAddressLines Explicit newline-separated receipt address; null or blank keeps saved text.
 * @property receiptPhone Explicit receipt phone number; null or blank keeps saved text.
 * @property receiptTaxId Explicit receipt tax identifier; null or blank keeps saved text.
 * @property receiptTitle Explicit receipt heading; null or blank keeps saved text.
 * @property receiptFooter Explicit receipt footer; null or blank keeps saved text.
 * @property importReceiptName Whether Adyen lookup may fill a blank business name.
 * @property importReceiptAddress Whether Adyen lookup may fill a blank address.
 * @property importReceiptPhone Whether Adyen lookup may fill a blank phone number.
 */
@Serializable
data class ConnectionSetup(
    val destination: ConnectionDestination? = null,
    val environment: TerminalEnvironment? = null,
    val host: String? = null,
    val poiId: String? = null,
    val keyIdentifier: String? = null,
    val keyVersion: Int? = null,
    val merchantAccount: String? = null,
    val liveUrlPrefix: String? = null,
    val storeId: String? = null,
    val automatic: Boolean = false,
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val smtpSecurity: SmtpSecurity? = null,
    val smtpUsername: String? = null,
    val smtpFromAddress: String? = null,
    val smtpFromName: String? = null,
    val receiptBusinessName: String? = null,
    val receiptAddressLines: String? = null,
    val receiptPhone: String? = null,
    val receiptTaxId: String? = null,
    val receiptTitle: String? = null,
    val receiptFooter: String? = null,
    val importReceiptName: Boolean = true,
    val importReceiptAddress: Boolean = true,
    val importReceiptPhone: Boolean = true,
) {
    /** Applies explicit nonblank receipt text; omitted fields and unrelated receipt preferences stay unchanged. */
    fun receiptAppliedTo(receipt: ReceiptSettings): ReceiptSettings =
        receipt.copy(
            businessName = receiptBusinessName.or(receipt.businessName),
            addressLines = receiptAddressLines.or(receipt.addressLines),
            phone = receiptPhone.or(receipt.phone),
            taxId = receiptTaxId.or(receipt.taxId),
            title = receiptTitle.or(receipt.title),
            footer = receiptFooter.or(receipt.footer),
        )

    /** Whether a selected automatic receipt field remains blank and needs read-only Adyen lookup. */
    fun needsReceiptDetails(receipt: ReceiptSettings): Boolean =
        (importReceiptName && receipt.businessName.isBlank()) ||
            (importReceiptAddress && receipt.addressLines.isBlank()) ||
            (importReceiptPhone && receipt.phone.isBlank())

    /** [email] with supplied SMTP fields; omitted or blank text and unrelated receipt email settings stay unchanged. */
    fun emailAppliedTo(email: EmailSettings): EmailSettings =
        email.copy(
            host = smtpHost.or(email.host),
            port = smtpPort ?: email.port,
            security = smtpSecurity ?: email.security,
            username = smtpUsername.or(email.username),
            fromAddress = smtpFromAddress.or(email.fromAddress),
            fromName = smtpFromName.or(email.fromName),
        )

    /** Whether this helper requests optional automatic setup for a destination supported by this device; never boards. */
    fun requestsAutomaticSetup(onTerminal: Boolean): Boolean =
        automatic &&
            when (destination) {
                ConnectionDestination.THIS_TERMINAL -> onTerminal
                ConnectionDestination.NETWORK, ConnectionDestination.CLOUD -> !onTerminal
                ConnectionDestination.TAP_TO_PAY, null -> false
            }

    /**
     * [terminal] with what this setup holds, on a device that is an Adyen terminal when [onTerminal]. A terminal only
     * ever takes payments itself, so there only [ConnectionDestination.THIS_TERMINAL] changes where payments go (to
     * Automatic, which is this terminal); elsewhere that one leaves it, and the others choose theirs. Choosing another
     * destination forgets its environment, as Settings does; a supplied network/cloud environment then sets it.
     */
    fun appliedTo(
        terminal: TerminalSettings,
        onTerminal: Boolean,
    ): TerminalSettings {
        val mode =
            when (destination) {
                null -> null
                ConnectionDestination.THIS_TERMINAL -> TerminalMode.AUTO.takeIf { onTerminal }
                ConnectionDestination.NETWORK -> TerminalMode.TERMINAL.takeUnless { onTerminal }
                ConnectionDestination.CLOUD -> TerminalMode.CLOUD.takeUnless { onTerminal }
                ConnectionDestination.TAP_TO_PAY -> TerminalMode.PAYMENTS_APP.takeUnless { onTerminal }
            }

        val selected = environment.takeIf { !onTerminal && (mode ?: terminal.mode) in setOf(TerminalMode.TERMINAL, TerminalMode.CLOUD) }
        return terminal.withConnection(mode, selected).copy(
            host = host.or(terminal.host),
            poiIdOverride = poiId.or(terminal.poiIdOverride),
            keyIdentifier = keyIdentifier.or(terminal.keyIdentifier),
            keyVersion = keyVersion ?: terminal.keyVersion,
            merchantAccount = merchantAccount.or(terminal.merchantAccount),
            liveUrlPrefix = liveUrlPrefix.or(terminal.liveUrlPrefix),
            storeId = storeId.or(terminal.storeId),
        )
    }

    /** A supplied nonblank setup value, trimmed, or the saved [current] value when omitted. */
    private fun String?.or(current: String): String = this?.trim()?.ifEmpty { null } ?: current
}

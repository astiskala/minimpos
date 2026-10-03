package io.github.astiskala.minimpos.app.data.settings

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
 * The connection the setup helper web page sends in a transfer: where payments go and what reaches them, typed on a
 * computer instead of the device. Only what it holds is set, so the device's other settings stay as they are; unlike a
 * transfer between terminals it may set the device fields ([TerminalSettings.withDeviceFieldsOf]). Its secrets travel
 * sealed beside it, as in any transfer. The page leaves out what was not typed; a blank value counts as left out too.
 *
 * @property destination Where payments go; null leaves it as it is.
 * @property host The IP address or host name of a terminal on the network.
 * @property poiId The terminal's POIID, for a terminal on the network or in the cloud.
 * @property keyIdentifier The shared key's identifier.
 * @property keyVersion The shared key's version.
 * @property merchantAccount The merchant account the Checkout API (and the cloud) takes payments for.
 * @property liveUrlPrefix The live endpoint prefix for the Checkout API.
 * @property storeId The store Tap to Pay is set up for.
 */
@Serializable
data class ConnectionSetup(
    val destination: ConnectionDestination? = null,
    val host: String? = null,
    val poiId: String? = null,
    val keyIdentifier: String? = null,
    val keyVersion: Int? = null,
    val merchantAccount: String? = null,
    val liveUrlPrefix: String? = null,
    val storeId: String? = null,
) {
    /**
     * [terminal] with what this setup holds, on a device that is an Adyen terminal when [onTerminal]. A terminal only
     * ever takes payments itself, so there only [ConnectionDestination.THIS_TERMINAL] changes where payments go (to
     * Automatic, which is this terminal); elsewhere that one leaves it, and the others choose theirs. Choosing another
     * destination forgets the detected environment, as Settings does.
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
            }?.takeIf { it != terminal.mode }

        fun String?.or(current: String) = this?.trim()?.ifEmpty { null } ?: current
        val moved = mode?.let { terminal.copy(mode = it, environment = null, cloudRegion = null) } ?: terminal
        return moved.copy(
            host = host.or(terminal.host),
            poiIdOverride = poiId.or(terminal.poiIdOverride),
            keyIdentifier = keyIdentifier.or(terminal.keyIdentifier),
            keyVersion = keyVersion ?: terminal.keyVersion,
            merchantAccount = merchantAccount.or(terminal.merchantAccount),
            liveUrlPrefix = liveUrlPrefix.or(terminal.liveUrlPrefix),
            storeId = storeId.or(terminal.storeId),
        )
    }
}

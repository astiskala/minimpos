package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.AdyenError
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.adyenField
import app.minimpos.terminal.transport.decodeAdyenModel
import app.minimpos.terminal.transport.fault
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.JSON
import com.adyen.model.checkout.ServiceError

/** [text] as the official Checkout error model; null when it is not readable. */
internal fun parseError(text: String): ServiceError? = decodeAdyenModel(text, ServiceError::class.java)

/** An answer whose status the library cannot map to a known enum, keeping the original status for diagnostics. */
internal fun unexpectedStatus(text: String): Fault = Fault.UnreadableReply(ExternalText.of(adyenField(text, "status")))

/** The fault of an unsuccessful Checkout answer, with Adyen's error message and code from its error model. */
internal fun AdyenReply.Answered.checkoutFault(): Fault =
    parseError(body).let { fault(ApiKey.ADYEN, error = AdyenError(ExternalText.of(it?.message), it?.errorCode)) }

/** The fault of a call that got no usable answer from the Checkout API. */
internal fun AdyenReply.failure(): Fault? =
    when (this) {
        is AdyenReply.Failed -> fault
        is AdyenReply.Answered if ok -> null
        is AdyenReply.Answered -> checkoutFault()
    }

internal fun ApplicationInfo.checkoutInfo(): com.adyen.model.checkout.ApplicationInfo =
    JSON.getMapper().convertValue(this, com.adyen.model.checkout.ApplicationInfo::class.java)

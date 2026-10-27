package app.minimpos.terminal

import app.minimpos.terminal.parse.FormEncoding
import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.TerminalKey
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Plays the Adyen Payments app: boards, and answers payments and refunds with the simulator. */
internal class FakePaymentsApp(
    key: TerminalKey,
) : AppLinkExchange {
    /** One link the fake Payments app was asked to open. */
    data class Opened(
        val link: String,
        val packageName: String,
    ) {
        val parameters = FormEncoding.decode(link.substringAfter('?'))
        val returnUrl: String = URLDecoder.decode(parameters.getValue("returnUrl"), Charsets.UTF_8)
        val request: String? = parameters["request"]
    }

    private val gson = TerminalAPIGsonBuilder.create()
    private val crypto = NexoCrypto(key.toSecurityKey())
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })
    val opened = mutableListOf<Opened>()
    val requests = mutableListOf<TerminalAPIRequest>()
    var loseFinancialReplies = false
    var transform: ((TerminalAPIResponse) -> Unit)? = null
    val boardingTokens = mutableListOf<String>()
    var boarded = true

    /** Keeps answers as if the app had been restarted, so the exchange times out. */
    var keepAnswers = false
    var retainAnswers = false
    val callbacks = mutableListOf<String>()
    private val late = mutableListOf<String>()

    fun retain(url: String) {
        late += url
    }

    /** Replaces the answer to the next links. */
    var reply: ((Opened) -> String)? = null

    /** Brings no answer back from the next links. */
    var unanswered: AppLinkAnswer.Unanswered? = null

    override suspend fun exchange(
        link: String,
        packageName: String,
        timeout: Duration,
    ): AppLinkAnswer {
        val opened = Opened(link, packageName).also { this.opened += it }
        val answer = unanswered ?: AppLinkAnswer.Returned(reply?.invoke(opened) ?: answer(opened))
        if (answer is AppLinkAnswer.Returned) {
            callbacks += answer.url
            if (keepAnswers || retainAnswers) late += answer.url
        }
        val financial = requests.lastOrNull()?.saleToPOIRequest?.let { it.paymentRequest != null || it.reversalRequest != null } == true
        return if (keepAnswers || (loseFinancialReplies && financial)) AppLinkAnswer.TimedOut else answer
    }

    override fun lateReplies(): List<String> = late.toList()

    override fun forgetLateReplies(urls: Set<String>) {
        late.removeAll(urls)
    }

    private suspend fun answer(opened: Opened): String =
        when {
            opened.link.contains("/boarded?") -> {
                if (boarded && !opened.link.contains("reboard=true")) {
                    "${opened.returnUrl}?boarded=true&installationId=INSTALLATION-1"
                } else {
                    "${opened.returnUrl}?boarded=false&installationId=INSTALLATION-1&boardingRequestToken=BRT-1"
                }
            }

            opened.link.contains("/board?") -> {
                boardingTokens += String(Base64.UrlSafe.decode(opened.parameters.getValue("boardingToken")))
                boarded = true
                "${opened.returnUrl}?boarded=true&installationId=INSTALLATION-1"
            }

            else -> {
                val secured =
                    gson.fromJson(
                        String(Base64.UrlSafe.decode(checkNotNull(opened.request))),
                        TerminalAPISecuredRequest::class.java,
                    )
                val request = gson.fromJson(crypto.decrypt(secured.saleToPOIRequest), TerminalAPIRequest::class.java)
                requests += request
                val response = checkNotNull((simulator.send(request, 10.seconds) as Delivery.Answered).response)
                transform?.invoke(response)
                "${opened.returnUrl}?response=${encrypt(gson.toJson(response), response.saleToPOIResponse.messageHeader)}"
            }
        }

    /** An answer that says it is for [serviceId], encrypted with this app's key. */
    fun encryptedAnswer(
        opened: Opened,
        serviceId: String,
    ): String {
        val header =
            MessageHeader().apply {
                this.serviceID = serviceId
                messageCategory = MessageCategoryType.PAYMENT
                messageClass = MessageClassType.SERVICE
                messageType = MessageType.RESPONSE
            }
        val response =
            TerminalAPIResponse().apply {
                saleToPOIResponse =
                    SaleToPOIResponse()
                        .apply { messageHeader = header }
            }
        return "${opened.returnUrl}?response=${encrypt(gson.toJson(response), header)}"
    }

    private fun encrypt(
        json: String,
        header: MessageHeader,
    ): String {
        val secured = TerminalAPISecuredResponse().apply { saleToPOIResponse = crypto.encrypt(json, header) }
        return Base64.UrlSafe.encode(gson.toJson(secured).toByteArray())
    }

    companion object {
        const val RETURN_URL = "minimpos://paymentsapp"
    }
}

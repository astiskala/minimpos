package app.minimpos.terminal.client

import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.LoginRequest
import com.adyen.model.nexo.LogoutRequest
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleSoftware
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Opens a session only after an explicit LoggedOut rejection, runs [operation] once, then best-effort logs out the session
 * this call opened, including on cancellation. An unanswered login never permits [operation]; cleanup cannot replace its
 * result or cause a reconciliation to be repeated.
 */
internal suspend fun TerminalClient.withTotalsLogin(operation: suspend () -> TerminalTotalsOutcome): TerminalTotalsOutcome {
    val delivery =
        sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.LOGIN) {
            loginRequest =
                LoginRequest().apply {
                    dateTime = TerminalClient.parseTimestamp(TerminalClient.formatTimestamp(clock.instant()))
                    operatorLanguage = "en"
                    saleSoftware =
                        SaleSoftware().apply {
                            manufacturerID = application?.let { PosApplication.clean(it.integrator) }.orEmpty()
                            applicationName = application?.let { PosApplication.clean(it.name) }.orEmpty()
                            softwareVersion = application?.let { PosApplication.clean(it.version) }.orEmpty()
                            // Required nexo field; empty does not claim software certification.
                            certificationCode = ""
                        }
                }
        }
    val response =
        when (val answer = readTerminalAnswer(delivery) { it.loginResponse?.response }) {
            is TerminalAnswer.Unread -> return TerminalTotalsOutcome.Failed(answer.fault)
            is TerminalAnswer.Read -> answer.body
        }
    if (response.result != ResultType.SUCCESS) {
        val message = AdditionalResponseParser.parse(response.additionalResponse)[NexoVocabulary.MESSAGE]
        return TerminalTotalsOutcome.Failed(Fault.TerminalRejected(ExternalText.of(message)))
    }
    return try {
        operation()
    } finally {
        withContext(NonCancellable) {
            sendShortMessage(MessageClassType.SERVICE, MessageCategoryType.LOGOUT) { logoutRequest = LogoutRequest() }
        }
    }
}

package app.minimpos.terminal

import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GetTotalsResponse
import com.adyen.model.nexo.LoginResponse
import com.adyen.model.nexo.LogoutResponse
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.ReconciliationResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TerminalSessionTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0
    private val clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC)
    private val unreachable = Fault.Unreachable("192.168.1.20:8443", terminal = true)
    private val loggedOut = result(ResultType.FAILURE, ErrorConditionType.LOGGED_OUT, "message=Must%20be%20logged%20in")

    private fun client(
        application: PosApplication? = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
        reply: suspend (TerminalAPIRequest) -> Delivery,
    ) = TerminalClient(
        transport =
            TerminalTransport { request, _ ->
                sent += request
                reply(request)
            },
        identity = TerminalIdentity("POS1", "S1F2-000158213605014"),
        application = application,
        clock = clock,
        newServiceId = { "S${++ids}" },
        currencyDigits = testCurrencyDigits,
    )

    private fun result(
        type: ResultType = ResultType.SUCCESS,
        condition: ErrorConditionType? = null,
        additional: String? = null,
    ) = Response().apply {
        result = type
        errorCondition = condition
        additionalResponse = additional
    }

    private fun answer(
        category: MessageCategoryType,
        response: Response? = result(),
    ) = Delivery.Answered(
        TerminalAPIResponse().apply {
            saleToPOIResponse =
                SaleToPOIResponse().apply {
                    when {
                        category == MessageCategoryType.GET_TOTALS -> {
                            getTotalsResponse =
                                GetTotalsResponse().apply {
                                    this.response = response
                                    poiReconciliationID = "42"
                                }
                        }

                        category == MessageCategoryType.RECONCILIATION -> {
                            reconciliationResponse =
                                ReconciliationResponse().apply {
                                    this.response = response
                                    poiReconciliationID = "42"
                                }
                        }

                        category == MessageCategoryType.LOGIN -> {
                            loginResponse = LoginResponse().apply { this.response = response }
                        }

                        category == MessageCategoryType.LOGOUT -> {
                            logoutResponse = LogoutResponse().apply { this.response = response }
                        }

                        else -> {
                            error("Unexpected request: $category")
                        }
                    }
                }
        },
    )

    private fun categories() = sent.map { it.saleToPOIRequest.messageHeader.messageCategory }

    @Test
    fun `logged out totals opens its own session and closes it after reading`() =
        runTest {
            var reads = 0
            val terminal =
                client { request ->
                    val category = request.saleToPOIRequest.messageHeader.messageCategory
                    if (category == MessageCategoryType.GET_TOTALS && reads++ == 0) {
                        answer(category, loggedOut)
                    } else {
                        answer(category)
                    }
                }
            assertThat(terminal.getTotals()).isEqualTo(TerminalTotalsOutcome.Answered("42", emptyList(), false))
            assertThat(categories())
                .containsExactly(
                    MessageCategoryType.GET_TOTALS,
                    MessageCategoryType.LOGIN,
                    MessageCategoryType.GET_TOTALS,
                    MessageCategoryType.LOGOUT,
                ).inOrder()
            val login = sent[1].saleToPOIRequest.loginRequest
            assertThat(login.dateTime.toXMLFormat()).isEqualTo("2026-09-30T01:02:03.456Z")
            assertThat(login.operatorLanguage).isEqualTo("en")
            assertThat(login.saleSoftware.manufacturerID).isEqualTo("Mini mPOS")
            assertThat(login.saleSoftware.applicationName).isEqualTo("Mini mPOS")
            assertThat(login.saleSoftware.softwareVersion).isEqualTo("1.0")
            assertThat(login.saleSoftware.certificationCode).isEmpty()
            assertThat(sent.map { it.saleToPOIRequest.messageHeader.serviceID }.distinct()).hasSize(4)
        }

    @Test
    fun `an existing session is read without login or logout`() =
        runTest {
            assertThat(client { answer(MessageCategoryType.GET_TOTALS) }.getTotals())
                .isEqualTo(TerminalTotalsOutcome.Answered("42", emptyList(), false))
            assertThat(categories()).containsExactly(MessageCategoryType.GET_TOTALS)
        }

    @Test
    fun `login refusal and unreadable login never retry totals or log out an unconfirmed session`() =
        runTest {
            val cases =
                listOf(
                    answer(
                        MessageCategoryType.LOGIN,
                        result(ResultType.FAILURE, ErrorConditionType.NOT_ALLOWED, "message=Login+denied"),
                    ) to Fault.TerminalRejected(ExternalText("Login denied")),
                    Delivery.Answered(null) to Fault.UnreadableReply(),
                    answer(MessageCategoryType.LOGIN, null) to Fault.UnreadableReply(),
                    Delivery.Failed(unreachable) to unreachable,
                    Delivery.Failed(Fault.TimedOut) to Fault.TimedOut,
                )
            cases.forEach { (loginAnswer, fault) ->
                sent.clear()
                val terminal =
                    client { request ->
                        if (request.saleToPOIRequest.loginRequest !=
                            null
                        ) {
                            loginAnswer
                        } else {
                            answer(MessageCategoryType.GET_TOTALS, loggedOut)
                        }
                    }
                assertThat(terminal.getTotals()).isEqualTo(TerminalTotalsOutcome.Failed(fault))
                assertThat(categories()).containsExactly(MessageCategoryType.GET_TOTALS, MessageCategoryType.LOGIN).inOrder()
            }
        }

    @Test
    fun `only an explicit failed LoggedOut answer permits a session retry`() =
        runTest {
            val cases =
                listOf(
                    null,
                    Response().apply { errorCondition = ErrorConditionType.LOGGED_OUT },
                    result(ResultType.PARTIAL, ErrorConditionType.LOGGED_OUT),
                    result(ResultType.SUCCESS, ErrorConditionType.LOGGED_OUT),
                    result(ResultType.FAILURE, ErrorConditionType.BUSY),
                )
            cases.forEach { response ->
                sent.clear()
                client { answer(MessageCategoryType.GET_TOTALS, response) }.getTotals()
                assertThat(categories()).containsExactly(MessageCategoryType.GET_TOTALS)
            }
        }

    @Test
    fun `an unanswered initial reconciliation never logs in or resends`() =
        runTest {
            val cases =
                listOf(
                    Delivery.Failed(Fault.TimedOut) to Fault.TimedOut,
                    Delivery.Answered(null) to Fault.UnreadableReply(),
                )
            cases.forEach { (reply, fault) ->
                sent.clear()
                assertThat(client { reply }.reconcile()).isEqualTo(TerminalTotalsOutcome.Failed(fault))
                assertThat(categories()).containsExactly(MessageCategoryType.RECONCILIATION)
            }
        }

    @Test
    fun `session retry never repeats an ambiguous reconciliation or replaces its result with logout failure`() =
        runTest {
            val cases =
                listOf(
                    answer(MessageCategoryType.RECONCILIATION) to TerminalTotalsOutcome.Answered("42", emptyList(), true),
                    Delivery.Failed(Fault.TimedOut) to TerminalTotalsOutcome.Failed(Fault.TimedOut),
                    Delivery.Answered(null) to TerminalTotalsOutcome.Failed(Fault.UnreadableReply()),
                    answer(MessageCategoryType.RECONCILIATION, loggedOut) to
                        TerminalTotalsOutcome.Failed(Fault.TerminalRejected(ExternalText("Must be logged in"))),
                )
            cases.forEach { (retryAnswer, expected) ->
                sent.clear()
                var reconciliations = 0
                val terminal =
                    client(application = null) { request ->
                        when (val category = request.saleToPOIRequest.messageHeader.messageCategory) {
                            MessageCategoryType.LOGIN -> answer(category)
                            MessageCategoryType.LOGOUT -> Delivery.Failed(unreachable)
                            else -> if (reconciliations++ == 0) answer(category, loggedOut) else retryAnswer
                        }
                    }
                assertThat(terminal.reconcile()).isEqualTo(expected)
                assertThat(categories())
                    .containsExactly(
                        MessageCategoryType.RECONCILIATION,
                        MessageCategoryType.LOGIN,
                        MessageCategoryType.RECONCILIATION,
                        MessageCategoryType.LOGOUT,
                    ).inOrder()
                val software = sent[1].saleToPOIRequest.loginRequest.saleSoftware
                assertThat(software.manufacturerID).isEmpty()
                assertThat(software.applicationName).isEmpty()
                assertThat(software.softwareVersion).isEmpty()
            }
        }

    @Test
    fun `cancelling a session totals read still completes logout`() =
        runTest {
            val readStarted = CompletableDeferred<Unit>()
            var reads = 0
            var didLogout = false
            val terminal =
                client { request ->
                    when (val category = request.saleToPOIRequest.messageHeader.messageCategory) {
                        MessageCategoryType.LOGIN -> {
                            answer(category)
                        }

                        MessageCategoryType.LOGOUT -> {
                            delay(1)
                            didLogout = true
                            answer(category)
                        }

                        else -> {
                            if (reads++ == 0) {
                                answer(category, loggedOut)
                            } else {
                                readStarted.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    }
                }
            val operation = launch { terminal.getTotals() }
            readStarted.await()
            operation.cancelAndJoin()
            assertThat(operation.isCancelled).isTrue()
            assertThat(didLogout).isTrue()
            assertThat(categories())
                .containsExactly(
                    MessageCategoryType.GET_TOTALS,
                    MessageCategoryType.LOGIN,
                    MessageCategoryType.GET_TOTALS,
                    MessageCategoryType.LOGOUT,
                ).inOrder()
        }
}

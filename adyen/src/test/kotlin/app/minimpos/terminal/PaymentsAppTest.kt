package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.parse.FormEncoding
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.paymentsapp.BoardingTarget
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.paymentsapp.Onboarding
import app.minimpos.terminal.paymentsapp.PaymentsAppCrypto
import app.minimpos.terminal.paymentsapp.PaymentsAppLinks
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import app.minimpos.terminal.paymentsapp.PaymentsAppOnboarding
import app.minimpos.terminal.paymentsapp.PaymentsAppTransport
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PaymentsAppTest {
    private val key = TerminalKey("key-id", "correct horse battery staple", 1)
    private val app = FakePaymentsApp(key)
    private val transport = PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL)
    private val client =
        TerminalClient(
            transport,
            TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1, maxInProgressChecks = 1, reprintReceipts = false),
            currencyDigits = testCurrencyDigits,
        )
    private val payment = PaymentParams(1250, "EUR", "260930-1")

    @Test
    fun `links follow Adyen's formats for each environment`() {
        val test = PaymentsAppLinks(TerminalEnvironment.TEST)
        val live = PaymentsAppLinks(TerminalEnvironment.LIVE)
        assertThat(test.packageName).isEqualTo("com.adyen.ipp.mobile.companion.test")
        assertThat(live.packageName).isEqualTo("com.adyen.ipp.mobile.companion.live")
        // As in Adyen's example code, the return URL is URL-encoded before it is added as a parameter.
        assertThat(test.boardedCheck("minimpos://paymentsapp/boarded"))
            .isEqualTo("https://www.adyen.com/test/boarded?returnUrl=minimpos%253A%252F%252Fpaymentsapp%252Fboarded")
        assertThat(
            live.boardedCheck("app://x", reboard = true),
        ).isEqualTo("https://www.adyen.com/boarded?returnUrl=app%253A%252F%252Fx&reboard=true")
        val board = live.board("token-1", "app://x")
        assertThat(board).startsWith("https://www.adyen.com/board?boardingToken=")
        val token = FormEncoding.decode(board.substringAfter('?')).getValue("boardingToken")
        assertThat(String(Base64.UrlSafe.decode(token))).isEqualTo("token-1")
        assertThat(test.nexo("abc=", "app://x")).isEqualTo("https://www.adyen.com/test/nexo?request=abc%3D&returnUrl=app%253A%252F%252Fx")
        assertThat(
            PaymentsAppLinks.answer("app://x?boarded=true&installationId=ID%201"),
        ).containsExactly("boarded", "true", "installationId", "ID 1")
        assertThat(PaymentsAppLinks.answer("app://x")).isEmpty()
    }

    @Test
    fun `a payment and a refund travel encrypted in links and come back through the return URL`() {
        val paid = runBlocking { client.pay(payment, serviceId = "PAY1") } as TransactionOutcome.Completed
        assertThat(paid.details.success).isTrue()
        val opened = app.opened.single()
        assertThat(opened.packageName).isEqualTo("com.adyen.ipp.mobile.companion.test")
        assertThat(opened.link).startsWith("https://www.adyen.com/test/nexo?request=")
        assertThat(opened.returnUrl).isEqualTo("$RETURN_URL/nexo")
        // The request in the link is encrypted, not readable.
        assertThat(opened.link).doesNotContain("PaymentRequest")
        assertThat(String(Base64.UrlSafe.decode(checkNotNull(opened.request)))).contains("NexoBlob")

        val refund =
            RefundParams(paid.details.poiTransactionId!!, paid.details.poiTimestamp!!, "R-260930-1")
        val refunded = runBlocking { client.refund(refund, serviceId = "REF1") } as TransactionOutcome.Completed
        assertThat(refunded.details.success).isTrue()
    }

    @Test
    fun `payments refunds and status go to the Payments app but hardware requests do not`() {
        assertThat(runBlocking { client.diagnose() }.reachable).isFalse()
        assertThat(runBlocking { client.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))) })
            .isInstanceOf(PrintOutcome.Failed::class.java)
        runBlocking { client.abort("PAY1") }
        // An unconfirmed NotFound cannot establish that another payment is safe.
        assertThat(runBlocking { client.status("PAY1") }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        assertThat(
            app.requests
                .single()
                .saleToPOIRequest.transactionStatusRequest,
        ).isNotNull()
    }

    @Test
    fun `an answer that arrived after a restart settles the payment's status check`() {
        app.keepAnswers = true
        // The payment's own status check already finds the answer.
        val paid = runBlocking { client.pay(payment, serviceId = "PAY2") } as TransactionOutcome.Completed
        assertThat(paid.details.success).isTrue()
        val recovered = runBlocking { client.status("PAY2") } as TransactionOutcome.Completed
        assertThat(recovered.details.success).isTrue()
        // Acknowledgement belongs after durable storage; until then repeat checks must still work.
        assertThat(app.lateReplies()).hasSize(1)
        transport.acknowledge("OTHER")
        assertThat(app.lateReplies()).hasSize(1)
        transport.acknowledge("PAY2")
        assertThat(app.lateReplies()).isEmpty()
        assertThat(runBlocking { client.status("PAY2") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        // Answers to other requests do not count.
        assertThat(runBlocking { client.status("OTHER") }).isInstanceOf(TransactionOutcome.Unknown::class.java)
    }

    @Test
    fun `missing payment and refund callbacks recover with status without sending another financial request`() {
        app.loseFinancialReplies = true
        val paid = runBlocking { client.pay(payment, "LOSTPAY") } as TransactionOutcome.Completed
        val request = app.requests.last().saleToPOIRequest
        assertThat(request.messageHeader.poiid).isEqualTo("INSTALLATION-1")
        assertThat(request.messageHeader.saleID).isEqualTo("MiniMPOS")
        assertThat(request.messageHeader.serviceID).isNotEqualTo("LOSTPAY")
        assertThat(request.transactionStatusRequest.messageReference.serviceID).isEqualTo("LOSTPAY")
        assertThat(request.transactionStatusRequest.messageReference.saleID).isEqualTo("MiniMPOS")
        assertThat(request.transactionStatusRequest.messageReference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
        assertThat(request.transactionStatusRequest.isReceiptReprintFlag).isFalse()
        val refund = RefundParams(paid.details.poiTransactionId!!, paid.details.poiTimestamp!!, "REFUND")
        assertThat(runBlocking { client.refund(refund, "LOSTREF") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(
            app.requests
                .last()
                .saleToPOIRequest.transactionStatusRequest.messageReference.messageCategory,
        ).isEqualTo(MessageCategoryType.REVERSAL)
        assertThat(app.requests.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
        assertThat(app.requests.count { it.saleToPOIRequest.reversalRequest != null }).isEqualTo(1)
    }

    @Test
    fun `fresh client after process death recovers the persisted original identity through selected environment`() {
        runBlocking { client.pay(payment, "RESTART") }
        TerminalEnvironment.entries.forEach { environment ->
            val restarted =
                TerminalClient(
                    PaymentsAppTransport(key, environment, app, RETURN_URL),
                    TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
                    currencyDigits = testCurrencyDigits,
                    recovery = RecoveryPolicy(reprintReceipts = false),
                )
            assertThat(runBlocking { restarted.status("RESTART") }).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(app.opened.last().packageName).isEqualTo(PaymentsAppLinks.packageName(environment))
            val prefix = if (environment == TerminalEnvironment.TEST) "https://www.adyen.com/test/nexo?" else "https://www.adyen.com/nexo?"
            assertThat(app.opened.last().link).startsWith(prefix)
        }
        assertThat(app.requests.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
    }

    @Test
    fun `old app versions use validated late replies and can gain status support after update`() {
        var version = "1.6.0"
        val old =
            TerminalClient(
                PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL) { version },
                TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
                currencyDigits = testCurrencyDigits,
            )
        assertThat(runBlocking { old.status("OLD") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
        assertThat(app.opened).isEmpty()
        app.keepAnswers = true
        assertThat(runBlocking { client.pay(payment, "OLD") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(runBlocking { old.status("OLD") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        app.keepAnswers = false
        version = "1.8.0"
        assertThat(runBlocking { old.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
        assertThat(app.opened).hasSize(2)
        listOf(null, "unknown", "1.8.0", "1.9.0", "2.0.0").forEach { assertThat(PaymentsAppTransport.supportsStatus(it)).isTrue() }
        listOf("0.9.0", "1.6.0", "1.7.99").forEach { assertThat(PaymentsAppTransport.supportsStatus(it)).isFalse() }
    }

    @Test
    fun `in progress missing callbacks and wrong status context remain unknown`() {
        runBlocking { client.pay(payment, "ORIGINAL") }
        app.transform = {
            it.saleToPOIResponse.transactionStatusResponse.response =
                Response().apply {
                    result = ResultType.FAILURE
                    errorCondition = ErrorConditionType.IN_PROGRESS
                }
        }
        assertThat(runBlocking { client.status("ORIGINAL") }).isEqualTo(TransactionOutcome.Unknown(Fault.StillInProgress))
        app.transform = { it.saleToPOIResponse.transactionStatusResponse.messageReference.serviceID = "FOREIGN" }
        assertThat(runBlocking { client.status("ORIGINAL") }).isEqualTo(TransactionOutcome.Unknown(Fault.UnreadableReply()))
        app.transform = null
        assertThat(runBlocking { client.status("ORIGINAL", TransactionKind.REFUND) })
            .isEqualTo(TransactionOutcome.Unknown(Fault.UnreadableReply()))
        app.unanswered = AppLinkAnswer.TimedOut
        assertThat(runBlocking { client.status("ORIGINAL") }).isEqualTo(TransactionOutcome.Unknown(Fault.TimedOut))
    }

    @Test
    fun `malformed status bodies and mismatched repeated headers never settle the original payment`() {
        runBlocking { client.pay(payment, "GUARDS") }
        val mutations: List<(SaleToPOIResponse) -> Unit> =
            listOf(
                { it.messageHeader.saleID = "FOREIGN" },
                { it.messageHeader.poiid = "FOREIGN" },
                { it.transactionStatusResponse = null },
                { it.transactionStatusResponse.messageReference = null },
                { it.transactionStatusResponse.repeatedMessageResponse = null },
                { it.transactionStatusResponse.repeatedMessageResponse.repeatedResponseMessageBody = null },
                { it.transactionStatusResponse.repeatedMessageResponse.messageHeader = MessageHeader().apply { poiid = "FOREIGN" } },
            )
        mutations.forEach { mutation ->
            app.transform = { mutation(it.saleToPOIResponse) }
            assertThat(runBlocking { client.status("GUARDS") }).isEqualTo(TransactionOutcome.Unknown(Fault.UnreadableReply()))
        }
        app.transform = null
        runBlocking { client.status("GUARDS") }
        val missingReference = app.requests.last()
        missingReference.saleToPOIRequest.transactionStatusRequest.messageReference = null
        assertThat(runBlocking { transport.send(missingReference, 1.seconds) }).isEqualTo(Delivery.Failed(Fault.NoLateReply))
        listOf("999999999999999999999.8.0", "1.999999999999999999999.0").forEach {
            assertThat(PaymentsAppTransport.supportsStatus(it)).isTrue()
        }
    }

    @Test
    fun `late replies must match original sale terminal and category even when service ID matches`() {
        app.keepAnswers = true
        runBlocking { client.pay(payment, "COLLISION") }
        val lateOnly = PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL) { "1.6.0" }
        listOf(TerminalIdentity("OTHER", "INSTALLATION-1"), TerminalIdentity("MiniMPOS", "OTHER")).forEach { identity ->
            val foreign = TerminalClient(lateOnly, identity, currencyDigits = testCurrencyDigits)
            assertThat(runBlocking { foreign.status("COLLISION") }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        }
        val original = TerminalClient(lateOnly, TerminalIdentity("MiniMPOS", "INSTALLATION-1"), currencyDigits = testCurrencyDigits)
        assertThat(
            runBlocking { original.status("COLLISION", TransactionKind.REFUND) },
        ).isInstanceOf(TransactionOutcome.Unknown::class.java)
        assertThat(app.lateReplies()).hasSize(1)
        assertThat(runBlocking { client.status("COLLISION") }).isInstanceOf(TransactionOutcome.Completed::class.java)
    }

    @Test
    fun `an original answer racing the status callback is retained until durable acknowledgement`() {
        app.keepAnswers = true
        runBlocking { client.pay(payment, "RACE") }
        val original = app.lateReplies().single()
        transport.acknowledge("RACE")
        app.reply = { original }
        assertThat(runBlocking { client.status("RACE") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(app.lateReplies()).containsExactly(original)
        transport.acknowledge("RACE")
        assertThat(app.lateReplies()).isEmpty()
        assertThat(app.requests.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
        assertThat(app.opened).hasSize(2)
    }

    @Test
    fun `errors, foreign answers and wrong keys are reported as such`() {
        app.reply = { "$RETURN_URL/nexo?error=Cancelled+by+user" }
        assertThat((runBlocking { client.pay(payment) } as TransactionOutcome.NotProcessed).fault)
            .isEqualTo(Fault.AppRefused(ExternalText("Cancelled by user")))
        app.reply = { "$RETURN_URL/nexo?response=bm90IGpzb24" }
        assertThat(runBlocking { client.pay(payment) }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        // A Payments app with another shared key answers in a way that cannot be verified.
        val stranger = FakePaymentsApp(TerminalKey("key-id", "another passphrase", 1))
        val mismatched = PaymentsAppTransport(key, TerminalEnvironment.TEST, stranger, RETURN_URL)
        val request =
            TerminalAPIRequest().apply {
                saleToPOIRequest =
                    SaleToPOIRequest().apply {
                        messageHeader =
                            MessageHeader()
                                .apply { serviceID = "X" }
                        paymentRequest =
                            PaymentRequest()
                    }
            }
        stranger.reply = { stranger.encryptedAnswer(it, serviceId = "X") }
        assertThat(runBlocking { mismatched.send(request, 5.seconds) }).isEqualTo(Delivery.Failed(Fault.ReplyUnverified))
        // An answer to another request is not taken for this one.
        app.reply = { app.encryptedAnswer(it, serviceId = "SOMETHING-ELSE") }
        assertThat(
            runBlocking { PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL).send(request, 5.seconds) },
        ).isEqualTo(Delivery.Failed(Fault.UnreadableReply()))
        // A Payments app that cannot be started means nothing was sent.
        app.unanswered = AppLinkAnswer.NotStarted
        assertThat((runBlocking { client.pay(payment) } as TransactionOutcome.NotProcessed).fault).isEqualTo(Fault.NotStarted)
        app.unanswered = null
        // Missing response/error parameters or empty request fail cleanly.
        app.reply = { "$RETURN_URL/nexo" }
        assertThat(
            runBlocking { PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL).send(request, 5.seconds) },
        ).isEqualTo(Delivery.Failed(Fault.AppRefused(null)))
        assertThat(
            runBlocking { PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL).send(TerminalAPIRequest(), 5.seconds) },
        ).isEqualTo(Delivery.Failed(Fault.Unsupported))
    }

    @Test
    fun `short callback includes decoded error code and support trace ID`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val traceParent = "00-$traceId-00f067aa0ba902b7-01"
        app.reply = {
            "$RETURN_URL/nexo?error=UnavailableService&message=Service+not+enabled" +
                "&errorCode=APP%2F42&traceParent=$traceParent&secret=never-show"
        }
        val outcome = runBlocking { client.pay(payment) } as TransactionOutcome.NotProcessed
        assertThat(outcome.fault).isEqualTo(
            Fault.AppRefused(
                ExternalText("UnavailableService\nService not enabled\nerrorCode=APP/42\ntraceparent=$traceParent\ntraceId=$traceId"),
            ),
        )
    }

    @Test
    fun `an encoded short response exposes its trace without claiming a payment outcome`() {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val traceParent = "00-$traceId-00f067aa0ba902b7-01"
        val json =
            """{"error":"UnavailableService","message":"Service not enabled","errorCode":"42","traceParent":"$traceParent","secret":"private"}"""
        app.reply = { "$RETURN_URL/nexo?response=${Base64.UrlSafe.encode(json.toByteArray())}" }
        val outcome = runBlocking { client.pay(payment) } as TransactionOutcome.Unknown
        assertThat(outcome.fault).isEqualTo(
            Fault.UnreadableReply(
                ExternalText("UnavailableService\nService not enabled\nerrorCode=42\ntraceparent=$traceParent\ntraceId=$traceId"),
            ),
        )
        listOf(
            """{"error":{},"message":[],"traceParent":null}""",
            "[]",
            "null",
            " ",
            """{"unrecognized":"private"}""",
            """{"SaleToPOIResponse":{},"message":"Not an authenticated answer","traceParent":"$traceParent"}""",
        ).forEach { malformed ->
            app.reply = { "$RETURN_URL/nexo?response=${Base64.UrlSafe.encode(malformed.toByteArray())}" }
            assertThat((runBlocking { client.pay(payment) } as TransactionOutcome.Unknown).fault).isEqualTo(Fault.ReplyUnverified)
        }
        val crypto = PaymentsAppCrypto(key)
        assertThat(crypto.shortError(Base64.UrlSafe.encode("null".toByteArray()))).isNull()
        assertThat(crypto.shortError(Base64.UrlSafe.encode(" ".toByteArray()))).isNull()
        assertThat(crypto.shortError(Base64.UrlSafe.encode("""{"SaleToPOIResponse":{}}""".toByteArray()))).isNull()
    }

    @Test
    fun `boarding asks the Payments app, gets a token from Adyen and hands it over`() {
        val management = RecordingManagement()
        val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, app, management, RETURN_URL)
        app.boarded = false
        val boarded = runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM", "ST1")) }
        assertThat(boarded).isEqualTo(Onboarding.NewlyBoarded("INSTALLATION-1", confirmed = false))
        assertThat(management.requests).containsExactly(BoardingTarget("HarbourCoffeeCOM", "ST1") to "BRT-1")
        assertThat(app.opened.map { it.returnUrl }).containsExactly("$RETURN_URL/boarded", "$RETURN_URL/board").inOrder()
        assertThat(app.boardingTokens).containsExactly("BT-1")

        // Once boarded, the check alone answers; reboarding goes through the steps again.
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) })
            .isEqualTo(Onboarding.AlreadyBoarded("INSTALLATION-1"))
        assertThat(management.requests).hasSize(1)
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM"), reboard = true) })
            .isEqualTo(Onboarding.NewlyBoarded("INSTALLATION-1", confirmed = false))
        assertThat(app.opened.count { it.link.contains("reboard=true") }).isEqualTo(1)
        assertThat(management.requests).hasSize(2)

        // Failures from Adyen and from the Payments app are reported.
        app.boarded = false
        management.result = ManagementResult.Failed(Fault.Permission(ApiKey.PAYMENTS_APP))
        assertThat(
            runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) },
        ).isEqualTo(Onboarding.Failed(Fault.Permission(ApiKey.PAYMENTS_APP), unboarded = true))
        management.result = ManagementResult.Done(boardingToken = null)
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) })
            .isEqualTo(Onboarding.Failed(Fault.UnreadableReply(), unboarded = true))
        app.reply = { "$RETURN_URL/boarded?boarded=false&error=Device+not+supported" }
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) })
            .isEqualTo(Onboarding.Failed(Fault.AppRefused(ExternalText("Device not supported")), unboarded = true))
        app.reply = { "$RETURN_URL/boarded?error=Native+library+error" }
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) })
            .isEqualTo(Onboarding.Failed(Fault.AppRefused(ExternalText("Native library error")), unboarded = false))
        app.reply = { "$RETURN_URL/boarded?boarded=true" }
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) })
            .isEqualTo(Onboarding.Failed(Fault.UnreadableReply(), unboarded = false))
        val minimalManagement =
            object : PaymentsAppManagement {
                override suspend fun boardingToken(
                    target: BoardingTarget,
                    boardingRequestToken: String,
                ): ManagementResult = ManagementResult.NotBoarded

                override suspend fun revoke(
                    merchantAccount: String,
                    installationId: String,
                ): ManagementResult = ManagementResult.NotBoarded
            }
        assertThat(runBlocking { minimalManagement.registration(BoardingTarget("HarbourCoffeeCOM"), "I1") })
            .isEqualTo(ManagementResult.Failed(Fault.Unsupported))
        assertThrows(IllegalArgumentException::class.java) { BoardingTarget(" ") }
    }

    @Test
    fun `expired boarding token is refused and an explicit retry obtains a fresh token`() {
        val management = RecordingManagement()
        val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, app, management, RETURN_URL)
        val target = BoardingTarget("HarbourCoffeeCOM", "ST1")
        app.boarded = false
        app.reply = { opened ->
            if ("/boarded?" in opened.link) {
                "${opened.returnUrl}?boarded=false&installationId=INSTALLATION-1&boardingRequestToken=BRT-1"
            } else {
                "${opened.returnUrl}?boarded=false&error=Boarding+token+expired"
            }
        }
        assertThat(runBlocking { onboarding.board(target) })
            .isEqualTo(Onboarding.Failed(Fault.AppRefused(ExternalText("Boarding token expired")), unboarded = true))
        assertThat(management.requests).hasSize(1)
        assertThat(app.opened).hasSize(2)
        assertThat(app.boarded).isFalse()

        app.reply = null
        management.result = ManagementResult.Done("BT-2", "INSTALLATION-1")
        assertThat(runBlocking { onboarding.board(target) }).isEqualTo(Onboarding.NewlyBoarded("INSTALLATION-1", confirmed = true))
        assertThat(management.requests).hasSize(2)
        assertThat(app.boardingTokens).containsExactly("BT-2")
    }

    @Test
    fun `boarding steps without a usable answer say whether the Payments app may have dropped its boarding`() {
        val management = RecordingManagement()
        val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, app, management, RETURN_URL)
        app.unanswered = AppLinkAnswer.NotStarted
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant"), reboard = true) })
            .isEqualTo(Onboarding.Failed(Fault.NotStarted, unboarded = false))
        app.unanswered = AppLinkAnswer.Abandoned
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant"), reboard = true) })
            .isEqualTo(Onboarding.Failed(Fault.Abandoned, unboarded = true))
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant")) })
            .isEqualTo(Onboarding.Failed(Fault.Abandoned, unboarded = false))
        app.unanswered = null
        app.boarded = false
        management.result = ManagementResult.NotBoarded
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant")) })
            .isEqualTo(Onboarding.Failed(Fault.UnreadableReply(), unboarded = true))
        management.result = ManagementResult.Done("BT-1")
        app.reply = { opened ->
            if (opened.link.contains("/board?")) {
                "${opened.returnUrl}?boarded=true"
            } else {
                "${opened.returnUrl}?boarded=false&installationId=INSTALLATION-1&boardingRequestToken=BRT-1"
            }
        }
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant")) })
            .isEqualTo(Onboarding.Failed(Fault.UnreadableReply(), unboarded = true))
    }

    @Test
    fun `a boarding token Adyen issued for the installation confirms where it is boarded`() {
        val management = RecordingManagement()
        val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, app, management, RETURN_URL)
        app.boarded = false
        management.result = ManagementResult.Done("BT-1", "INSTALLATION-1")
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant")) })
            .isEqualTo(Onboarding.NewlyBoarded("INSTALLATION-1", confirmed = true))
        management.result = ManagementResult.Done("BT-1", "ANOTHER")
        assertThat(runBlocking { onboarding.board(BoardingTarget("Merchant"), reboard = true) })
            .isEqualTo(Onboarding.NewlyBoarded("INSTALLATION-1", confirmed = false))
    }

    @Test
    fun `the Management API is called with the Payments app key, per merchant account or store`() {
        MockWebServer().use { server ->
            server.start()
            val api = AdyenPaymentsAppManagement("pa-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1"))
            server.enqueue(MockResponse.Builder().body("""{"installationId":"I1","boardingToken":"BT"}""").build())
            assertThat(runBlocking { api.boardingToken(BoardingTarget("Merchant"), "BRT") }).isEqualTo(ManagementResult.Done("BT", "I1"))
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v1/merchants/Merchant/generatePaymentsAppBoardingToken")
            assertThat(request.headers["x-api-key"]).isEqualTo("pa-key")
            assertThat(JsonParser.parseString(request.body!!.utf8()).asJsonObject["boardingRequestToken"].asString).isEqualTo("BRT")

            server.enqueue(MockResponse.Builder().body("{}").build())
            runBlocking { api.boardingToken(BoardingTarget("Merchant", "ST1"), "BRT") }
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/merchants/Merchant/stores/ST1/generatePaymentsAppBoardingToken")

            server.enqueue(MockResponse.Builder().code(200).build())
            assertThat(runBlocking { api.revoke("Merchant", "I1") }).isEqualTo(ManagementResult.Done())
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/merchants/Merchant/paymentsApps/I1/revoke")

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("""{"detail":"Not allowed"}""")
                    .build(),
            )
            val permission = Fault.Permission(ApiKey.PAYMENTS_APP, "Adyen Payments App role")
            assertThat(runBlocking { api.revoke("Merchant", "I1") }).isEqualTo(ManagementResult.Failed(permission))
            server.enqueue(MockResponse.Builder().code(401).build())
            assertThat(
                runBlocking { api.revoke("Merchant", "I1") },
            ).isEqualTo(ManagementResult.Failed(Fault.Credential(ApiKey.PAYMENTS_APP)))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(422)
                    .body("""{"detail":"Not allowed","errorCode":"00_422"}""")
                    .build(),
            )
            assertThat(runBlocking { api.revoke("Merchant", "I1") })
                .isEqualTo(ManagementResult.Failed(Fault.AdyenRejected(422, "00_422", ExternalText("Not allowed"))))
            server.enqueue(MockResponse.Builder().code(500).build())
            assertThat(runBlocking { api.revoke("Merchant", "I1") }).isEqualTo(ManagementResult.Failed(Fault.AdyenUnavailable(500)))
        }
        val offline =
            AdyenPaymentsAppManagement(
                "pa-key",
                TerminalEnvironment.LIVE,
                baseUrl =
                    HttpUrl
                        .Builder()
                        .scheme("http")
                        .host("127.0.0.1")
                        .port(1)
                        .build(),
            )
        val failed = runBlocking { offline.revoke("Merchant", "I1") } as ManagementResult.Failed
        assertThat(failed.fault).isEqualTo(Fault.Unreachable("127.0.0.1", terminal = false))
        assertThat(AdyenPaymentsAppManagement.endpoint(TerminalEnvironment.LIVE)).isEqualTo("https://management-live.adyen.com/v1")
    }

    @Test
    fun `registration checks the intended merchant and store without boarding mutations`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("dummy-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1/"))
                val target = BoardingTarget("Merchant", "ST1")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"paymentsApps":[{"installationId":"I1","merchantAccountCode":"Merchant","status":"BOARDED"}]}""",
                        ).build(),
                )
                assertThat(api.registration(target, "I1")).isEqualTo(ManagementResult.Done())
                val request = server.takeRequest()
                assertThat(request.method).isEqualTo("GET")
                assertThat(request.url.encodedPath).isEqualTo("/v1/merchants/Merchant/stores/ST1/paymentsApps")
                assertThat(request.url.queryParameter("statuses")).isEqualTo("BOARDED")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"paymentsApps":[{"installationId":"I1","merchantAccountCode":"Other","status":"BOARDED"}]}""",
                        ).build(),
                )
                assertThat(api.registration(target, "I1")).isEqualTo(ManagementResult.NotBoarded)
                listOf(
                    "{}",
                    "not json",
                    """{"paymentsApps":[{"installationId":{}}]}""",
                    """{"paymentsApps":[null]}""",
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    assertThat(api.registration(target, "I1")).isNotInstanceOf(ManagementResult.Done::class.java)
                }
                server.enqueue(MockResponse.Builder().code(403).build())
                assertThat(api.registration(target, "I1")).isInstanceOf(ManagementResult.Failed::class.java)
            }
        }

    @Test
    fun `registration pagination is bounded and never follows server-provided links`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("dummy-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1/"))
                val full = """{"paymentsApps":[${List(
                    100,
                ) { """{"installationId":"Other","merchantAccountCode":"Merchant","status":"BOARDED"}""" }.joinToString(",")}]}"""
                repeat(100) { server.enqueue(MockResponse.Builder().body(full).build()) }
                assertThat(api.registration(BoardingTarget("Merchant"), "I1")).isEqualTo(ManagementResult.Failed(Fault.ListTooLarge))
                assertThat(server.requestCount).isEqualTo(100)
                assertThat(server.takeRequest().url.queryParameter("offset")).isEqualTo("0")
                assertThat(server.takeRequest().url.queryParameter("offset")).isEqualTo("100")
            }
            val defaultManagement =
                object : PaymentsAppManagement {
                    override suspend fun boardingToken(
                        target: BoardingTarget,
                        boardingRequestToken: String,
                    ) = ManagementResult.NotBoarded

                    override suspend fun revoke(
                        merchantAccount: String,
                        installationId: String,
                    ) = ManagementResult.NotBoarded
                }
            assertThat(defaultManagement.registration(BoardingTarget("Merchant"), "I1"))
                .isEqualTo(ManagementResult.Failed(Fault.Unsupported))
            assertThat(defaultManagement.credential()).isEqualTo(BoardingCredentialCheck.Failed(Fault.Unsupported))
            assertThat(Onboarding.AlreadyBoarded("INSTALLATION-1").installationId).isEqualTo("INSTALLATION-1")

            var stepCount = 0
            val abandonOnSecondStep =
                object : AppLinkExchange {
                    override suspend fun exchange(
                        link: String,
                        packageName: String,
                        timeout: Duration,
                    ): AppLinkAnswer =
                        if (++stepCount == 1) {
                            AppLinkAnswer.Returned("$RETURN_URL/boarded?boarded=false&installationId=I1&boardingRequestToken=BRT-1")
                        } else {
                            AppLinkAnswer.Abandoned
                        }

                    override fun lateReplies(): List<String> = emptyList()

                    override fun forgetLateReplies(urls: Set<String>) = Unit
                }
            val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, abandonOnSecondStep, RecordingManagement(), RETURN_URL)
            assertThat(onboarding.board(BoardingTarget("Merchant"))).isEqualTo(Onboarding.Failed(Fault.Abandoned, unboarded = true))
        }

    /** One link the fake Payments app was asked to open. */
    private data class Opened(
        val link: String,
        val packageName: String,
    ) {
        val parameters = FormEncoding.decode(link.substringAfter('?'))
        val returnUrl: String = URLDecoder.decode(parameters.getValue("returnUrl"), Charsets.UTF_8)
        val request: String? = parameters["request"]
    }

    /** Plays the Adyen Payments app: boards, and answers payments and refunds with the simulator. */
    private class FakePaymentsApp(
        key: TerminalKey,
    ) : AppLinkExchange {
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
        private val late = mutableListOf<String>()

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
            if (keepAnswers && answer is AppLinkAnswer.Returned) late += answer.url
            val financial = requests.lastOrNull()?.saleToPOIRequest?.let { it.paymentRequest != null || it.reversalRequest != null } == true
            return if (keepAnswers || (loseFinancialReplies && financial)) AppLinkAnswer.TimedOut else answer
        }

        override fun lateReplies(): List<String> = late

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
    }

    /** Records boarding token requests and answers with [result]. */
    private class RecordingManagement : PaymentsAppManagement {
        val requests = mutableListOf<Pair<BoardingTarget, String>>()
        var result: ManagementResult = ManagementResult.Done("BT-1")

        override suspend fun boardingToken(
            target: BoardingTarget,
            boardingRequestToken: String,
        ): ManagementResult {
            requests += target to boardingRequestToken
            return result
        }

        override suspend fun revoke(
            merchantAccount: String,
            installationId: String,
        ): ManagementResult = result
    }

    @Test
    fun `unencryptable request fails with RequestNotEncrypted before invoking app link`() =
        runBlocking {
            val unencryptable =
                object : TerminalAPIRequest() {
                    private var reads = 0

                    override fun getSaleToPOIRequest(): SaleToPOIRequest? =
                        if (reads++ == 0) SaleToPOIRequest().apply { paymentRequest = PaymentRequest() } else null
                }
            assertThat(transport.send(unencryptable, 5.seconds)).isEqualTo(Delivery.Failed(Fault.RequestNotEncrypted))
        }

    private companion object {
        const val RETURN_URL = "minimpos://paymentsapp"
    }
}

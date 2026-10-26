package app.minimpos.terminal

import app.minimpos.terminal.client.GiftCardAccount
import app.minimpos.terminal.client.GiftCardBalance
import app.minimpos.terminal.client.GiftCardEntryMode
import app.minimpos.terminal.client.GiftCardPayment
import app.minimpos.terminal.client.GiftCardUse
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.StoredValueOperation
import app.minimpos.terminal.client.StoredValueParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.client.balance
import app.minimpos.terminal.client.storedValue
import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.simulator.SimulatedGiftCards
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.simulator.answerStoredValue
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.BalanceInquiryResponse
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.StoredValueAccountType
import com.adyen.model.nexo.StoredValueData
import com.adyen.model.nexo.StoredValueRequest
import com.adyen.model.nexo.StoredValueResponse
import com.adyen.model.nexo.StoredValueTransactionType
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

class GiftCardsTest {
    private class MovingClock(
        var now: Instant = Instant.parse("2026-10-25T10:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovingClock()
    private var config = SimulatorConfig(delayMillis = 0)
    private val simulator = TerminalSimulator(config = { config }, clock = clock, zone = ZoneOffset.UTC)
    private val sent = mutableListOf<TerminalAPIRequest>()

    private val client =
        TerminalClient(
            transport =
                TerminalTransport { request, timeout ->
                    sent += request
                    simulator.send(request, timeout)
                },
            identity = TerminalIdentity("POS1", "AMS1-123"),
            clock = clock,
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
            currencyDigits = testCurrencyDigits,
        )

    private fun swiped(provider: String = "svs") = GiftCardAccount.onTerminal(provider, GiftCardEntryMode.MAG_STRIPE)

    private fun details(outcome: TransactionOutcome): TransactionDetails = (outcome as TransactionOutcome.Completed).details

    private suspend fun stored(
        operation: StoredValueOperation,
        amountMinor: Long,
        account: GiftCardAccount? = swiped(),
    ) = details(client.storedValue(StoredValueParams(operation, account, amountMinor, "EUR", "GC-1")))

    private suspend fun pay(
        use: GiftCardUse,
        amountMinor: Long,
        account: GiftCardAccount = swiped(),
        partial: Boolean = false,
    ) = details(
        client.pay(
            PaymentParams(amountMinor, "EUR", "GC-P", giftCard = GiftCardPayment(account, use), allowPartialAuthorisation = partial),
        ),
    )

    @Test
    fun `scanned numbers are transient, validated and never shown`() =
        runTest {
            val card = GiftCardAccount.scanned("givex", "6006491234567890", "1228")
            assertThat(card.lastFour).isEqualTo("7890")
            assertThat(card.toString()).doesNotContain("6006")
            assertThat(card.expiryDate).isEqualTo("1228")
            card.discard()
            assertThrows(IllegalStateException::class.java) { card.toNexo() }
            assertThat(sent).isEmpty()
            validation()
        }

    private fun validation() {
        assertThrows(IllegalArgumentException::class.java) { GiftCardAccount.scanned("givex", "12a4", "1228") }
        assertThrows(IllegalArgumentException::class.java) { GiftCardAccount.scanned("givex", "123", "1228") }
        assertThrows(IllegalArgumentException::class.java) { GiftCardAccount.scanned("givex", "1234", "1328") }
        assertThrows(IllegalArgumentException::class.java) { GiftCardAccount.scanned("Givex", "1234", "1228") }
        assertThrows(IllegalArgumentException::class.java) { GiftCardAccount.onTerminal("svs", GiftCardEntryMode.SCANNED) }
        assertThat(swiped().lastFour).isNull()
    }

    @Test
    fun `parameters only combine as Adyen documents`() {
        val card = swiped()
        assertThrows(IllegalArgumentException::class.java) { StoredValueParams(StoredValueOperation.UNDO_LOAD, card, 0, "EUR", "R") }
        assertThrows(IllegalArgumentException::class.java) { StoredValueParams(StoredValueOperation.LOAD, null, 10, "EUR", "R") }
        assertThrows(IllegalArgumentException::class.java) {
            StoredValueParams(StoredValueOperation.LOAD, card, 10, "EUR", "R", ".PSP", "2026-10-25T10:00:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) { StoredValueParams(StoredValueOperation.LOAD, card, 0, "EUR", "R") }
        assertThrows(IllegalArgumentException::class.java) { StoredValueParams(StoredValueOperation.ACTIVATE, card, -1, "EUR", "R") }
        assertThrows(IllegalArgumentException::class.java) { StoredValueParams(StoredValueOperation.DEACTIVATE, card, 5, "EUR", "R") }
        assertThrows(IllegalArgumentException::class.java) {
            StoredValueParams(StoredValueOperation.UNDO_LOAD, null, 0, "EUR", "R", ".PSP", null)
        }
        val giftCard = GiftCardPayment(card, GiftCardUse.CASH_OUT)
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "EUR", "R", giftCard = giftCard, allowPartialAuthorisation = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "EUR", "R", giftCard = GiftCardPayment(card, GiftCardUse.REDEEM), askGiving = true)
        }
        PaymentParams(100, "EUR", "R", giftCard = GiftCardPayment(card, GiftCardUse.REDEEM), allowPartialAuthorisation = true)
    }

    @Test
    fun `activation, load and balance use the documented requests`() =
        runTest {
            val activated = stored(StoredValueOperation.ACTIVATE, 1000)
            assertThat(activated.success).isTrue()
            assertThat(activated.balanceMinor).isEqualTo(1000)
            assertThat(activated.maskedPan).isEqualTo("**** 0042")
            assertThat(activated.paymentBrand).isEqualTo("svs")
            val request = sent.last().saleToPOIRequest
            assertThat(request.messageHeader.messageCategory).isEqualTo(MessageCategoryType.STORED_VALUE)
            val data = request.storedValueRequest.storedValueData.single()
            assertThat(data.storedValueTransactionType).isEqualTo(StoredValueTransactionType.ACTIVATE)
            assertThat(data.storedValueAccountID.storedValueAccountType).isEqualTo(StoredValueAccountType.GIFT_CARD)
            assertThat(data.storedValueAccountID.entryMode).containsExactly(EntryModeType.MAG_STRIPE)
            assertThat(data.storedValueAccountID.storedValueID).isEmpty()
            assertThat(data.itemAmount.toPlainString()).isEqualTo("10.00")

            val loaded = stored(StoredValueOperation.LOAD, 500, GiftCardAccount.onTerminal("svs", GiftCardEntryMode.KEYED))
            assertThat(loaded.balanceMinor).isEqualTo(1500)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.storedValueRequest.storedValueData
                    .single()
                    .storedValueAccountID.entryMode,
            ).containsExactly(EntryModeType.KEYED)

            val balance = client.balance(swiped())
            assertThat(balance).isEqualTo(GiftCardBalance.Read(1500, "EUR", "0042"))
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.messageHeader.messageCategory,
            ).isEqualTo(MessageCategoryType.BALANCE_INQUIRY)
        }

    @Test
    fun `an undo refers to the load by its PSP reference and only once`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 1000)
            val loaded = stored(StoredValueOperation.LOAD, 500)
            val undo =
                details(
                    client.storedValue(
                        StoredValueParams(
                            StoredValueOperation.UNDO_LOAD,
                            null,
                            500,
                            "EUR",
                            "GC-U",
                            ".${checkNotNull(loaded.pspReference)}",
                            loaded.poiTimestamp,
                        ),
                    ),
                )
            assertThat(undo.success).isTrue()
            assertThat(undo.balanceMinor).isEqualTo(1000)
            assertThat(undo.maskedPan).isNull()
            val reverse =
                sent
                    .last()
                    .saleToPOIRequest.storedValueRequest.storedValueData
                    .single()
            assertThat(reverse.storedValueTransactionType).isEqualTo(StoredValueTransactionType.REVERSE)
            assertThat(reverse.originalPOITransaction.poiTransactionID.transactionID).isEqualTo(".${loaded.pspReference}")
            assertThat(reverse.itemAmount).isNull()
            assertThat(reverse.storedValueAccountID).isNull()

            val again =
                client.storedValue(
                    StoredValueParams(
                        StoredValueOperation.UNDO_LOAD,
                        null,
                        500,
                        "EUR",
                        "GC-U",
                        ".${loaded.pspReference}",
                        loaded.poiTimestamp,
                    ),
                )
            assertThat(details(again).success).isFalse()
            assertThat(details(again).refusalReason).contains("most recent load")
        }

    @Test
    fun `only the most recent load within 24 hours can be undone`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 0)
            val first = stored(StoredValueOperation.LOAD, 300)
            val second = stored(StoredValueOperation.LOAD, 200)

            fun undo(load: TransactionDetails) =
                StoredValueParams(
                    StoredValueOperation.UNDO_LOAD,
                    null,
                    0,
                    "EUR",
                    "U",
                    ".${checkNotNull(load.pspReference)}",
                    load.poiTimestamp!!,
                )
            assertThat(details(client.storedValue(undo(first))).success).isFalse()
            clock.now = clock.now.plusSeconds(25.hours.inWholeSeconds)
            assertThat(details(client.storedValue(undo(second))).success).isFalse()
            assertThat(details(client.storedValue(undo(second.copy(pspReference = "UNKNOWN")))).success).isFalse()
        }

    @Test
    fun `undo refuses when the loaded value was already spent`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 0)
            val load = stored(StoredValueOperation.LOAD, 300)
            pay(GiftCardUse.REDEEM, 200)
            val undo =
                StoredValueParams(
                    StoredValueOperation.UNDO_LOAD,
                    null,
                    0,
                    "EUR",
                    "U",
                    ".${checkNotNull(load.pspReference)}",
                    load.poiTimestamp!!,
                )
            assertThat(details(client.storedValue(undo)).refusalReason).isEqualTo("Not enough balance")
        }

    @Test
    fun `redemption is partial only when allowed and declines without balance`() =
        runTest {
            assertThat(pay(GiftCardUse.REDEEM, 100).refusalReason).contains("not active")
            stored(StoredValueOperation.ACTIVATE, 1000)
            assertThat(pay(GiftCardUse.REDEEM, 1500).refusalReason).isEqualTo("Not enough balance")
            val partial = pay(GiftCardUse.REDEEM, 1500, partial = true)
            assertThat(partial.partial).isTrue()
            assertThat(partial.amountMinor).isEqualTo(1000)
            assertThat(partial.maskedPan).isNull()
            val payment = sent.last().saleToPOIRequest.paymentRequest
            assertThat(payment.paymentData.paymentInstrumentData.storedValueAccountID.storedValueProvider).isEqualTo("svs")
            assertThat(payment.paymentData.paymentType).isEqualTo(PaymentType.NORMAL)
            assertThat(payment.saleData.saleToAcquirerData.tenderOption).contains("AllowPartialAuthorisation")
            assertThat(pay(GiftCardUse.REDEEM, 100, partial = true).success).isFalse()
            assertThat(pay(GiftCardUse.REDEEM, 100, GiftCardAccount.onTerminal("givex", GiftCardEntryMode.KEYED)).success).isFalse()
        }

    @Test
    fun `currency must match the card`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 1000)
            val usd = { op: StoredValueOperation -> StoredValueParams(op, swiped(), 100, "USD", "C") }
            assertThat(details(client.storedValue(usd(StoredValueOperation.LOAD))).success).isFalse()
            val other = { use: GiftCardUse -> PaymentParams(100, "USD", "C", giftCard = GiftCardPayment(swiped(), use)) }
            GiftCardUse.entries.forEach { assertThat(details(client.pay(other(it))).success).isFalse() }
        }

    @Test
    fun `cash out pays the whole balance, partially only for Givex, and refunds credit the card`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 1000)
            assertThat(pay(GiftCardUse.CASH_OUT, 400).refusalReason).contains("whole balance")
            assertThat(pay(GiftCardUse.CASH_OUT, 1400).refusalReason).isEqualTo("Not enough balance")
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.paymentRequest.saleData.saleToAcquirerData.redemptionType,
            ).isEqualTo("cashback")
            val credited = pay(GiftCardUse.REFUND, 250)
            assertThat(credited.amountMinor).isEqualTo(250)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.paymentRequest.paymentData.paymentType,
            ).isEqualTo(PaymentType.REFUND)
            assertThat(pay(GiftCardUse.CASH_OUT, 1250).success).isTrue()
            assertThat(client.balance(swiped())).isInstanceOf(GiftCardBalance.Refused::class.java)

            val givex = GiftCardAccount.onTerminal("givex", GiftCardEntryMode.MAG_STRIPE)
            stored(StoredValueOperation.ACTIVATE, 1000, givex)
            assertThat(pay(GiftCardUse.CASH_OUT, 400, givex).success).isTrue()
            assertThat(client.balance(givex)).isEqualTo(GiftCardBalance.Read(600, "EUR", "0042"))
        }

    @Test
    fun `deactivation is Givex only`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 1000)
            assertThat(stored(StoredValueOperation.DEACTIVATE, 0).refusalReason).contains("Not supported")
            val givex = GiftCardAccount.scanned("givex", "6006491234567890", "1228")
            assertThat(stored(StoredValueOperation.DEACTIVATE, 0, givex).refusalReason).contains("not active")
            val again = { GiftCardAccount.scanned("givex", "6006491234567890", "1228") }
            val activated = stored(StoredValueOperation.ACTIVATE, 500, again())
            assertThat(activated.maskedPan).isEqualTo("**** 7890")
            assertThat(stored(StoredValueOperation.ACTIVATE, 500, again()).refusalReason).contains("already active")
            val off = stored(StoredValueOperation.DEACTIVATE, 0, again())
            assertThat(off.success).isTrue()
            assertThat(off.balanceMinor).isEqualTo(0)
            assertThat(stored(StoredValueOperation.LOAD, 100, again()).refusalReason).contains("not active")
            assertThat(pay(GiftCardUse.REFUND, 100, again()).success).isFalse()
            assertThat(pay(GiftCardUse.CASH_OUT, 100, again()).success).isFalse()
            val scanned =
                sent
                    .last()
                    .saleToPOIRequest.paymentRequest.paymentData.paymentInstrumentData.storedValueAccountID
            assertThat(scanned.storedValueID).isEqualTo("6006491234567890")
            assertThat(scanned.expiryDate).isEqualTo("1228")
        }

    @Test
    fun `a referenced refund of a redemption credits the card again`() =
        runTest {
            stored(StoredValueOperation.ACTIVATE, 1000)
            val payment = pay(GiftCardUse.REDEEM, 600)
            val refund =
                client.refund(
                    RefundParams(checkNotNull(payment.poiTransactionId), checkNotNull(payment.poiTimestamp), "R-1", 200, "EUR"),
                )
            assertThat(details(refund).success).isTrue()
            assertThat(client.balance(swiped())).isEqualTo(GiftCardBalance.Read(600, "EUR", "0042"))
        }

    @Test
    fun `declines, cancellations and lost answers keep the documented outcomes`() =
        runTest {
            config = config.copy(outcome = SimulatedOutcome.DECLINE)
            assertThat(stored(StoredValueOperation.ACTIVATE, 100).success).isFalse()
            assertThat(pay(GiftCardUse.REDEEM, 100).success).isFalse()
            assertThat(client.balance(swiped())).isInstanceOf(GiftCardBalance.Refused::class.java)
            config = config.copy(outcome = SimulatedOutcome.CANCEL)
            assertThat(stored(StoredValueOperation.ACTIVATE, 100).decline?.cancelled).isTrue()

            config = config.copy(outcome = SimulatedOutcome.TIMEOUT)
            val activated =
                details(client.storedValue(StoredValueParams(StoredValueOperation.ACTIVATE, swiped(), 900, "EUR", "T"), "SVTIME01"))
            assertThat(activated.success).isTrue()
            assertThat(activated.balanceMinor).isEqualTo(900)
            val status =
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference
            assertThat(status.messageCategory).isEqualTo(MessageCategoryType.STORED_VALUE)
            assertThat(status.serviceID).isEqualTo("SVTIME01")
            assertThat(sent.count { it.saleToPOIRequest.storedValueRequest != null }).isEqualTo(3)
            assertThat(client.balance(swiped())).isInstanceOf(GiftCardBalance.Failed::class.java)
            val redeemed = pay(GiftCardUse.REDEEM, 100)
            assertThat(redeemed.success).isTrue()

            config = config.copy(outcome = SimulatedOutcome.APPROVE)
            assertThat(details(client.status("SVTIME01", TransactionKind.STORED_VALUE)).balanceMinor).isEqualTo(900)
            assertThat(client.status("MISSING", TransactionKind.STORED_VALUE)).isEqualTo(TransactionOutcome.NotProcessed(Fault.NoRecord))
        }

    @Test
    fun `aborting a stored value request reaches the waiting terminal`() =
        runTest {
            config = config.copy(delayMillis = 60_000)
            val outcome =
                coroutineScope {
                    val running =
                        async {
                            client.storedValue(StoredValueParams(StoredValueOperation.ACTIVATE, swiped(), 100, "EUR", "A"), "SVABORT1")
                        }
                    delay(200)
                    client.abort("SVABORT1", TransactionKind.STORED_VALUE)
                    running.await()
                }
            assertThat(details(outcome).errorCondition).isEqualTo(ErrorConditionType.ABORTED.value())
            assertThat(
                sent
                    .first { it.saleToPOIRequest.abortRequest != null }
                    .saleToPOIRequest.abortRequest.messageReference.messageCategory,
            ).isEqualTo(MessageCategoryType.STORED_VALUE)
        }

    @Test
    fun `unreadable answers stay unknown or unread`() =
        runTest {
            fun reply(response: SaleToPOIResponse) =
                TerminalClient(
                    transport =
                        TerminalTransport {
                            _,
                            _,
                            ->
                            Delivery.Answered(TerminalAPIResponse().apply { saleToPOIResponse = response })
                        },
                    identity = TerminalIdentity("POS1", "AMS1-123"),
                    recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
                    currencyDigits = testCurrencyDigits,
                )
            val empty = SaleToPOIResponse().apply { storedValueResponse = StoredValueResponse().apply { response = Response() } }
            val outcome = reply(empty).storedValue(StoredValueParams(StoredValueOperation.ACTIVATE, swiped(), 0, "EUR", "X"))
            assertThat(outcome).isInstanceOf(TransactionOutcome.Unknown::class.java)
            val noResult = SaleToPOIResponse().apply { balanceInquiryResponse = BalanceInquiryResponse() }
            assertThat(reply(noResult).balance(swiped())).isEqualTo(GiftCardBalance.Failed(Fault.UnreadableReply()))
            val noBalance =
                SaleToPOIResponse().apply {
                    balanceInquiryResponse = BalanceInquiryResponse().apply { response = Response().apply { result = ResultType.SUCCESS } }
                }
            assertThat(reply(noBalance).balance(swiped())).isEqualTo(GiftCardBalance.Failed(Fault.UnreadableReply()))
        }

    @Test
    fun `the gift card ledger refuses unsupported and absent operation types without changing value`() {
        val ledger = SimulatedGiftCards(clock)
        listOf(StoredValueTransactionType.RESERVE, StoredValueTransactionType.DUPLICATE, null).forEach { type ->
            val data = StoredValueData().apply { storedValueTransactionType = type }
            val refusal = ledger.answerStoredValue(data, "PSP") as SimulatedGiftCards.Answer.Refused
            assertThat(refusal.refusal).isEqualTo("Unsupported stored value operation (simulated)")
        }
        assertThat(ledger.answerStoredValue(null, "PSP")).isInstanceOf(SimulatedGiftCards.Answer.Refused::class.java)
    }

    @Test
    fun `the simulator refuses stored value operations it does not take`() =
        runTest {
            val request =
                TerminalAPIRequest().apply {
                    saleToPOIRequest =
                        SaleToPOIRequest().apply {
                            messageHeader = MessageHeader().apply { serviceID = "RESERVE1" }
                            storedValueRequest =
                                StoredValueRequest().apply {
                                    storedValueData +=
                                        StoredValueData().apply { storedValueTransactionType = StoredValueTransactionType.RESERVE }
                                }
                        }
                }
            val answer = (simulator.send(request, 1.hours) as Delivery.Answered).response
            val result = answer?.saleToPOIResponse?.storedValueResponse?.response
            assertThat(result?.result).isEqualTo(ResultType.FAILURE)
            assertThat(AdditionalResponseParser.parse(result?.additionalResponse)["refusalReason"])
                .isEqualTo("Unsupported stored value operation (simulated)")
        }
}

package io.minimpos.terminal

import com.adyen.model.nexo.MessageHeader
import com.adyen.model.terminal.SaleToAcquirerData
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.security.SaleToPOISecuredMessage
import com.adyen.model.terminal.security.SecurityTrailer
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.security.exception.NexoCryptoException
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.minimpos.terminal.client.PaymentParams
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.client.RecoveryPolicy
import io.minimpos.terminal.client.RecurringModel
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TerminalIdentity
import io.minimpos.terminal.transport.Delivery
import io.minimpos.terminal.transport.TerminalKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.xml.datatype.DatatypeFactory

/** Pins the behaviour of the official Adyen library that Mini mPOS relies on. */
class AdyenLibraryTest {
    // Vectors generated independently with Python hashlib/hmac and `openssl enc -aes-256-cbc`.
    private val key = TerminalKey(keyIdentifier = "mykey", passphrase = "mysupersecretpassphrase", keyVersion = 1)
    private val plaintext = """{"SaleToPOIRequest":{"hello":"world"}}"""
    private val nonce = ByteArray(16) { it.toByte() }
    private val expectedBlob = "gP0vnBpYJLc06akeJiZwNBNfEbJ150PkxQyPMNvwCouKFt21HOha6s6cYXki3gt0"
    private val expectedHmac = "gvfkFGl2YOtUUksWgbZbwn9lzFt4mB/Vber+JI12zSg="

    private fun secured(
        blob: String = expectedBlob,
        hmac: ByteArray = Base64.getDecoder().decode(expectedHmac),
    ) = SaleToPOISecuredMessage().apply {
        nexoBlob = blob
        securityTrailer =
            SecurityTrailer().apply {
                this.nonce = this@AdyenLibraryTest.nonce
                this.hmac = hmac
                keyIdentifier = "mykey"
                keyVersion = 1
                adyenCryptoVersion = 1
            }
    }

    @Test
    fun `Adyen NexoCrypto decrypts the reference vectors`() {
        assertThat(NexoCrypto(key.toSecurityKey()).decrypt(secured())).isEqualTo(plaintext)
    }

    @Test
    fun `Adyen NexoCrypto rejects tampering and wrong keys`() {
        val tampered = Base64.getDecoder().decode(expectedHmac).also { it[0] = (it[0] + 1).toByte() }
        assertThrows(NexoCryptoException::class.java) { NexoCrypto(key.toSecurityKey()).decrypt(secured(hmac = tampered)) }
        val wrong = TerminalKey("mykey", "another passphrase", 1).toSecurityKey()
        assertThrows(Exception::class.java) { NexoCrypto(wrong).decrypt(secured()) }
    }

    @Test
    fun `encryption round trips and carries the key details`() {
        val crypto = NexoCrypto(TerminalKey("key-id", "correct horse battery staple", 3).toSecurityKey())
        val message = crypto.encrypt(plaintext, MessageHeader())
        assertThat(message.securityTrailer.keyIdentifier).isEqualTo("key-id")
        assertThat(message.securityTrailer.keyVersion).isEqualTo(3)
        assertThat(message.securityTrailer.adyenCryptoVersion).isEqualTo(1)
        assertThat(crypto.decrypt(message)).isEqualTo(plaintext)
    }

    @Test
    fun `terminal keys are validated and never print the passphrase`() {
        assertThrows(IllegalArgumentException::class.java) { TerminalKey(" ", "pass", 1) }
        assertThrows(IllegalArgumentException::class.java) { TerminalKey("id", "", 1) }
        assertThrows(IllegalArgumentException::class.java) { TerminalKey("id", "pass", 0) }
        assertThat(TerminalKey("id", "secret", 2).toString()).doesNotContain("secret")
    }

    @Test
    fun `a DatatypeFactory implementation is on the classpath for Android`() {
        // Android has javax.xml.datatype but no implementation; the nexo models need one for every TimeStamp.
        assertThat(DatatypeFactory.newInstance().javaClass.name).startsWith("org.apache.xerces.")
    }

    @Test
    fun `payment requests serialise as Adyen documents them`() {
        var sent: TerminalAPIRequest? = null
        val client =
            TerminalClient(
                transport = { request, _ ->
                    sent = request
                    Delivery.Answered(null)
                },
                identity = TerminalIdentity("MiniMPOS", "S1F2-000158213605014"),
                application = PosApplication("Mini mPOS", "1.2.0", "Mini mPOS", "Android", "13"),
                clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC),
                newServiceId = { "SVC1" },
                recovery = RecoveryPolicy(attempts = 0),
            )
        runBlocking {
            client.pay(
                PaymentParams(
                    amount = BigDecimal("12.50"),
                    currency = "AUD",
                    merchantReference = "MP-1",
                    shopperReference = "CUST-1",
                    recurringProcessingModel = RecurringModel.UNSCHEDULED_CARD_ON_FILE,
                    tenderOptions = listOf("ReceiptHandler"),
                ),
            )
        }
        val json = JsonParser.parseString(TerminalAPIGsonBuilder.create().toJson(sent)).asJsonObject
        val request = json.getAsJsonObject("SaleToPOIRequest")
        val header = request.getAsJsonObject("MessageHeader")
        assertThat(header["ProtocolVersion"].asString).isEqualTo("3.0")
        assertThat(header["MessageClass"].asString).isEqualTo("Service")
        assertThat(header["MessageCategory"].asString).isEqualTo("Payment")
        assertThat(header["POIID"].asString).isEqualTo("S1F2-000158213605014")
        val payment = request.getAsJsonObject("PaymentRequest")
        val amounts = payment.getAsJsonObject("PaymentTransaction").getAsJsonObject("AmountsReq")
        assertThat(amounts["RequestedAmount"].asJsonPrimitive.isNumber).isTrue()
        assertThat(amounts["RequestedAmount"].asBigDecimal).isEqualTo(BigDecimal("12.50"))
        val saleData = payment.getAsJsonObject("SaleData")
        assertThat(saleData.getAsJsonObject("SaleTransactionID")["TimeStamp"].asString).isEqualTo("2026-09-30T01:02:03.456Z")
        val acquirer = JsonParser.parseString(String(Base64.getDecoder().decode(saleData["SaleToAcquirerData"].asString))).asJsonObject
        assertThat(acquirer["shopperReference"].asString).isEqualTo("CUST-1")
        assertThat(acquirer["recurringProcessingModel"].asString).isEqualTo("UnscheduledCardOnFile")
        assertThat(acquirer["tenderOption"].asString).isEqualTo("ReceiptHandler")
        val info = acquirer.getAsJsonObject("applicationInfo")
        assertThat(info.getAsJsonObject("merchantApplication")["name"].asString).isEqualTo("Mini mPOS")
        assertThat(info.getAsJsonObject("merchantApplication")["version"].asString).isEqualTo("1.2.0")
        assertThat(info.getAsJsonObject("externalPlatform")["name"].asString).isEqualTo("Mini mPOS")
        assertThat(info.getAsJsonObject("externalPlatform")["integrator"].asString).isEqualTo("Mini mPOS")
        assertThat(info.getAsJsonObject("merchantDevice")["os"].asString).isEqualTo("Android")
        assertThat(info.getAsJsonObject("merchantDevice")["osVersion"].asString).isEqualTo("13")
        assertThat(info.getAsJsonObject("adyenLibrary")["name"].asString).isEqualTo("adyen-java-api-library")
    }

    @Test
    fun `our recurring models are the library's, and the application summary shows what is sent`() {
        assertThat(RecurringModel.entries.map { it.value })
            .containsExactlyElementsIn(SaleToAcquirerData.RecurringProcessingModelEnum.entries.map { it.toString() })
            .inOrder()
        assertThat(RecurringModel.entries.map { it.name })
            .containsExactlyElementsIn(SaleToAcquirerData.RecurringProcessingModelEnum.entries.map { it.name })
        val summary = PosApplication("Mini mPOS!", "1.2.0", "  ", "Android", "13").summary()
        assertThat(summary.application).isEqualTo("Mini mPOS 1.2.0")
        assertThat(summary.platform).isEqualTo("Mini mPOS 1.2.0")
        assertThat(summary.integrator).isNull()
        assertThat(summary.device).isEqualTo("Android 13")
        assertThat(summary.library).startsWith("adyen-java-api-library ")
    }
}

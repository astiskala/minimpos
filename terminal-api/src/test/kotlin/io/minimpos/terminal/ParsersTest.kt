package io.minimpos.terminal

import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.PaymentReceipt
import com.google.common.truth.Truth.assertThat
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.client.RetryAdvice
import io.minimpos.terminal.parse.AdditionalResponseParser
import io.minimpos.terminal.parse.FormEncoding
import io.minimpos.terminal.parse.ReceiptField
import io.minimpos.terminal.parse.ReceiptParser
import org.junit.Test
import java.util.Base64

class ParsersTest {
    private fun receipt(
        qualifier: DocumentQualifierType,
        signature: Boolean?,
        vararg lines: Pair<String, CharacterStyleType?>,
    ) = PaymentReceipt().apply {
        documentQualifier = qualifier
        signature?.let { setRequiredSignatureFlag(it) }
        outputContent =
            OutputContent().apply {
                outputText =
                    lines.map { (text, style) ->
                        OutputText().apply {
                            this.text = text
                            characterStyle = style
                        }
                    }
            }
    }

    @Test
    fun `receipt lines are URL-decoded key-name-value triples`() {
        val receipts =
            listOf(
                receipt(
                    DocumentQualifierType.CASHIER_RECEIPT,
                    true,
                    "key=header1&name=Shop%20name" to null,
                    "key=approved&name=APPROVED" to CharacterStyleType.BOLD,
                ),
                receipt(DocumentQualifierType.CUSTOMER_RECEIPT, null, "key=totalAmount&name=TOTAL&value=AUD%2012.00" to null),
            )
        assertThat(ReceiptParser.fields(receipts, DocumentQualifierType.CASHIER_RECEIPT))
            .containsExactly(ReceiptField("header1", "Shop name", null, false), ReceiptField("approved", "APPROVED", null, true))
            .inOrder()
        assertThat(ReceiptParser.fields(receipts, DocumentQualifierType.CUSTOMER_RECEIPT).single().value).isEqualTo("AUD 12.00")
        assertThat(ReceiptParser.fields(receipts, DocumentQualifierType.JOURNAL)).isEmpty()
        assertThat(ReceiptParser.fields(null, DocumentQualifierType.JOURNAL)).isEmpty()
        assertThat(ReceiptParser.signatureRequired(receipts)).isTrue()
        assertThat(ReceiptParser.signatureRequired(receipts.drop(1))).isFalse()
        val empty = PaymentReceipt().apply { documentQualifier = DocumentQualifierType.DOCUMENT }
        assertThat(ReceiptParser.fields(listOf(empty), DocumentQualifierType.DOCUMENT)).isEmpty()
    }

    @Test
    fun `form encoding tolerates odd input`() {
        assertThat(FormEncoding.decode("a=1&&b&c=%zz&d=x%3Dy")).containsExactly("a", "1", "b", "", "c", "%zz", "d", "x=y")
    }

    @Test
    fun `additional response supports form and base64 JSON`() {
        assertThat(AdditionalResponseParser.parse(null)).isEmpty()
        assertThat(AdditionalResponseParser.parse(" ")).isEmpty()
        assertThat(AdditionalResponseParser.parse("pspReference=ABC&message=Hello%20there"))
            .containsExactly("pspReference", "ABC", "message", "Hello there")
        val json =
            """{"additionalData":{"pspReference":"XYZ","tokenization":{"storedPaymentMethodId":"123"}},""" +
                """"message":"ok","n":null,"list":[1]}"""
        assertThat(AdditionalResponseParser.parse(Base64.getEncoder().encodeToString(json.toByteArray())))
            .containsExactly("pspReference", "XYZ", "tokenization.storedPaymentMethodId", "123", "message", "ok", "list", "[1]")
        // Base64 that is not JSON, and plain words, fall back to form decoding.
        assertThat(AdditionalResponseParser.parse(Base64.getEncoder().encodeToString("hello".toByteArray()))).hasSize(1)
        assertThat(AdditionalResponseParser.parse(Base64.getEncoder().encodeToString("{broken".toByteArray()))).hasSize(1)
        assertThat(AdditionalResponseParser.parse("message=Printer%20error")).containsExactly("message", "Printer error")
    }

    @Test
    fun `application info values follow Adyen's rules`() {
        assertThat(PosApplication.clean("Mini mPOS")).isEqualTo("Mini mPOS")
        assertThat(PosApplication.clean("1.2.0-beta_1")).isEqualTo("1.2.0-beta_1")
        assertThat(PosApplication.clean("  (Mini)  mPOS / Café!  ")).isEqualTo("Mini mPOS Caf")
        assertThat(PosApplication.clean("x".repeat(60))).hasLength(PosApplication.MAX_LENGTH)
        assertThat(PosApplication.clean("!!!")).isNull()
        val info = PosApplication("Mini mPOS", "1.0", "Acme POS", "Android", "13", platformName = "Acme").toApplicationInfo()
        assertThat(info.externalPlatform.name).isEqualTo("Acme")
        assertThat(info.externalPlatform.integrator).isEqualTo("Acme POS")
        assertThat(info.merchantApplication.name).isEqualTo("Mini mPOS")
        assertThat(info.merchantDevice.osVersion).isEqualTo("13")
    }

    @Test
    fun `retry advice follows Adyen's declined payment tables`() {
        fun advice(
            condition: String?,
            reason: String? = null,
        ) = RetryAdvice.forPayment(condition, reason)
        assertThat(advice("Aborted")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("WrongPIN")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("UnreachableHost")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("DeviceOut")).isEqualTo(RetryAdvice.WAIT_AND_RETRY)
        assertThat(advice("Busy")).isEqualTo(RetryAdvice.TERMINAL_BUSY)
        assertThat(advice("NotAllowed")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("MessageFormat")).isEqualTo(RetryAdvice.CHECK_SETUP)
        assertThat(advice("UnavailableService")).isEqualTo(RetryAdvice.CHECK_SETUP)
        assertThat(advice("Cancel", "Approved")).isEqualTo(RetryAdvice.DO_NOT_RETRY)
        assertThat(advice("Cancel", "Cancelled by shopper")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("InvalidCard", "Card data authentication failed")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("InvalidCard", "No savings account available on Card")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("Refusal", "Do Not Honor")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("Refusal", "Card is blocked")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("Refusal", "Not enough balance")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("Refusal")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice(null)).isEqualTo(RetryAdvice.RETRY)
    }
}

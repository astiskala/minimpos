package app.minimpos.terminal

import app.minimpos.terminal.client.Decline
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.RetryAdvice
import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.parse.FormEncoding
import app.minimpos.terminal.parse.ReceiptField
import app.minimpos.terminal.parse.ReceiptParser
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.PaymentReceipt
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.io.encoding.Base64

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
        val cashierFields = ReceiptParser.fields(receipts, DocumentQualifierType.CASHIER_RECEIPT)
        assertThat(cashierFields)
            .containsExactly(ReceiptField("header1", "Shop name", null, false), ReceiptField("approved", "APPROVED", null, true))
            .inOrder()
        assertThat(cashierFields[0].bold).isFalse()
        assertThat(cashierFields[1].bold).isTrue()
        assertThat(ReceiptParser.fields(receipts, DocumentQualifierType.CUSTOMER_RECEIPT).single().value).isEqualTo("AUD 12.00")
        assertThat(ReceiptParser.fields(receipts, DocumentQualifierType.JOURNAL)).isEmpty()
        assertThat(ReceiptParser.fields(null, DocumentQualifierType.JOURNAL)).isEmpty()
        assertThat(ReceiptParser.signatureRequired(receipts)).isTrue()
        assertThat(ReceiptParser.signatureRequired(receipts.drop(1))).isFalse()
        assertThat(ReceiptParser.signatureRequired(null)).isFalse()
        val empty = PaymentReceipt().apply { documentQualifier = DocumentQualifierType.DOCUMENT }
        assertThat(ReceiptParser.fields(listOf(empty), DocumentQualifierType.DOCUMENT)).isEmpty()
        val nullText =
            PaymentReceipt().apply {
                documentQualifier = DocumentQualifierType.DOCUMENT
                outputContent = OutputContent().apply { outputText = listOf(OutputText()) }
            }
        assertThat(ReceiptParser.fields(listOf(nullText), DocumentQualifierType.DOCUMENT))
            .containsExactly(ReceiptField(null, null, null, false))
    }

    @Test
    fun `form encoding tolerates odd input`() {
        assertThat(FormEncoding.decode("a=1&&b&c=%zz&d=x%3Dy&=emptyKey")).containsExactly(
            "a",
            "1",
            "b",
            "",
            "c",
            "%zz",
            "d",
            "x=y",
            "",
            "emptyKey",
        )
    }

    @Test
    fun `additional response supports form and base64 JSON`() {
        assertThat(AdditionalResponseParser.parse(null)).isEmpty()
        assertThat(AdditionalResponseParser.parse(" ")).isEmpty()
        assertThat(AdditionalResponseParser.parse("pspReference=ABC&message=Hello%20there"))
            .containsExactly("pspReference", "ABC", "message", "Hello there")
        val json =
            """{"additionalData":{"pspReference":"XYZ","tokenization":{"storedPaymentMethodId":"123"}},""" +
                """"nested":{"inner":"val"},"message":"ok","n":null,"list":[1]}"""
        assertThat(AdditionalResponseParser.parse(Base64.encode(json.toByteArray())))
            .containsExactly(
                "pspReference",
                "XYZ",
                "tokenization.storedPaymentMethodId",
                "123",
                "nested.inner",
                "val",
                "message",
                "ok",
                "list",
                "[1]",
            )
        val primitiveAdditional = """{"additionalData":"plain"}"""
        assertThat(AdditionalResponseParser.parse(Base64.encode(primitiveAdditional.toByteArray())))
            .containsExactly("additionalData", "plain")
        // Base64 that is not JSON, invalid base64, and plain words fall back to form decoding.
        assertThat(AdditionalResponseParser.parse("not!base64!")).containsExactly("not!base64!", "")
        assertThat(AdditionalResponseParser.parse(Base64.encode("hello".toByteArray()))).hasSize(1)
        assertThat(AdditionalResponseParser.parse(Base64.encode("{broken".toByteArray()))).hasSize(1)
        assertThat(AdditionalResponseParser.parse("message=Printer%20error")).containsExactly("message", "Printer error")
    }

    @Test
    fun `application info values follow Adyen's rules`() {
        assertThat(PosApplication.clean("Mini mPOS")).isEqualTo("Mini mPOS")
        assertThat(PosApplication.clean("1.2.0-beta_1")).isEqualTo("1.2.0-beta_1")
        assertThat(PosApplication.clean("  (Mini)  mPOS / Café!  ")).isEqualTo("Mini mPOS Caf")
        assertThat(PosApplication.clean("---abc---")).isEqualTo("abc---")
        assertThat(PosApplication.clean("a".repeat(39) + " b")).isEqualTo("a".repeat(39))
        assertThat(PosApplication.clean("x".repeat(60))).hasLength(PosApplication.MAX_LENGTH)
        assertThat(PosApplication.clean("!!!")).isNull()
        val app = PosApplication("Mini mPOS", "1.0", "Acme POS", "Android", "13", platformName = "Acme", platformVersion = "2.5")
        assertThat(app.name).isEqualTo("Mini mPOS")
        assertThat(app.version).isEqualTo("1.0")
        assertThat(app.integrator).isEqualTo("Acme POS")
        assertThat(app.osName).isEqualTo("Android")
        assertThat(app.osVersion).isEqualTo("13")
        assertThat(app.platformName).isEqualTo("Acme")
        assertThat(app.platformVersion).isEqualTo("2.5")
        val info = app.toApplicationInfo()
        assertThat(info.externalPlatform.name).isEqualTo("Acme")
        assertThat(info.externalPlatform.version).isEqualTo("2.5")
        assertThat(info.externalPlatform.integrator).isEqualTo("Acme POS")
        assertThat(info.merchantApplication.name).isEqualTo("Mini mPOS")
        assertThat(info.merchantDevice.osVersion).isEqualTo("13")
    }

    @Test
    fun `retry advice follows Adyen's declined payment tables`() {
        fun advice(
            condition: String?,
            reason: String? = null,
        ) = Decline(condition, reason).advice
        assertThat(advice("Aborted")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("NotFound")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("UnavailableDevice")).isEqualTo(RetryAdvice.WAIT_AND_RETRY)
        assertThat(advice("PaymentRestriction")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("SomethingNew")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("WrongPIN")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("UnreachableHost")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("DeviceOut")).isEqualTo(RetryAdvice.WAIT_AND_RETRY)
        assertThat(advice("Busy")).isEqualTo(RetryAdvice.TERMINAL_BUSY)
        assertThat(advice("NotAllowed")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("MessageFormat")).isEqualTo(RetryAdvice.CHECK_SETUP)
        assertThat(advice("UnavailableService")).isEqualTo(RetryAdvice.NOT_ENABLED)
        assertThat(advice("Cancel", "Approved")).isEqualTo(RetryAdvice.DO_NOT_RETRY)
        assertThat(advice("Cancel", "approved")).isEqualTo(RetryAdvice.DO_NOT_RETRY)
        assertThat(advice("Cancel", "Cancelled by shopper")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("InvalidCard", "Card data authentication failed")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("InvalidCard", "card data authentication failed")).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        assertThat(advice("InvalidCard", "No savings account available on Card")).isEqualTo(RetryAdvice.RETRY)
        listOf(
            "AID banned",
            "Always refused",
            "Amount too low to be accepted by Card Network",
            "Card is blocked",
            "Do Not Honor",
            "Issuer Suspected Fraud",
            "Pin validation not possible",
            "Restricted Card",
            "issuer suspected fraud",
        ).forEach { reason ->
            assertThat(advice("Refusal", reason)).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
        }
        assertThat(advice("Refusal", "Not enough balance")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice("Refusal")).isEqualTo(RetryAdvice.RETRY)
        assertThat(advice(null)).isNull()
    }

    @Test
    fun `a decline tells cancellations and the transaction a busy terminal is working on`() {
        assertThat(Decline.of(succeeded = true, errorCondition = "Cancel", refusalReason = null)).isNull()
        val cancelled = Decline.of(false, "Cancel", null)!!
        assertThat(cancelled.cancelled).isTrue()
        assertThat(cancelled.errorCondition).isEqualTo("Cancel")
        assertThat(cancelled.refusalReason).isNull()
        assertThat(Decline.of(false, "Aborted", null)!!.cancelled).isTrue()
        val refused = Decline.of(false, "Refusal", "Do Not Honor")!!
        assertThat(refused.cancelled).isFalse()
        assertThat(refused.refusalReason).isEqualTo("Do Not Honor")
        assertThat(Decline.of(false, null, null)!!.cancelled).isFalse()
        val busy = mapOf("serviceId" to "OTHER1")
        assertThat(Decline.of(false, "Busy", null, busy)!!.busyServiceId).isEqualTo("OTHER1")
        assertThat(Decline.of(false, "Refusal", null, busy)!!.busyServiceId).isNull()
        assertThat(Decline.of(false, "Busy", null)!!.busyServiceId).isNull()
    }
}

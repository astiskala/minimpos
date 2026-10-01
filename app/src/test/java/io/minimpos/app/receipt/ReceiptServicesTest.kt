package io.minimpos.app.receipt

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.repo.RefundedLine
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.MerchantCopyPolicy
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.data.settings.SmtpSecurity
import io.minimpos.app.email.EmailMessage
import io.minimpos.app.email.InlineImage
import io.minimpos.app.email.MailTransport
import io.minimpos.app.email.SmtpMailer
import io.minimpos.app.payment.AutoDelivery
import io.minimpos.app.payment.SalePrint
import io.minimpos.app.qr.QrCodes
import io.minimpos.core.codec.RefundQrPayload
import io.minimpos.core.receipt.Align
import io.minimpos.core.receipt.CardReceiptLine
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptElement
import io.minimpos.core.receipt.ReceiptLabels
import io.minimpos.core.receipt.TextStyle
import io.minimpos.terminal.client.PrintAlign
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.PrintLine
import io.minimpos.terminal.client.PrintStyle
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.Locale
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.MimeMultipart

@RunWith(RobolectricTestRunner::class)
class ReceiptServicesTest {
    private val env = TestEnvironment()
    private val container = env.container

    @After
    fun tearDown() = env.close()

    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 1_790_679_835_000,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_118,
            taxMinor = 82,
            totalMinor = 1_200,
            status = SaleStatus.APPROVED,
            merchantReference = "MP-1",
            customerReference = "CUST",
            poiTransactionId = "BV0q001643892070000.PSP1",
            poiTimestamp = "2026-09-29T11:04:00.000Z",
            customerReceiptJson = ReceiptLinesJson.encode(listOf(CardReceiptLine("approved", "APPROVED", null, true))),
            cashierReceiptJson = ReceiptLinesJson.encode(listOf(CardReceiptLine("sigline", "Signature", null))),
            signatureRequired = true,
        )
    private val lines =
        listOf(
            SaleLineEntity(1, "s1", 0, 1, "Latte", null, 450, 2, "GST", 10_000, 818, 82, 900),
            SaleLineEntity(2, "s1", 1, null, "Water", null, 300, 1, "Free", 0, 300, 0, 300),
        )

    private fun store() = await { container.sales.createPending(sale, lines) }

    @Test
    fun `sale receipts carry lines, tax and the refund QR code`() {
        val factory =
            ReceiptFactory(
                ReceiptLabels(),
                { Locale.forLanguageTag("en-AU") },
                { ZoneOffset.UTC },
            )
        val record = SaleWithLines(sale, lines)
        val customer = factory.sale(record, ReceiptSettings(businessName = "Cafe", addressLines = "1 St\n2 Ave"))
        assertThat(customer.elements).contains(ReceiptElement.Row("Includes GST 10%", "$0.82"))
        assertThat(customer.elements).contains(ReceiptElement.Text("APPROVED", Align.CENTER, TextStyle.BOLD))
        val qr = RefundQrPayload.decode(customer.qrCodes.single().content)!!
        assertThat(qr.transactionId).isEqualTo("BV0q001643892070000.PSP1")
        assertThat(qr.amountMinor).isEqualTo(1_200)
        val merchant = factory.sale(record, ReceiptSettings(), ReceiptCopy.MERCHANT)
        assertThat(merchant.elements).contains(ReceiptElement.Text("Signature", Align.CENTER, TextStyle.NORMAL))
        val declined = factory.sale(record.copy(sale = sale.copy(status = SaleStatus.DECLINED, taxMode = "BOGUS")), ReceiptSettings())
        assertThat(declined.qrCodes).isEmpty()
        // A sale that could not be refunded gets no refund QR code (see RefundablePaymentTest for the rules).
        assertThat(factory.sale(record.copy(sale = sale.copy(poiTimestamp = "not a time")), ReceiptSettings()).qrCodes).isEmpty()
        assertThat(factory.formatDateTime(0)).contains("70")
    }

    @Test
    fun `refund receipts list refunded items`() {
        val refund =
            RefundEntity(
                id = "r1",
                saleId = "s1",
                createdAt = 0,
                merchantReference = "MP-R",
                originalTransactionId = "T.X",
                originalTimestamp = "t",
                originalReference = null,
                currency = "AUD",
                amountMinor = 450,
                full = false,
                status = RefundStatus.REQUESTED,
                linesJson = ReceiptLinesJson.encodeRefunded(listOf(RefundedLine(1, "Latte", 1, 450, 450))),
            )
        val document = container.receiptFactory.refund(refund, ReceiptSettings())
        assertThat(document.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).containsAtLeast("Latte", "Original sale")
    }

    @Test
    fun `print renderer maps rows, dividers and QR codes to print jobs`() {
        val document =
            ReceiptDocument(
                listOf(
                    ReceiptElement.Text("Shop", Align.CENTER, TextStyle.BOLD),
                    ReceiptElement.Row("Total", "$1", TextStyle.UNDERLINE),
                    ReceiptElement.Text("right", Align.RIGHT),
                    ReceiptElement.Divider,
                    ReceiptElement.Blank,
                    ReceiptElement.Qr("MPR1*a.b*1*2*AUD", "Scan"),
                    ReceiptElement.Qr("A B"),
                ),
            )
        assertThat(PrintRenderer.jobs(document, dividerWidth = 10))
            .containsExactly(
                PrintJob.Text(
                    listOf(
                        PrintLine.Text("Shop", PrintAlign.CENTER, PrintStyle.BOLD),
                        PrintLine.Columns("Total", "$1", PrintStyle.UNDERLINE),
                        PrintLine.Text("right", PrintAlign.RIGHT),
                        PrintLine.Text("-".repeat(10), PrintAlign.CENTER),
                        PrintLine.Text(""),
                        // The caption goes above its QR code.
                        PrintLine.Text(""),
                        PrintLine.Text("Scan", PrintAlign.CENTER),
                    ),
                ),
                PrintJob.QrCode("MPR1*a.b*1*2*AUD"),
                PrintJob.QrCode("A B"),
            ).inOrder()
    }

    @Test
    fun `receipts print through the simulator, with the merchant copy as the policy says`() {
        env.useSimulator()
        store()
        val receipts = container.receipts
        // The shopper signed, so the merchant copy is due after the customer copy.
        assertThat(await { receipts.printSale("s1") }).isEqualTo(SalePrint(ActionResult.Success, merchantCopyDue = true))
        assertThat(
            container.virtualPrinter.jobs.value
                .filterIsInstance<PrintJob.QrCode>(),
        ).hasSize(1)
        container.virtualPrinter.clear()
        assertThat(await { receipts.printSale("s1", ReceiptCopy.MERCHANT) }).isEqualTo(SalePrint(ActionResult.Success))
        assertThat(
            container.virtualPrinter.jobs.value
                .filterIsInstance<PrintJob.QrCode>(),
        ).isEmpty()
        assertThat(await { receipts.printSale("missing") }.result).isInstanceOf(ActionResult.Failure::class.java)
        assertThat(await { receipts.printRefund("missing") }).isInstanceOf(ActionResult.Failure::class.java)
        assertThat(await { receipts.printDocument(ReceiptDocument(listOf(ReceiptElement.Text("Test")))) }).isEqualTo(ActionResult.Success)

        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.NEVER)) }
        assertThat(await { receipts.printSale("s1") }.merchantCopyDue).isFalse()
        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.ALWAYS)) }
        await { container.sales.update(sale.copy(signatureRequired = false)) }
        assertThat(await { receipts.printSale("s1") }.merchantCopyDue).isTrue()
        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.SIGNATURE_ONLY)) }
        assertThat(await { receipts.printSale("s1") }.merchantCopyDue).isFalse()

        env.useSimulator { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
        val failure = await { receipts.printSale("s1") }
        assertThat((failure.result as ActionResult.Failure).message).contains("no printer")
        assertThat(failure.merchantCopyDue).isFalse()
    }

    @Test
    fun `automatic delivery runs once per armed transaction, as the settings say`() {
        env.useSimulator {
            it.copy(
                receipt = it.receipt.copy(autoPrint = true),
                payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = true),
            )
        }
        await { container.terminalStatus.state.first { it.printerAvailable } }
        await { container.sales.createPending(sale.copy(shopperEmail = "a@b.co"), lines) }
        val receipts = container.receipts
        // Nothing is delivered for a sale that did not just succeed.
        assertThat(await { receipts.automationForSale("s1") }).isEqualTo(AutoDelivery())
        receipts.arm("s1")
        assertThat(await { receipts.automationForSale("s1") }).isEqualTo(AutoDelivery(print = true, emailTo = "a@b.co"))
        assertThat(await { receipts.automationForSale("s1") }).isEqualTo(AutoDelivery())

        // The email is only sent automatically when it was asked for before payment and Send automatically is on.
        env.updateSettings { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.AFTER_PAYMENT)) }
        receipts.arm("s1")
        assertThat(await { receipts.automationForSale("s1") }).isEqualTo(AutoDelivery(print = true))
        env.updateSettings {
            it.copy(
                payment = it.payment.copy(emailCapture = EmailCapture.BOTH, autoSendEmail = false),
                receipt = it.receipt.copy(autoPrint = false),
            )
        }
        receipts.arm("s1")
        assertThat(await { receipts.automationForSale("s1") }).isEqualTo(AutoDelivery())

        // Without a printer nothing is printed automatically.
        env.useSimulator { it.copy(receipt = it.receipt.copy(autoPrint = true), simulator = it.simulator.copy(hasPrinter = false)) }
        await { container.terminalStatus.state.first { !it.printerAvailable } }
        receipts.arm("r1")
        assertThat(await { receipts.automationForRefund("r1") }).isEqualTo(AutoDelivery())
        receipts.arm("missing")
        assertThat(await { receipts.automationForSale("missing") }).isEqualTo(AutoDelivery())
    }

    private fun configureEmail() {
        env.updateSettings {
            it.copy(
                email =
                    it.email.copy(
                        host = "smtp.example.com",
                        fromAddress = "shop@example.com",
                        fromName = "Shop",
                        username = "user",
                        bcc = "copy@example.com",
                    ),
                receipt = it.receipt.copy(businessName = "Corner Cafe"),
            )
        }
        await { container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
    }

    @Test
    fun `emails receipts with inline QR codes`() {
        configureEmail()
        store()
        assertThat(await { container.receipts.emailSale("s1", "shopper@example.com") }).isEqualTo(ActionResult.Success)
        val message = env.mail.sent.single()
        assertThat(message.subject).isEqualTo("Your receipt from Corner Cafe")
        assertThat(message.allRecipients.map { it.toString() }).containsExactly("shopper@example.com", "copy@example.com")
        val related = message.content as MimeMultipart
        assertThat(related.count).isEqualTo(2)
        assertThat(related.getBodyPart(1).getHeader("Content-ID").single()).isEqualTo("<qr0>")
        assertThat(
            await {
                container.sales
                    .get("s1")!!
                    .sale.emailedTo
            },
        ).isEqualTo("shopper@example.com")
        assertThat(await { container.receipts.emailSale("missing", "a@b.co") }).isInstanceOf(ActionResult.Failure::class.java)
        assertThat(await { container.receipts.emailRefund("missing", "a@b.co") }).isInstanceOf(ActionResult.Failure::class.java)
    }

    @Test
    fun `pre-authorisations and their cancellations get their own receipts and emails`() {
        configureEmail()
        await { container.sales.createPending(sale.copy(kind = SaleKind.PRE_AUTHORISATION), lines) }
        val record = await { container.sales.get("s1")!! }
        val receipt = container.receiptFactory.sale(record, ReceiptSettings())
        assertThat(receipt.elements).contains(ReceiptElement.Text("PRE-AUTHORIZATION", Align.CENTER, TextStyle.BOLD))
        assertThat(receipt.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).contains("AMOUNT HELD")
        assertThat(receipt.qrCodes).isEmpty()
        assertThat(await { container.receipts.emailSale("s1", "a@b.co") }).isEqualTo(ActionResult.Success)
        assertThat(
            env.mail.sent
                .last()
                .allText(),
        ).contains("The amount is held on your card")

        val cancellation =
            RefundEntity(
                id = "c1",
                saleId = "s1",
                createdAt = 0,
                merchantReference = "C-1",
                originalTransactionId = "T.X",
                originalTimestamp = "t",
                originalReference = "MP-1",
                currency = "AUD",
                amountMinor = 1_200,
                full = true,
                status = RefundStatus.REQUESTED,
                cancellation = true,
            )
        await { container.refundRecords.create(cancellation) }
        assertThat(container.receiptFactory.refund(cancellation, ReceiptSettings()).elements)
            .contains(ReceiptElement.Text("CANCELLATION", Align.CENTER, TextStyle.BOLD))
        assertThat(await { container.receipts.emailRefund("c1", "a@b.co") }).isEqualTo(ActionResult.Success)
        assertThat(
            env.mail.sent
                .last()
                .allText(),
        ).contains("Your pre-authorization has been canceled")
    }

    private fun Part.allText(): String =
        when (val content = content) {
            is String -> content
            is Multipart -> (0 until content.count).joinToString("\n") { content.getBodyPart(it).allText() }
            else -> ""
        }

    @Test
    fun `email validates configuration, addresses and transport errors`() {
        store()
        assertThat((await { container.receipts.sendTestEmail("a@b.co") } as ActionResult.Failure).message).contains("not set up")
        configureEmail()
        assertThat((await { container.receipts.sendTestEmail("nope") } as ActionResult.Failure).message).contains("valid email")
        assertThat(await { container.receipts.sendTestEmail("a@b.co") }).isEqualTo(ActionResult.Success)
        env.mail.failure = MessagingException("Connection refused")
        assertThat((await { container.receipts.emailSale("s1", "a@b.co") } as ActionResult.Failure).message).isEqualTo("Connection refused")
        assertThat(
            await {
                container.sales
                    .get("s1")!!
                    .sale.emailedTo
            },
        ).isNull()
    }

    @Test
    fun `smtp messages honour security settings`() {
        val mailer = SmtpMailer(MailTransport { })
        val base = EmailSettings(host = " smtp ", port = 465, fromAddress = "a@b.co")
        val plain = mailer.build(base.copy(security = SmtpSecurity.NONE), null, EmailMessage("c@d.co", "Hi", "<p>x</p>", "x"))
        assertThat(plain.session.getProperty("mail.smtp.auth")).isEqualTo("false")
        assertThat(plain.session.getProperty("mail.smtp.host")).isEqualTo("smtp")
        assertThat(plain.content).isInstanceOf(MimeMultipart::class.java)
        val ssl =
            mailer.build(
                base.copy(security = SmtpSecurity.SSL, username = "u"),
                "p",
                EmailMessage("c@d.co", "Hi", "h", "t", listOf(InlineImage("qr0", QrCodes.png("x")))),
            )
        assertThat(ssl.session.getProperty("mail.smtp.ssl.enable")).isEqualTo("true")
        assertThat(ssl.session.getProperty("mail.smtp.auth")).isEqualTo("true")
        val starttls = mailer.build(base, null, EmailMessage("c@d.co", "Hi", "h", "t"))
        assertThat(starttls.session.getProperty("mail.smtp.starttls.required")).isEqualTo("true")
        val out = ByteArrayOutputStream()
        ssl.writeTo(out)
        assertThat(out.toString()).contains("Content-ID: <qr0>")
    }

    @Test
    fun `qr codes render as bitmaps`() {
        assertThat(QrCodes.matrix("hello").width).isGreaterThan(20)
        val bitmap = QrCodes.bitmap("hello", 200)
        assertThat(bitmap.width).isAtLeast(bitmap.height)
        assertThat(QrCodes.png("hello").size).isGreaterThan(0)
    }
}

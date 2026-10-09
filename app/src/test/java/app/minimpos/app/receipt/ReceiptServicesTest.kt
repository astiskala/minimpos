package app.minimpos.app.receipt

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.repo.ReceiptLinesJson
import app.minimpos.app.data.repo.RefundedLine
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.MerchantCopyPolicy
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.data.settings.SmtpSecurity
import app.minimpos.app.email.EmailMessage
import app.minimpos.app.email.InlineImage
import app.minimpos.app.email.MailTransport
import app.minimpos.app.email.SmtpMailer
import app.minimpos.app.payment.AutoDelivery
import app.minimpos.app.payment.ReceiptOffer
import app.minimpos.app.payment.ReceiptPrint
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.qr.QrCodes
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.Align
import app.minimpos.core.receipt.CardReceiptLine
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.ReceiptLabels
import app.minimpos.core.receipt.TextStyle
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.PrintAlign
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintStyle
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
    @Config(qualifiers = "ja-rJP")
    fun `Japanese yen rendering does not override explicit receipt settings`() {
        val record = SaleWithLines(sale.copy(currency = "JPY"), lines)
        val document = container.receiptFactory.sale(record, ReceiptSettings())
        assertThat(document.elements).contains(ReceiptElement.Row("内税（GST 10%）", "￥82"))
    }

    @Test
    @Config(qualifiers = "ja-rJP")
    fun `Japanese yen receipts use stored rates without changing the charged amount`() {
        val record =
            SaleWithLines(
                sale.copy(currency = "JPY"),
                listOf(
                    lines[0].copy(name = "食品", taxRateMilliPercent = 8_000),
                    lines[1].copy(name = "商品", taxRateMilliPercent = 10_000),
                ),
            )
        val settings = await { container.settings.current() }.receipt
        val document = container.receiptFactory.sale(record, settings)
        assertThat(document.elements).containsAtLeast(
            ReceiptElement.Row("食品 ※", "￥900"),
            ReceiptElement.Text("※は軽減税率対象商品"),
            ReceiptElement.Row("8%対象（税込）", "￥900"),
            ReceiptElement.Row("10%対象（税込）", "￥300"),
            ReceiptElement.Row("合計", "￥1,200", TextStyle.BOLD),
        )
        assertThat(document.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("内税（GST 8%）")
        val qr = RefundQrPayload.decode(document.qrCodes.single().content)!!
        assertThat(qr.amountMinor).isEqualTo(sale.totalMinor)
        val otherCurrency = container.receiptFactory.sale(record.copy(sale = sale), settings)
        assertThat(otherCurrency.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).contains("食品 ※")
        assertThat(otherCurrency.elements).contains(ReceiptElement.Text("※は軽減税率対象商品"))
    }

    @Test
    @Config(qualifiers = "ja-rJP")
    fun `Japanese item refunds retain their reduced-rate mark`() {
        val refund =
            RefundEntity(
                id = "r1",
                saleId = "s1",
                createdAt = 0,
                merchantReference = "MP-R",
                originalTransactionId = "T.X",
                originalTimestamp = "t",
                originalReference = "MP-1",
                currency = "JPY",
                amountMinor = 450,
                full = false,
                status = RefundStatus.REQUESTED,
                linesJson = ReceiptLinesJson.encodeRefunded(listOf(RefundedLine(1, "食品", 1, 450, 450, 8_000))),
            )
        val document = container.receiptFactory.refund(refund, await { container.settings.current() }.receipt)
        assertThat(document.elements).containsAtLeast(
            ReceiptElement.Row("食品 ※", "￥450"),
            ReceiptElement.Text("※は軽減税率対象商品"),
        )
    }

    @Test
    fun `English receipts can use rate-only totals and a custom marked rate`() {
        val settings =
            ReceiptSettings(
                showTaxAmounts = false,
                showTaxRateTotals = true,
                markedTaxRateMilliPercent = 5_500,
                markedTaxRateMarker = "*",
                markedTaxRateNote = "* Reduced rate",
            )
        val record = SaleWithLines(sale, lines.map { it.copy(taxRateMilliPercent = 5_500) })
        val factory = ReceiptFactory(ReceiptLabels(), { Locale.forLanguageTag("en-AU") })
        val document = factory.sale(record, settings)
        assertThat(document.elements).containsAtLeast(
            ReceiptElement.Row("Latte *", "$9.00"),
            ReceiptElement.Text("* Reduced rate"),
            ReceiptElement.Row("5.5% taxable (incl. tax)", "$12.00"),
        )
        assertThat(document.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("Includes GST 5.5%")
        val sample = factory.sample(settings, CurrencySpec.of("AUD"), TaxMode.INCLUSIVE, false)
        assertThat(sample.elements).contains(ReceiptElement.Row("Custom item *", "$3.00"))
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
        assertThat(
            await { receipts.print(StoredTransaction.Sale("s1")) },
        ).isEqualTo(ReceiptPrint(ActionResult.Success, merchantCopyDue = true))
        assertThat(
            container.virtualPrinter.jobs.value
                .filterIsInstance<PrintJob.QrCode>(),
        ).hasSize(1)
        container.virtualPrinter.clear()
        assertThat(
            await { receipts.print(StoredTransaction.Sale("s1"), ReceiptCopy.MERCHANT) },
        ).isEqualTo(ReceiptPrint(ActionResult.Success))
        assertThat(
            container.virtualPrinter.jobs.value
                .filterIsInstance<PrintJob.QrCode>(),
        ).isEmpty()
        assertThat(await { receipts.print(StoredTransaction.Sale("missing")) }.result).isEqualTo(ActionResult.Missing)
        assertThat(await { receipts.print(StoredTransaction.Refund("missing")).result }).isEqualTo(ActionResult.Missing)
        assertThat(await { receipts.printDocument(ReceiptDocument(listOf(ReceiptElement.Text("Test")))) }).isEqualTo(ActionResult.Success)

        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.NEVER)) }
        assertThat(await { receipts.print(StoredTransaction.Sale("s1")) }.merchantCopyDue).isFalse()
        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.ALWAYS)) }
        await { container.database.saleDao().update(sale.copy(signatureRequired = false)) }
        assertThat(await { receipts.print(StoredTransaction.Sale("s1")) }.merchantCopyDue).isTrue()
        env.updateSettings { it.copy(receipt = it.receipt.copy(merchantCopy = MerchantCopyPolicy.SIGNATURE_ONLY)) }
        assertThat(await { receipts.print(StoredTransaction.Sale("s1")) }.merchantCopyDue).isFalse()

        env.useSimulator { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
        val failure = await { receipts.print(StoredTransaction.Sale("s1")) }
        val noPrinter = ((failure.result as ActionResult.Failed).failure as Failure.Remote).fault as Fault.TerminalRejected
        assertThat(noPrinter.said?.text).contains("no printer")
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
        assertThat(await { receipts.automation(StoredTransaction.Sale("s1")) }).isEqualTo(AutoDelivery())
        receipts.arm("s1")
        assertThat(await { receipts.automation(StoredTransaction.Sale("s1")) }).isEqualTo(AutoDelivery(print = true, emailTo = "a@b.co"))
        assertThat(await { receipts.automation(StoredTransaction.Sale("s1")) }).isEqualTo(AutoDelivery())

        // The email is only sent automatically when it was asked for before payment and Send automatically is on.
        env.updateSettings { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.AFTER_PAYMENT)) }
        receipts.arm("s1")
        assertThat(await { receipts.automation(StoredTransaction.Sale("s1")) }).isEqualTo(AutoDelivery(print = true))
        env.updateSettings {
            it.copy(
                payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = false),
                receipt = it.receipt.copy(autoPrint = false),
            )
        }
        receipts.arm("s1")
        assertThat(await { receipts.automation(StoredTransaction.Sale("s1")) }).isEqualTo(AutoDelivery())

        // Without a printer nothing is printed automatically.
        env.useSimulator { it.copy(receipt = it.receipt.copy(autoPrint = true), simulator = it.simulator.copy(hasPrinter = false)) }
        await { container.terminalStatus.state.first { !it.printerAvailable } }
        receipts.arm("r1")
        assertThat(await { receipts.automation(StoredTransaction.Refund("r1")) }).isEqualTo(AutoDelivery())
        receipts.arm("missing")
        assertThat(await { receipts.automation(StoredTransaction.Sale("missing")) }).isEqualTo(AutoDelivery())
    }

    @Test
    fun `screens are offered the receipt, printing and email as the settings and printer say`() {
        env.useSimulator { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.OFF)) }
        await { container.terminalStatus.state.first { it.printerAvailable } }
        val receipts = container.receipts
        assertThat(
            await { receipts.offer(StoredTransaction.Sale("s1"), fresh = false).first() },
        ).isEqualTo(ReceiptOffer(null, canPrint = true, canEmail = false, canShare = true))
        store()
        configureEmail()
        val later = await { receipts.offer(StoredTransaction.Sale("s1"), fresh = false).first { it.canEmail } }
        assertThat(later.receipt!!.qrCodes).hasSize(1)
        // Right after the payment email is offered only when checkout captures emails, except for a payment link, which
        // reaches the shopper by email.
        assertThat(await { receipts.offer(StoredTransaction.Sale("s1"), fresh = true).first() }.canEmail).isFalse()
        await {
            container.database.saleDao().update(
                container.sales
                    .get("s1")!!
                    .sale
                    .copy(paymentLink = true),
            )
        }
        assertThat(await { receipts.offer(StoredTransaction.Sale("s1"), fresh = true).first { it.canEmail } }.canEmail).isTrue()
        await {
            container.database.saleDao().update(
                container.sales
                    .get("s1")!!
                    .sale
                    .copy(paymentLink = false),
            )
        }
        env.updateSettings { it.copy(payment = it.payment.copy(emailCapture = EmailCapture.AFTER_PAYMENT)) }
        assertThat(await { receipts.offer(StoredTransaction.Sale("s1"), fresh = true).first { it.canEmail } }.canEmail).isTrue()
        env.useSimulator { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
        assertThat(
            await {
                receipts.offer(StoredTransaction.Refund("missing")).first { !it.canPrint }
            },
        ).isEqualTo(ReceiptOffer(null, canPrint = false, canEmail = true, canShare = true))

        val sample = container.sampleReceipt(AppSettings(receipt = ReceiptSettings(businessName = "Cafe")))
        val text = PlainTextReceiptRenderer(48).render(sample)
        assertThat(text).contains("Cafe")
        assertThat(text).contains("Flat white")
        assertThat(text).contains("MP-SAMPLE-0001")
        assertThat(RefundQrPayload.decode(sample.qrCodes.single().content)!!.amountMinor).isEqualTo(1_200)
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
        assertThat(await { container.receipts.email(StoredTransaction.Sale("s1"), "shopper@example.com") }).isEqualTo(ActionResult.Success)
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
        assertThat(
            await { container.receipts.email(StoredTransaction.Sale("missing"), "a@b.co") },
        ).isEqualTo(ActionResult.Missing)
        assertThat(
            await { container.receipts.email(StoredTransaction.Refund("missing"), "a@b.co") },
        ).isEqualTo(ActionResult.Missing)
    }

    @Test
    fun `a sale awaiting its payment link is emailed, printed and shared as an unpaid receipt with the link`() {
        configureEmail()
        env.useSimulator()
        val url = "https://test.adyen.link/PL1"
        val link =
            sale.copy(
                status = SaleStatus.AWAITING_PAYMENT,
                paymentLink = true,
                paymentLinkUrl = url,
                paymentLinkExpiresAt = 1_790_766_235_000,
                poiTransactionId = null,
                poiTimestamp = null,
                customerReceiptJson = null,
                cashierReceiptJson = null,
                shopperEmail = "sam@example.com",
            )
        await { container.sales.createPending(link, lines) }
        val receipts = container.receipts
        assertThat(await { receipts.email(StoredTransaction.Sale("s1"), "sam@example.com") }).isEqualTo(ActionResult.Success)
        val message = env.mail.sent.single()
        assertThat(message.subject).isEqualTo("Payment request from Corner Cafe")
        val text = message.allText()
        assertThat(text).contains("Pay securely online with the link or the QR code below.")
        assertThat(text).contains("<a href=\"$url\"")
        assertThat(text).contains("UNPAID")
        assertThat(text).contains("AMOUNT DUE")
        assertThat((message.content as MimeMultipart).getBodyPart(1).getHeader("Content-ID").single()).isEqualTo("<qr0>")

        // The slip to hand over has the link's QR code and address, and needs no merchant copy.
        val printed = await { receipts.print(StoredTransaction.Sale("s1")) }
        assertThat(printed).isEqualTo(ReceiptPrint(ActionResult.Success, merchantCopyDue = false))
        val jobs = container.virtualPrinter.jobs.value
        assertThat(jobs).contains(PrintJob.QrCode(url))
        assertThat(jobs.filterIsInstance<PrintJob.Text>().flatMap { it.lines }).contains(PrintLine.Text(url, PrintAlign.CENTER))

        val shared = await { receipts.offer(StoredTransaction.Sale("s1"), fresh = false).first() }.share!!
        assertThat(shared.paymentLink).isEqualTo(url)
        assertThat(shared.reference).isEqualTo("MP-1")
        assertThat(shared.amountMinor).isEqualTo(1_200)
        assertThat(
            shared.document.qrCodes
                .single()
                .content,
        ).isEqualTo(url)

        // Once paid it is a receipt like any other, with nothing left to pay.
        await { container.database.saleDao().update(link.copy(status = SaleStatus.APPROVED)) }
        val paid = await { receipts.offer(StoredTransaction.Sale("s1"), fresh = false).first { it.share?.paymentLink == null } }.share!!
        assertThat(paid.paymentLink).isNull()
        assertThat(paid.document.elements).contains(ReceiptElement.Text("Paid online", Align.CENTER))
        assertThat(paid.document.qrCodes).isEmpty()
        assertThat(await { receipts.email(StoredTransaction.Sale("s1"), "sam@example.com") }).isEqualTo(ActionResult.Success)
        assertThat(
            env.mail.sent
                .last()
                .subject,
        ).isEqualTo("Your receipt from Corner Cafe")
        assertThat(await { receipts.offer(StoredTransaction.Sale("missing"), fresh = false).first() }.share).isNull()
    }

    @Test
    fun `refund receipts are shared as they are emailed`() {
        val refund =
            RefundEntity(
                id = "r1",
                saleId = null,
                createdAt = 0,
                merchantReference = "R-1",
                originalTransactionId = "T.X",
                originalTimestamp = "t",
                originalReference = "MP-1",
                currency = "AUD",
                amountMinor = 500,
                full = false,
                status = RefundStatus.REQUESTED,
            )
        await { container.refundRecords.create(refund) }
        val shared = await { container.receipts.offer(StoredTransaction.Refund("r1")).first() }.share!!
        assertThat(shared.reference).isEqualTo("R-1")
        assertThat(shared.amountMinor).isEqualTo(500)
        assertThat(shared.paymentLink).isNull()
        assertThat(shared.document.elements).contains(ReceiptElement.Text("REFUND", Align.CENTER, TextStyle.BOLD))
        assertThat(await { container.receipts.offer(StoredTransaction.Refund("missing")).first() }.share).isNull()
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
        assertThat(await { container.receipts.email(StoredTransaction.Sale("s1"), "a@b.co") }).isEqualTo(ActionResult.Success)
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
        assertThat(await { container.receipts.email(StoredTransaction.Refund("c1"), "a@b.co") }).isEqualTo(ActionResult.Success)
        assertThat(
            env.mail.sent
                .last()
                .allText(),
        ).contains("Cancellation of the held payment was requested")
    }

    @Test
    fun `tip receipts have lines to fill in until the tip is entered, and the shopper signs the merchant copy`() {
        env.useSimulator()
        val awaiting = sale.copy(tipOnReceipt = true, signatureRequired = false, pspReference = "PSP1")
        await { container.sales.createPending(awaiting, lines) }
        val factory = container.receiptFactory
        val write = "_".repeat(14)
        val blank = factory.sale(SaleWithLines(awaiting, lines), ReceiptSettings())
        assertThat(
            blank.elements,
        ).containsAtLeast(ReceiptElement.Row("AMOUNT", "A$12.00", TextStyle.BOLD), ReceiptElement.Row("TIP", write))
        // The shopper signs for the tip, so the merchant copy is due even though the terminal asked for no signature.
        assertThat(await { container.receipts.print(StoredTransaction.Sale("s1")) }.merchantCopyDue).isTrue()
        val merchant = factory.sale(SaleWithLines(awaiting, lines), ReceiptSettings(), ReceiptCopy.MERCHANT)
        assertThat(merchant.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).contains("SIGNATURE")

        val tipped = awaiting.copy(tipMinor = 300, capturedMinor = 1_500, captureStatus = CaptureStatus.REQUESTED)
        await { container.database.saleDao().update(tipped) }
        val entered = factory.sale(SaleWithLines(tipped, lines), ReceiptSettings())
        assertThat(entered.elements)
            .containsAtLeast(ReceiptElement.Row("TIP", "A$3.00"), ReceiptElement.Row("TOTAL", "A$15.00", TextStyle.BOLD))
            .inOrder()
        assertThat(RefundQrPayload.decode(entered.qrCodes.single().content)!!.amountMinor).isEqualTo(1_500)
        assertThat(await { container.receipts.print(StoredTransaction.Sale("s1")) }.merchantCopyDue).isFalse()
        // Nor does an emailed receipt.
        assertThat(
            factory
                .sale(
                    SaleWithLines(awaiting, lines),
                    ReceiptSettings(),
                    paper = false,
                ).elements
                .filterIsInstance<ReceiptElement.Row>()
                .map {
                    it.left
                },
        ).doesNotContain("TIP")
        // A declined sale gets no lines to fill in.
        val declined = factory.sale(SaleWithLines(awaiting.copy(status = SaleStatus.DECLINED), lines), ReceiptSettings())
        assertThat(declined.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("TIP")
    }

    @Test
    fun `an adjusted and captured pre-authorisation shows what it holds and what was captured`() {
        val preAuth =
            sale.copy(
                kind = SaleKind.PRE_AUTHORISATION,
                authorisedMinor = 1_500,
                capturedMinor = 1_450,
                captureStatus = CaptureStatus.REQUESTED,
            )
        val rows =
            container.receiptFactory
                .sale(
                    SaleWithLines(preAuth, lines),
                    ReceiptSettings(),
                ).elements
                .filterIsInstance<ReceiptElement.Row>()
        assertThat(rows.map { it.left }).containsAtLeast("AMOUNT HELD", "HELD NOW", "CAPTURED").inOrder()
        val held =
            container.receiptFactory.sale(
                SaleWithLines(preAuth.copy(capturedMinor = null, captureStatus = null), lines),
                ReceiptSettings(),
            )
        assertThat(held.elements.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("CAPTURED")
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
        assertThat(
            await { container.receipts.sendTestEmail("a@b.co") },
        ).isEqualTo(ActionResult.Failed(Failure.Email(EmailFault.NOT_CONFIGURED)))
        configureEmail()
        assertThat(
            await { container.receipts.sendTestEmail("nope") },
        ).isEqualTo(ActionResult.Failed(Failure.Email(EmailFault.INVALID_ADDRESS)))
        assertThat(await { container.receipts.sendTestEmail("a@b.co") }).isEqualTo(ActionResult.Success)
        env.mail.failure = MessagingException("Connection refused")
        assertThat(
            await { container.receipts.email(StoredTransaction.Sale("s1"), "a@b.co") },
        ).isEqualTo(ActionResult.Failed(Failure.Email(EmailFault.UNREACHABLE)))
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

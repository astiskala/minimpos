package app.minimpos.app.history

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.repo.GiftCardActivity
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.receipt.ActionResult
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalTotalsOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Stored-value activity in totals, reconciliation, issues and the daily report: never a sale. */
@RunWith(RobolectricTestRunner::class)
class GiftCardAccountingTest {
    @get:Rule val env = TestEnvironment()

    private val at = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true)

    private fun stage(
        id: String,
        stage: GiftCardStage,
        amountMinor: Long,
        status: GiftCardStatus = GiftCardStatus.SUCCEEDED,
        group: String = "group",
    ) = GiftCardOperationEntity(
        id = id,
        groupId = group,
        stage = stage,
        category = stage.category,
        createdAt = at,
        processedAt = at.takeIf { status == GiftCardStatus.SUCCEEDED },
        status = status,
        provider = "SVS",
        currency = "AUD",
        amountMinor = amountMinor,
        approvedMinor = amountMinor.takeIf { status == GiftCardStatus.SUCCEEDED },
        merchantReference = "G-$id",
        poiId = "S1F2-1",
        poiReconciliationId = "7",
        context = context,
        funds = GiftCardStage.LOAD.takeIf { stage == GiftCardStage.FUNDING_CARD },
    )

    private val loaded =
        GiftCardActivity(listOf(stage("fund", GiftCardStage.FUNDING_CARD, 2000), stage("load", GiftCardStage.LOAD, 2000)))
    private val cashOut =
        GiftCardActivity(
            listOf(
                stage("out", GiftCardStage.CASH_OUT, 500, group = "cash"),
                stage("hand", GiftCardStage.CASH_HANDOVER, 500, GiftCardStatus.AWAITING, group = "cash"),
            ),
        )

    @Test
    fun `stored value is totalled apart from sales and refunds, with unfinished handovers as issues`() {
        val items = listOf(HistoryItem.GiftCard(loaded), HistoryItem.GiftCard(cashOut))
        val totals = HistoryAccounting.totals(HistoryAccounting.activities(items)).getValue("AUD")
        assertThat(totals.salesMinor).isEqualTo(0)
        assertThat(totals.refundsMinor).isEqualTo(0)
        assertThat(totals.giftCards.cardFundingMinor).isEqualTo(2000)
        assertThat(totals.giftCards.valueAddedMinor).isEqualTo(2000)
        assertThat(totals.giftCards.valueRemovedMinor).isEqualTo(500)
        assertThat(totals.giftCards.cashHandedOverMinor).isEqualTo(0)
        assertThat(totals.unresolvedCount).isEqualTo(1)
        assertThat(HistoryAccounting.needsAttention(HistoryItem.GiftCard(cashOut))).isTrue()
        assertThat(HistoryAccounting.needsAttention(HistoryItem.GiftCard(loaded))).isFalse()
        assertThat(HistoryItem.GiftCard(loaded).id).isEqualTo("group")
        val days = HistoryAccounting.days(items, ZoneOffset.UTC)
        assertThat(days.single().items.map { it.id }).containsExactly("group", "cash")
        assertThat(days.single().totals.saleCount).isEqualTo(0)
    }

    @Test
    fun `terminal comparison counts gift-card payments as debits and refunds as credits, never stored-value management`() {
        val refund =
            GiftCardActivity(
                listOf(
                    stage("refund", GiftCardStage.REFUND, 300, group = "refund"),
                    stage("lost", GiftCardStage.LOAD, 100, GiftCardStatus.UNKNOWN, group = "refund"),
                ),
            )
        val remote = TerminalTotalsOutcome.Answered("7", listOf(TerminalCurrencyTotals("AUD", 2500, 2, 300, 1)), false)
        val report =
            HistoryAccounting
                .ReconciliationAccumulator("S1F2-1", remote, LocalDate.parse("2026-10-06"), ZoneOffset.UTC)
                .apply { add(listOf(HistoryItem.GiftCard(loaded), HistoryItem.GiftCard(cashOut), HistoryItem.GiftCard(refund))) }
                .report()
        val aud = report.currencies.single()
        assertThat(aud.localSalesMinor).isEqualTo(2500)
        assertThat(aud.localRefundsMinor).isEqualTo(300)
        assertThat(aud.matches).isTrue()
        assertThat(report.unresolvedCount).isEqualTo(1)
        assertThat(HistoryAccounting.unresolvedTerminalItems(listOf(HistoryItem.GiftCard(refund)), "OTHER-1")).isEmpty()
    }

    @Test
    fun `the daily report prints stored-value rows separately`() =
        await {
            env.useSimulator()
            env.container.giftCardRecords.open(stage("fund", GiftCardStage.FUNDING_CARD, 2000))
            env.container.giftCardRecords.open(stage("load", GiftCardStage.LOAD, 2000)) { true }
            val date = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
            assertThat(env.container.receipts.printReport(date, ZoneOffset.UTC)).isEqualTo(ActionResult.Success)
            val text =
                env.container.virtualPrinter.jobs.value
                    .single()
                    .toString()
            assertThat(text).contains("Gift card value added")
            assertThat(text).contains("Gift card payments")
            assertThat(text).doesNotContain("Gift card cash out")
        }
}

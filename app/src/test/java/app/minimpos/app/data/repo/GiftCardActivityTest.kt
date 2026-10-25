package app.minimpos.app.data.repo

import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.giftcard.GiftCardProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one reading of stored-value stages: standing, offered follow-ups, undo eligibility and totals. */
class GiftCardActivityTest {
    private val day = GiftCardProvider.UNDO_WINDOW_MILLIS
    private var next = 0

    private fun stage(
        stage: GiftCardStage,
        status: GiftCardStatus = GiftCardStatus.SUCCEEDED,
        amountMinor: Long = 1000,
        group: String = "g",
        createdAt: Long = 100L + next,
        provider: String? = "SVS",
        lastFour: String? = "0042",
        referenced: Boolean = true,
    ) = GiftCardOperationEntity(
        id = "s${next++}",
        groupId = group,
        stage = stage,
        category = stage.category,
        createdAt = createdAt,
        processedAt = createdAt.takeIf { status == GiftCardStatus.SUCCEEDED },
        status = status,
        provider = provider,
        cardLastFour = lastFour,
        currency = "AUD",
        amountMinor = amountMinor,
        approvedMinor = amountMinor.takeIf { status == GiftCardStatus.SUCCEEDED },
        balanceMinor = amountMinor.takeIf { status == GiftCardStatus.SUCCEEDED && stage.changesValue },
        merchantReference = "G-$next",
        poiTransactionId = "T.P$next".takeIf { referenced },
        poiTimestamp = "2026-10-25T10:00:00Z".takeIf { referenced },
        pspReference = "P$next".takeIf { referenced },
        funds = GiftCardStage.LOAD.takeIf { stage == GiftCardStage.FUNDING_CARD || stage == GiftCardStage.FUNDING_EXTERNAL },
    )

    private fun activity(vararg stages: GiftCardOperationEntity) = GiftCardActivity(stages.toList())

    @Test
    fun `an activity needs stages of one group`() {
        assertThrows(IllegalArgumentException::class.java) { GiftCardActivity(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            activity(stage(GiftCardStage.LOAD), stage(GiftCardStage.LOAD, group = "other"))
        }
    }

    @Test
    fun `its kind, card and balance come from the stages`() {
        val load = activity(stage(GiftCardStage.FUNDING_CARD), stage(GiftCardStage.LOAD, lastFour = null))
        assertThat(load.kind).isEqualTo(GiftCardStage.LOAD)
        assertThat(load.cardLastFour).isEqualTo("0042")
        assertThat(load.balanceMinor).isEqualTo(1000)
        assertThat(load.currency).isEqualTo("AUD")
        val unknownProvider = activity(stage(GiftCardStage.DEACTIVATE, provider = "UNKNOWN", lastFour = null))
        assertThat(unknownProvider.provider).isNull()
        assertThat(unknownProvider.cardLastFour).isNull()
        assertThat(unknownProvider.kind).isEqualTo(GiftCardStage.DEACTIVATE)
        assertThat(unknownProvider.change?.stage).isEqualTo(GiftCardStage.DEACTIVATE)
        val refused = activity(stage(GiftCardStage.CASH_OUT, GiftCardStatus.DECLINED))
        assertThat(refused.balanceMinor).isNull()
        assertThat(refused.value).isNull()
    }

    @Test
    fun `unknown sent stages are checked again while local awaiting stages are confirmed`() {
        val unknown = activity(stage(GiftCardStage.ACTIVATE, GiftCardStatus.UNKNOWN))
        assertThat(unknown.attention).containsExactly(GiftCardAction.CHECK_AGAIN)
        assertThat(unknown.unresolved).isTrue()
        val handover =
            activity(stage(GiftCardStage.CASH_OUT), stage(GiftCardStage.CASH_HANDOVER, GiftCardStatus.AWAITING))
        assertThat(handover.attention).containsExactly(GiftCardAction.CONFIRM_HANDOVER)
        assertThat(handover.needsAttention).isTrue()
        val done = activity(stage(GiftCardStage.CASH_OUT), stage(GiftCardStage.CASH_HANDOVER))
        assertThat(done.needsAttention).isFalse()
    }

    @Test
    fun `confirmed funding without delivered value is repaired by its own means`() {
        val pendingValue = activity(stage(GiftCardStage.FUNDING_CARD))
        assertThat(pendingValue.attention).containsExactly(GiftCardAction.CONTINUE, GiftCardAction.REFUND_FUNDING)
        val external = activity(stage(GiftCardStage.FUNDING_EXTERNAL), stage(GiftCardStage.LOAD, GiftCardStatus.FAILED))
        assertThat(external.attention).containsExactly(GiftCardAction.CONTINUE, GiftCardAction.RETURN_FUNDS)
        val refunding =
            activity(
                stage(GiftCardStage.FUNDING_CARD),
                stage(GiftCardStage.LOAD, GiftCardStatus.DECLINED),
                stage(GiftCardStage.FUNDING_REFUND),
            )
        assertThat(refunding.needsRepair).isFalse()
        val refundFailed =
            activity(
                stage(GiftCardStage.FUNDING_CARD),
                stage(GiftCardStage.LOAD, GiftCardStatus.DECLINED),
                stage(GiftCardStage.FUNDING_REFUND, GiftCardStatus.FAILED),
            )
        assertThat(refundFailed.attention).contains(GiftCardAction.REFUND_FUNDING)
        val unknownLoad = activity(stage(GiftCardStage.FUNDING_CARD), stage(GiftCardStage.LOAD, GiftCardStatus.UNKNOWN))
        assertThat(unknownLoad.attention).containsExactly(GiftCardAction.CHECK_AGAIN)
        val nothingFunded = activity(stage(GiftCardStage.FUNDING_EXTERNAL, amountMinor = 0))
        assertThat(nothingFunded.needsRepair).isFalse()
        val unpaid = activity(stage(GiftCardStage.FUNDING_CARD, GiftCardStatus.DECLINED))
        assertThat(unpaid.needsAttention).isFalse()
    }

    @Test
    fun `only a referenced, delivered and recent latest load of its card is undone`() {
        val load = activity(stage(GiftCardStage.LOAD))
        val now = checkNotNull(load.value?.processedAt) + 1
        assertThat(load.actions(now)).containsExactly(GiftCardAction.UNDO_LOAD)
        assertThat(load.actions(now + day)).isEmpty()
        assertThat(activity(stage(GiftCardStage.LOAD, referenced = false)).actions(now)).isEmpty()
        assertThat(activity(stage(GiftCardStage.LOAD, provider = GiftCardProvider.LOCAL.name)).actions(now)).isEmpty()
        assertThat(activity(stage(GiftCardStage.LOAD, provider = null)).actions(now)).isEmpty()
        assertThat(activity(stage(GiftCardStage.ACTIVATE)).actions(now)).isEmpty()
        assertThat(activity(stage(GiftCardStage.LOAD), stage(GiftCardStage.UNDO_LOAD, GiftCardStatus.UNKNOWN)).actions(now))
            .containsExactly(GiftCardAction.CHECK_AGAIN)
        val undone = activity(stage(GiftCardStage.FUNDING_CARD), stage(GiftCardStage.LOAD), stage(GiftCardStage.UNDO_LOAD))
        assertThat(undone.undone).isTrue()
        assertThat(undone.actions(now)).containsExactly(GiftCardAction.REFUND_FUNDING)
        val refusedUndo = activity(stage(GiftCardStage.LOAD), stage(GiftCardStage.UNDO_LOAD, GiftCardStatus.DECLINED))
        assertThat(refusedUndo.actions(now)).containsExactly(GiftCardAction.UNDO_LOAD)

        val later = activity(stage(GiftCardStage.LOAD, group = "later", createdAt = 10_000))
        val laterRefused = activity(stage(GiftCardStage.LOAD, GiftCardStatus.DECLINED, group = "refused", createdAt = 10_000))
        val otherCard = activity(stage(GiftCardStage.LOAD, group = "other", createdAt = 10_000, lastFour = "9999"))
        val otherProvider = activity(stage(GiftCardStage.LOAD, group = "givex", createdAt = 10_000, provider = "GIVEX"))
        val earlier = activity(stage(GiftCardStage.LOAD, group = "earlier", createdAt = 0))
        assertThat(load.actions(now, listOf(load, laterRefused, otherCard, otherProvider, earlier))).contains(GiftCardAction.UNDO_LOAD)
        assertThat(load.actions(now, listOf(later))).doesNotContain(GiftCardAction.UNDO_LOAD)
    }

    @Test
    fun `totals keep value, funding and cash apart`() {
        val full =
            activity(
                stage(GiftCardStage.FUNDING_EXTERNAL, amountMinor = 500),
                stage(GiftCardStage.ACTIVATE, amountMinor = 500),
                stage(GiftCardStage.UNDO_LOAD, amountMinor = 200),
                stage(GiftCardStage.REFUND, amountMinor = 100),
                stage(GiftCardStage.CASH_OUT, amountMinor = 400),
                stage(GiftCardStage.CASH_HANDOVER, amountMinor = 400),
                stage(GiftCardStage.FUNDING_RETURNED, amountMinor = 50),
                stage(GiftCardStage.DEACTIVATE, amountMinor = 0),
                stage(GiftCardStage.LOAD, GiftCardStatus.UNKNOWN, amountMinor = 999),
            )
        assertThat(full.totals)
            .isEqualTo(
                GiftCardActivity.Totals(
                    valueAddedMinor = 400,
                    valueRemovedMinor = 400,
                    externalFundingMinor = 500,
                    fundingReturnedMinor = 50,
                    cashHandedOverMinor = 400,
                ),
            )
    }

    @Test
    fun `stages group into activities in stored order, newest activity first`() {
        val first = stage(GiftCardStage.FUNDING_CARD, group = "a", createdAt = 5)
        val second = stage(GiftCardStage.LOAD, group = "a", createdAt = 5)
        val other = stage(GiftCardStage.DEACTIVATE, group = "b", createdAt = 9)
        val activities = GiftCardActivity.of(listOf(first, other, second))
        assertThat(activities.map { it.groupId }).containsExactly("b", "a").inOrder()
        assertThat(activities.last().stages).containsExactly(first, second).inOrder()
        assertThat(activities.last().createdAt).isEqualTo(5)
    }

    @Test
    fun `stage and status vocabulary`() {
        assertThat(GiftCardStage.entries.filter { it.changesValue }.mapNotNull { it.operation })
            .containsExactly(
                GiftCardOperation.ACTIVATE,
                GiftCardOperation.LOAD,
                GiftCardOperation.UNDO_LOAD,
                GiftCardOperation.DEACTIVATE,
                GiftCardOperation.CASH_OUT,
                GiftCardOperation.REFUND,
            )
        assertThat(GiftCardStage.entries.filterNot { it.sent })
            .containsExactly(GiftCardStage.FUNDING_EXTERNAL, GiftCardStage.CASH_HANDOVER, GiftCardStage.FUNDING_RETURNED)
        assertThat(GiftCardStatus.entries.filter { it.unresolved })
            .containsExactly(GiftCardStatus.PENDING, GiftCardStatus.UNKNOWN, GiftCardStatus.AWAITING)
        assertThat(GiftCardStatus.entries.filter { it.notDone })
            .containsExactly(GiftCardStatus.DECLINED, GiftCardStatus.CANCELLED, GiftCardStatus.FAILED)
        assertThat(stage(GiftCardStage.LOAD, GiftCardStatus.FAILED).movedMinor).isEqualTo(0)
    }

    @Test
    fun `configured providers are offered once complete, one each`() {
        val intersolve = GiftCardSetting(GiftCardProvider.INTERSOLVE, code = " fashioncheque ")
        assertThat(intersolve.wireCode).isEqualTo("fashioncheque")
        assertThat(intersolve.complete).isTrue()
        assertThat(GiftCardSetting(GiftCardProvider.INTERSOLVE).complete).isFalse()
        assertThat(GiftCardSetting(GiftCardProvider.SVS, entries = emptySet()).complete).isFalse()
        assertThat(GiftCardSetting(GiftCardProvider.GIVEX, code = "ignored").wireCode).isEqualTo("givex")
        val settings =
            PaymentSettings(
                giftCards =
                    listOf(
                        GiftCardSetting(GiftCardProvider.SVS),
                        GiftCardSetting(GiftCardProvider.SVS, entries = setOf(GiftCardEntry.KEYED)),
                        GiftCardSetting(GiftCardProvider.LOCAL),
                    ),
            ).normalized()
        assertThat(settings.giftCards.map { it.provider }).containsExactly(GiftCardProvider.SVS, GiftCardProvider.LOCAL)
        assertThat(settings.offeredGiftCards.map { it.provider }).containsExactly(GiftCardProvider.SVS)
    }
}

package app.minimpos.app.terminal

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.refund.HistoryAccounting
import app.minimpos.app.refund.HistoryScope
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class HistorySwitchesTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container
    private val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val sale =
        SaleEntity(
            id = "sale",
            createdAt = now,
            processedAt = now,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1000,
            taxMinor = 0,
            totalMinor = 1000,
            status = SaleStatus.APPROVED,
            merchantReference = "SALE",
            context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
        )

    @Test
    fun `preview changes nothing and confirmed switch purges unfinished records but keeps catalog`() =
        await {
            env.useSimulator()
            container.sampleData.populate(container.settings.current())
            container.sales.createPending(sale.copy(status = SaleStatus.UNKNOWN), emptyList())
            val settings = container.settings.current()
            val products = container.catalog.products.first()
            val target = settings.terminal.withConnection(mode = TerminalMode.CLOUD, environment = TerminalEnvironment.TEST)
            val plan = checkNotNull(container.historySwitches.preview(target))
            assertThat(plan.unfinished).isTrue()
            assertThat(container.settings.current()).isEqualTo(settings)
            assertThat(container.history.items().first()).isNotEmpty()
            assertThat(container.historySwitches.confirm(plan)).isTrue()
            assertThat(container.history.items().first()).isEmpty()
            assertThat(container.settings.current().terminal).isEqualTo(target)
            assertThat(container.settings.current().payment).isEqualTo(settings.payment)
            assertThat(container.catalog.products.first()).isEqualTo(products)
            assertThat(container.history.pendingSwitch.first()).isNull()
        }

    @Test
    fun `same environment transport changes need no purge while LIVE to TEST does`() =
        await {
            val live =
                container.settings.current().terminal.withConnection(
                    mode = TerminalMode.TERMINAL,
                    environment = TerminalEnvironment.LIVE,
                )
            container.settings.update { it.copy(terminal = live) }
            container.sales.createPending(sale.copy(context = sale.context!!.copy(simulated = false, environment = "LIVE")), emptyList())
            val cloud = live.withConnection(mode = TerminalMode.CLOUD, environment = TerminalEnvironment.LIVE)
            assertThat(container.historySwitches.preview(cloud)).isNull()
            val test = live.selectEnvironment(TerminalEnvironment.TEST)
            assertThat(container.historySwitches.preview(test)).isNotNull()
            assertThat(container.history.items().first()).hasSize(1)
            val awaitingEnvironment = live.withConnection(mode = TerminalMode.CLOUD)
            container.settings.update { it.copy(terminal = awaitingEnvironment) }
            assertThat(container.historySwitches.preview(awaitingEnvironment.selectEnvironment(TerminalEnvironment.LIVE))).isNull()
            assertThat(container.historySwitches.preview(awaitingEnvironment.selectEnvironment(TerminalEnvironment.TEST))).isNotNull()
        }

    @Test
    fun `stale destination confirmation never deletes history`() =
        await {
            env.useSimulator()
            container.sales.createPending(sale, emptyList())
            val original = container.settings.current().terminal
            val target = original.withConnection(mode = TerminalMode.CLOUD, environment = TerminalEnvironment.TEST)
            val plan = checkNotNull(container.historySwitches.preview(target))
            container.settings.update { it.copy(terminal = it.terminal.copy(host = "changed")) }
            assertThat(container.historySwitches.confirm(plan)).isFalse()
            assertThat(container.history.items().first()).hasSize(1)
            assertThat(container.history.pendingSwitch.first()).isNull()
        }

    @Test
    fun `new unfinished activity requires refreshed extra warning before deletion`() =
        await {
            env.useSimulator()
            container.sales.createPending(sale, emptyList())
            val target =
                container.settings
                    .current()
                    .terminal
                    .withConnection(mode = TerminalMode.CLOUD, environment = TerminalEnvironment.TEST)
            val plan = checkNotNull(container.historySwitches.preview(target))
            assertThat(plan.unfinished).isFalse()
            container.sales.createPending(sale.copy(id = "pending", status = SaleStatus.PENDING), emptyList())
            assertThat(container.historySwitches.confirm(plan)).isFalse()
            assertThat(container.history.items().first()).hasSize(2)
            val refreshed = checkNotNull(container.historySwitches.preview(target))
            assertThat(refreshed.unfinished).isTrue()
            assertThat(container.historySwitches.confirm(refreshed)).isTrue()
            assertThat(container.history.items().first()).isEmpty()
        }

    @Test
    fun `confirmed journal recovers destination once without another deletion`() =
        await {
            env.useSimulator()
            container.sales.createPending(sale, emptyList())
            val target =
                container.settings
                    .current()
                    .terminal
                    .withConnection(mode = TerminalMode.CLOUD, environment = TerminalEnvironment.TEST)
            container.history.purgeForEnvironmentSwitch(target)
            assertThat(container.history.switchTarget()).isEqualTo(target)
            assertThat(container.settings.current().terminal).isNotEqualTo(target)
            container.historySwitches.recover()
            assertThat(container.settings.current().terminal).isEqualTo(target)
            assertThat(container.history.pendingSwitch.first()).isNull()
            container.sales.createPending(sale.copy(id = "new"), emptyList())
            container.historySwitches.recover()
            assertThat(container.history.items().first()).hasSize(1)
        }

    @Test
    fun `sample purge removes demo report activity but preserves merchant sales`() =
        await {
            env.useSimulator()
            container.sampleData.populate(container.settings.current())
            val sample =
                container.history
                    .items()
                    .first()
                    .filterIsInstance<HistoryItem.Sale>()
                    .first { it.sale.status == SaleStatus.APPROVED }
            val date = Instant.ofEpochMilli(checkNotNull(sample.sale.processedAt)).atZone(ZoneOffset.UTC).toLocalDate()
            val before = HistoryAccounting.report(container.history.items().first(), date, ZoneOffset.UTC, now)
            assertThat(
                before.sections
                    .first { it.scope == HistoryScope.DEMO }
                    .totals.values
                    .sumOf { it.saleCount },
            ).isEqualTo(1)
            val own = sale.copy(context = checkNotNull(sale.context).copy(simulated = false, environment = "LIVE"))
            container.sales.createPending(own, emptyList())
            container.sampleData.purge()
            assertThat(container.history.items().first()).containsExactly(HistoryItem.Sale(own))
            val after = HistoryAccounting.report(container.history.items().first(), LocalDate.parse("2026-10-06"), ZoneOffset.UTC, now)
            assertThat(after.sections.map { it.scope }).doesNotContain(HistoryScope.DEMO)
        }
}

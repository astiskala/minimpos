package app.minimpos.terminal.simulator

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SimulatedGiftCardLoadsTest {
    private class MovingClock(
        var now: Instant = Instant.parse("2026-10-25T10:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovingClock()
    private val cards = SimulatedGiftCards(clock)

    @Test
    fun `many loads retain only the latest undo opportunity per card`() {
        cards.activate("first", BigDecimal.TEN, "EUR")
        cards.activate("second", BigDecimal.ZERO, "EUR")
        repeat(1_000) { index ->
            assertThat(cards.load("first", BigDecimal.ONE, "EUR", "LOAD$index"))
                .isInstanceOf(SimulatedGiftCards.Answer.Approved::class.java)
        }
        cards.load("second", BigDecimal.TEN, "EUR", "SECOND")
        assertRefused("LOAD0")
        assertRefused("LOAD998")
        val undone = cards.undo("terminal.LOAD999") as SimulatedGiftCards.Answer.Approved
        assertThat(undone.approved).isEqualTo(BigDecimal.ONE)
        assertThat(undone.balance).isEqualTo(BigDecimal("1009"))
        assertRefused("LOAD999")
        assertRefused("LOAD998")
        val second = cards.undo("terminal.SECOND") as SimulatedGiftCards.Answer.Approved
        assertThat(second.balance).isEqualTo(BigDecimal.ZERO)
        assertThat((cards.balance("first") as SimulatedGiftCards.Answer.Approved).balance).isEqualTo(BigDecimal("1009"))
    }

    @Test
    fun `expired latest load never exposes an earlier one and a new load can be undone`() {
        cards.activate("card", BigDecimal.ZERO, "EUR")
        cards.load("card", BigDecimal.ONE, "EUR", "EARLY")
        cards.load("card", BigDecimal.TEN, "EUR", "LATEST")
        clock.now += Duration.ofHours(24).plusSeconds(1)
        assertRefused("LATEST")
        assertRefused("EARLY")
        cards.load("card", BigDecimal.ONE, "EUR", "NEW")
        clock.now += Duration.ofHours(24)
        assertThat(cards.undo("terminal.NEW")).isInstanceOf(SimulatedGiftCards.Answer.Approved::class.java)
        assertRefused("NEW")
        assertRefused("LATEST")
        assertThat((cards.balance("card") as SimulatedGiftCards.Answer.Approved).balance).isEqualTo(BigDecimal("11"))
    }

    @Test
    fun `insufficient balance and inactive card still refuse latest load undo`() {
        cards.activate("card", BigDecimal.ZERO, "EUR")
        cards.load("card", BigDecimal.TEN, "EUR", "LOAD")
        cards.redeem("card", BigDecimal.ONE, "EUR", partialAllowed = false, transactionId = "PAY")
        assertRefused("LOAD")
        cards.refund("card", BigDecimal.ONE, "EUR")
        cards.deactivate("card", "givex")
        assertRefused("LOAD")
    }

    private fun assertRefused(psp: String) {
        assertThat(cards.undo("terminal.$psp")).isInstanceOf(SimulatedGiftCards.Answer.Refused::class.java)
    }
}

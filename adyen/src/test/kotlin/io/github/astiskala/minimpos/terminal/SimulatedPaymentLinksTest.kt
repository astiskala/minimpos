package io.github.astiskala.minimpos.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.terminal.checkout.ModificationAmount
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkRequest
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkResult
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkStatus
import io.github.astiskala.minimpos.terminal.simulator.SimulatedPaymentLinks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SimulatedPaymentLinksTest {
    private val now = Instant.parse("2026-10-04T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val request = PaymentLinkRequest("MP-1", ModificationAmount("EUR", 1250), now.plusSeconds(60))

    @Test
    fun `creation is idempotent and a fresh adapter recovers the same non-payable link`() =
        runBlocking {
            val first = SimulatedPaymentLinks(clock).create(request, "link-sale-1") as PaymentLinkResult.Answered
            assertThat(first.link.status).isEqualTo(PaymentLinkStatus.ACTIVE)
            assertThat(URI(first.link.url).host).isEqualTo("example.invalid")
            val restarted = SimulatedPaymentLinks(clock)
            assertThat(restarted.create(request, "link-sale-1")).isEqualTo(first)
            assertThat(restarted.status(first.link.id)).isEqualTo(first)
            assertThat(restarted.create(request, "link-sale-2")).isNotEqualTo(first)
            val expired = restarted.expire(first.link.id) as PaymentLinkResult.Answered
            assertThat(expired.link.status).isEqualTo(PaymentLinkStatus.EXPIRED)
            assertThat(expired.link.id).isEqualTo(first.link.id)
        }

    @Test
    fun `expiry is checked at its boundary and invalid IDs never become links`() =
        runBlocking {
            val first = SimulatedPaymentLinks(clock).create(request, "link-sale") as PaymentLinkResult.Answered
            val expired = SimulatedPaymentLinks(Clock.fixed(request.expiresAt, ZoneOffset.UTC))
            assertThat((expired.status(first.link.id) as PaymentLinkResult.Answered).link.status).isEqualTo(PaymentLinkStatus.EXPIRED)
            val createdExpired = expired.create(request, "link-sale") as PaymentLinkResult.Answered
            assertThat(createdExpired.link.status).isEqualTo(PaymentLinkStatus.EXPIRED)
            listOf("PL-real", "simulated:bad:key", "simulated:").forEach {
                assertThat(expired.status(it)).isInstanceOf(PaymentLinkResult.NotProcessed::class.java)
            }
        }
}

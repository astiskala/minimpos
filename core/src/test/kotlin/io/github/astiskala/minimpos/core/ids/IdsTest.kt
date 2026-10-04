package io.github.astiskala.minimpos.core.ids

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class IdsTest {
    @Test
    fun `transaction references carry prefix, local time and a random suffix`() {
        val now = Instant.parse("2026-09-29T11:03:55Z")
        val zone = ZoneId.of("Australia/Sydney")
        assertThat(Ids.transactionReference("MP", now, zone, Random(1))).matches("MP-260929-210355-[0-9A-Z]{4}")
        assertThat(Ids.transactionReference(" ", now, zone, Random(1))).matches("260929-210355-[0-9A-Z]{4}")
        assertThat(Ids.transactionReference("a b/c", now, zone, Random(1))).startsWith("abc-")
    }

    @Test
    fun `random strings use the given alphabet`() {
        assertThat(Ids.randomString(8, Random(3), "ab")).matches("[ab]{8}")
    }
}

package app.minimpos.core

import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.codec.Base45
import app.minimpos.core.codec.QrChunkAssembler
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.core.codec.TransferFormatException
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.refund.RefundCalculator
import app.minimpos.core.refund.RefundableLine
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Properties that hold for every input, checked on many generated ones: money, tax, carts, refunds and the QR formats.
 * Each check uses its own fixed seed, so a failure names the seed and case and replays exactly.
 */
class InvariantsTest {
    /** Checks [property] for [cases] values made by [generate] from [seed]; a failure names the seed, case and value. */
    private fun <T : Any> forAll(
        seed: Long,
        cases: Int = 2_000,
        generate: Random.() -> T,
        property: (T) -> Unit,
    ) {
        val random = Random(seed)
        repeat(cases) { case ->
            val value = random.generate()
            runCatching { property(value) }.onFailure { throw AssertionError("Seed $seed, case $case: $value", it) }
        }
    }

    private fun Random.rate(): Int = if (nextInt(8) == 0) listOf(0, TaxRates.MAX).random(this) else nextInt(0, TaxRates.MAX + 1)

    /** Text of [min] to [max] characters from [chars], whole code points only (an emoji stays one character). */
    private fun Random.text(
        chars: String,
        min: Int,
        max: Int,
    ): String {
        val codePoints = chars.codePoints().toArray()
        return (1..nextInt(min, max + 1)).joinToString("") { Character.toString(codePoints.random(this)) }
    }

    private val alphanumeric = ('A'..'Z').joinToString("") + ('a'..'z').joinToString("") + ('0'..'9').joinToString("")

    @Test
    fun `tax splits every amount exactly into net and tax, never negative, never shrinking as the amount grows`() =
        forAll(
            seed = 1,
            generate = { Triple(nextLong(0, 100_000_000_000), rate(), TaxMode.entries.random(this)) },
        ) { (amount, rate, mode) ->
            val split = TaxRates.apply(amount, rate, mode)
            assertThat(split.net + split.tax).isEqualTo(split.gross)
            assertThat(split.tax).isAtLeast(0)
            assertThat(if (mode == TaxMode.INCLUSIVE) split.gross else split.net).isEqualTo(amount)
            assertThat(TaxRates.apply(amount + 1, rate, mode).tax).isAtLeast(split.tax)
            if (rate == 0) assertThat(split.tax).isEqualTo(0)
            // Tax included in a price is never more than the same rate added on top of it.
            assertThat(TaxRates.apply(amount, rate, TaxMode.INCLUSIVE).tax).isAtMost(TaxRates.apply(amount, rate, TaxMode.EXCLUSIVE).tax)
        }

    @Test
    fun `every valid tax rate reads back from its label, typed with a dot, a comma or a percent sign`() {
        (0..TaxRates.MAX).forEach { rate ->
            val label = TaxRates.format(rate)
            assertThat(TaxRates.parse(label)).isEqualTo(rate)
            assertThat(TaxRates.parse(" ${label.replace('.', ',')} %")).isEqualTo(rate)
        }
    }

    @Test
    fun `a cart's totals are the sum of its lines, which add up to what was rung up`() =
        forAll(
            seed = 2,
            cases = 1_000,
            generate = {
                val lines = (0..<nextInt(1, 20)).map { Triple(nextLong(1, 1_000_000), nextInt(1, Cart.MAX_QUANTITY + 1), rate()) }
                lines to TaxMode.entries.random(this)
            },
        ) { (lines, mode) ->
            val cart =
                lines.foldIndexed(Cart()) { index, cart, (price, quantity, rate) ->
                    cart.addCustom("Item $index", price, AppliedTax("Tax", rate), "k$index").setQuantity("k$index", quantity)
                }
            val rungUp = lines.sumOf { (price, quantity) -> price * quantity }
            val totals = cart.totals(mode)
            assertThat(totals.amounts).isEqualTo(totals.lines.fold(TaxAmounts.ZERO) { sum, line -> sum + line.amounts })
            assertThat(totals.amounts.net + totals.amounts.tax).isEqualTo(totals.amounts.gross)
            assertThat(if (mode == TaxMode.INCLUSIVE) totals.amounts.gross else totals.amounts.net).isEqualTo(rungUp)
            assertThat(totals.itemCount).isEqualTo(lines.sumOf { it.second })
            assertThat(cart.totals(mode, chargeTax = false).amounts).isEqualTo(TaxAmounts(rungUp, 0, rungUp))
        }

    @Test
    fun `refunding every unit of a line, in any number of refunds, returns exactly what the line cost`() =
        forAll(
            seed = 3,
            generate = {
                val quantity = nextInt(1, Cart.MAX_QUANTITY + 1)
                val steps = mutableListOf<Int>()
                var left = quantity
                while (left > 0) {
                    nextInt(1, left + 1).also {
                        steps += it
                        left -= it
                    }
                }
                Triple(nextLong(0, 100_000_000), quantity, steps)
            },
        ) { (gross, quantity, steps) ->
            var refunded = 0
            val amounts =
                steps.map { units ->
                    RefundCalculator.lineAmount(RefundableLine(1, quantity, refunded, gross), units).also { refunded += units }
                }
            assertThat(amounts.all { it >= 0 }).isTrue()
            assertThat(amounts.sum()).isEqualTo(gross)
            assertThat(RefundCalculator.lineAmount(RefundableLine(1, quantity, quantity, gross), 1)).isEqualTo(0)
        }

    @Test
    fun `a refund is never negative and never more than the sale has left, and refunding everything returns all of it`() =
        forAll(
            seed = 4,
            generate = {
                val lines =
                    (1L..nextLong(1, 8)).map { id ->
                        val quantity = nextInt(1, 20)
                        RefundableLine(id, quantity, nextInt(0, quantity + 1), nextLong(0, 1_000_000))
                    }
                val selection = lines.filter { nextBoolean() }.associate { it.lineId to nextInt(-2, it.quantity + 3) } + (99L to 5)
                Triple(lines, selection, nextLong(-1_000, lines.sumOf { it.gross } + 1_000))
            },
        ) { (lines, selection, remaining) ->
            val amount = RefundCalculator.amountFor(lines, selection, remaining)
            assertThat(amount).isIn(0..remaining.coerceAtLeast(0))
            val everything = lines.associate { it.lineId to it.remainingQuantity }
            if (lines.any { it.remainingQuantity > 0 }) {
                assertThat(RefundCalculator.amountFor(lines, everything, remaining)).isEqualTo(remaining.coerceAtLeast(0))
            }
        }

    @Test
    fun `an amount survives conversion and typing in every currency exponent`() =
        forAll(seed = 5, generate = { nextInt(0, 5) to nextLong(0, 1_000_000_000_000) }) { (digits, minor) ->
            val currency = CurrencySpec("XTS", digits)
            val major = currency.toMajor(minor)
            assertThat(currency.toMinor(major)).isEqualTo(minor)
            assertThat(currency.parseMinor(major.toPlainString())).isEqualTo(minor)
            assertThat(currency.parseMinor("$" + major.toPlainString().replace('.', ','))).isEqualTo(minor)
        }

    @Test
    fun `amount entry keeps within its digits, and a backspace undoes a digit`() =
        forAll(seed = 6, generate = { (0..<nextInt(1, 30)).map { nextInt(-2, 10) } }) { keys ->
            val entry =
                keys.fold(AmountEntry()) { entry, key ->
                    val next =
                        when (key) {
                            -2 -> entry.backspace()
                            -1 -> entry.appendDoubleZero()
                            else -> entry.append(key)
                        }
                    assertThat(next.minor).isAtLeast(0)
                    assertThat(next.minor.toString().length).isAtMost(next.maxDigits)
                    next
                }
            (1..9).forEach { digit ->
                if (entry.minor.toString().length < entry.maxDigits) assertThat(entry.append(digit).backspace()).isEqualTo(entry)
            }
        }

    @Test
    fun `Base45 round-trips any bytes using only QR alphanumeric characters`() =
        forAll(seed = 7, generate = { nextBytes(nextInt(0, 400)) }) { bytes ->
            val text = Base45.encode(bytes)
            assertThat(text.all { it in Base45.ALPHABET }).isTrue()
            assertThat(Base45.decode(text).toList()).isEqualTo(bytes.toList())
        }

    @Test
    fun `chunks scanned in any order, repeated or interrupted by another set, assemble the exact payload`() =
        forAll(
            seed = 8,
            cases = 1_000,
            generate = {
                val setId = text("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", QrChunks.SET_ID_LENGTH, QrChunks.SET_ID_LENGTH)
                Triple(Base45.encode(nextBytes(nextInt(0, 3_000))), setId, nextInt(1, 600))
            },
        ) { (data, setId, size) ->
            val chunks = QrChunks.split(data, setId, size)
            assertThat(chunks.size).isAtMost(999)
            chunks.forEach { chunk ->
                assertThat(chunk.encode().all { it in Base45.ALPHABET }).isTrue()
                assertThat(QrChunks.parse(chunk.encode())).isEqualTo(chunk)
            }
            val random = Random(data.length + size)
            val assembler = QrChunkAssembler()
            // A code of another export restarts the set.
            assembler.add(QrChunks.Chunk(if (setId == "ZZZZ") "YYYY" else "ZZZZ", 1, 2, "X"))
            (chunks + chunks.filter { random.nextBoolean() }).shuffled(random).forEach { assembler.add(it) }
            assertThat(assembler.isComplete).isTrue()
            assertThat(assembler.assemble()).isEqualTo(data)
        }

    @Test
    fun `a refund code round-trips any valid payment`() =
        forAll(
            seed = 9,
            generate = {
                RefundQrPayload(
                    transactionId = text("$alphanumeric.", 1, 64),
                    timestamp = Instant.ofEpochMilli(nextLong(0, 4_102_444_800_000)),
                    amountMinor = nextLong(0, 1_000_000_000_000),
                    currency = text(('A'..'Z').joinToString(""), 3, 3),
                    reference = if (nextBoolean()) null else text("$alphanumeric._-", 1, 40),
                )
            },
        ) { payload -> assertThat(RefundQrPayload.decode(payload.encode())).isEqualTo(payload) }

    @Test
    fun `a transfer round-trips any settings, connection and sealed secrets`() =
        forAll(
            seed = 10,
            cases = 500,
            generate = {
                val unicode = "aZ09 ,.:{}\"\\é漢字日本語😀\n\t"
                Transfer(
                    settings = text(unicode, 1, 2_000).takeIf { nextBoolean() },
                    sealedSecrets = SealedSecrets(nextBytes(nextInt(1, TransferCodec.MAX_SECRETS_BYTES))).takeIf { nextBoolean() },
                    connection = text(unicode, 1, 500),
                )
            },
        ) { transfer -> assertThat(TransferCodec.decode(TransferCodec.encode(transfer))).isEqualTo(transfer) }

    @Test
    fun `damaged or foreign transfer text is rejected as a transfer format error and nothing else`() =
        forAll(
            seed = 11,
            cases = 3_000,
            generate = {
                val valid = TransferCodec.encode(Transfer(settings = "{\"a\":1}", connection = "c"))
                when (nextInt(3)) {
                    0 -> valid.toCharArray().also { it[nextInt(it.size)] = Base45.ALPHABET.random(this) }.concatToString()
                    1 -> valid.take(nextInt(valid.length))
                    else -> text(Base45.ALPHABET, 0, 300)
                }
            },
        ) { text ->
            runCatching {
                TransferCodec.decode(
                    text,
                )
            }.exceptionOrNull()?.let { assertThat(it).isInstanceOf(TransferFormatException::class.java) }
        }
}

package io.minimpos.core.codec

import com.google.common.truth.Truth.assertThat
import io.minimpos.core.catalogue.Catalogue
import io.minimpos.core.catalogue.CatalogueCategory
import io.minimpos.core.catalogue.CatalogueProduct
import io.minimpos.core.catalogue.CatalogueTaxRate
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.random.Random

class CodecTest {
    @Test
    fun `base45 matches RFC 9285 examples`() {
        assertThat(Base45.encode("AB".toByteArray())).isEqualTo("BB8")
        assertThat(Base45.encode("Hello!!".toByteArray())).isEqualTo("%69 VD92EX0")
        assertThat(Base45.encode("base-45".toByteArray())).isEqualTo("UJCLQE7W581")
        assertThat(String(Base45.decode("QED8WEX0"))).isEqualTo("ietf!")
        assertThat(Base45.encode(ByteArray(0))).isEmpty()
    }

    @Test
    fun `base45 round trips arbitrary bytes and validates input`() {
        val bytes = Random(7).nextBytes(1001)
        assertThat(Base45.decode(Base45.encode(bytes))).isEqualTo(bytes)
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("GGW") } // > 0xFFFF
        assertThrows(IllegalArgumentException::class.java) { Base45.decode(":::") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("::") } // > 0xFF
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("A") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("a1") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("é1") }
    }

    private val catalogue =
        Catalogue(
            currencyCode = "AUD",
            taxRates = listOf(CatalogueTaxRate("GST", 10_000), CatalogueTaxRate("GST-free", 0)),
            categories = listOf(CatalogueCategory("Coffee"), CatalogueCategory("Food ☕")),
            products =
                listOf(
                    CatalogueProduct("Flat white", 450, 0, 0, null),
                    CatalogueProduct("Banana bread", 650, 1, 1, "9300000000001"),
                    CatalogueProduct("Gift card", 5_000_000_000, 1, null, "GC"),
                    CatalogueProduct("Stamp", 120, null, null, null),
                    CatalogueProduct("Catering deposit", 20_000, 0, null, null, preAuthorisation = true),
                ),
        )

    @Test
    fun `catalogue round trips through the compact codec`() {
        val encoded = encodeCatalogue(catalogue)
        assertThat(encoded.all { it in Base45.ALPHABET }).isTrue()
        assertThat(decodeCatalogue(encoded)).isEqualTo(catalogue)
    }

    @Test
    fun `catalogues from the previous format, where every product had a tax rate, still decode`() {
        // Encoded by the version 1 codec (before products could be untaxed).
        val v1 = "E90YPP3\$N*P10ECS-E2%1XISZW2DKBF7M-XI0OI-RO+HP0 JU1JQPJA+OASIXJA3MFCIJGJIDAP6W6O45: MRHH+DG00"
        assertThat(decodeCatalogue(v1))
            .isEqualTo(
                Catalogue(
                    "AUD",
                    listOf(CatalogueTaxRate("GST", 10_000), CatalogueTaxRate("Zero rated", 0)),
                    listOf(CatalogueCategory("Coffee")),
                    listOf(CatalogueProduct("Latte", 450, 0, 0, "L1"), CatalogueProduct("Water", 300, 1, null, null)),
                ),
            )
        assertThat(Base45.decode(encodeCatalogue(catalogue))[0].toInt()).isEqualTo(3)
    }

    @Test
    fun `catalogues from before pre-authorisation products decode as sale products`() {
        // Encoded by the version 2 codec, which had no product kind.
        val v2 = "SE0/ML-FF*P12ECS-E2%1XIS+HP0 JU1JQPJA+OASIXJA3MFSIJGJIDAPRZ6+P4S9NJMG+DG00"
        assertThat(decodeCatalogue(v2))
            .isEqualTo(
                Catalogue(
                    "AUD",
                    listOf(CatalogueTaxRate("GST", 10_000)),
                    listOf(CatalogueCategory("Coffee")),
                    listOf(CatalogueProduct("Latte", 450, 0, 0, "L1"), CatalogueProduct("Stamp", 120, null, null, null)),
                ),
            )
    }

    @Test
    fun `the product kind survives the transfer`() {
        val decoded = decodeCatalogue(encodeCatalogue(catalogue))
        assertThat(decoded.products.filter { it.preAuthorisation }.map { it.name }).containsExactly("Catering deposit")
    }

    @Test
    fun `catalogue encoding is compact`() {
        val big =
            catalogue.copy(
                products = List(150) { CatalogueProduct("Product number $it", 100L + it * 25, it % 2, it % 2, null) },
            )
        val encoded = encodeCatalogue(big)
        assertThat(encoded.length).isLessThan(2000)
        assertThat(decodeCatalogue(encoded)).isEqualTo(big)
    }

    @Test
    fun `catalogue decoding rejects corrupt data`() {
        val encoded = encodeCatalogue(catalogue)
        val packet = Base45.decode(encoded)

        fun reencode(block: (ByteArray) -> ByteArray) = Base45.encode(block(packet.copyOf()))

        assertFormatError("truncated") { decodeCatalogue(Base45.encode(byteArrayOf(1, 0))) }
        assertFormatError("version") { decodeCatalogue(reencode { it.also { b -> b[0] = 9 } }) }
        assertFormatError("checksum") {
            decodeCatalogue(
                reencode {
                    it.also { b ->
                        b[1] = (b[1] + 1).toByte()
                    }
                },
            )
        }
        assertFormatError("") { decodeCatalogue(reencode { it.copyOf(it.size - 4) }) }
        assertFormatError("Invalid transfer data") { decodeCatalogue("A") }
        assertFormatError("") { decodeCatalogue(reencode { it.also { b -> b[6] = 0x7F } }) }
    }

    @Test
    fun `catalogue decoding validates structure`() {
        assertFormatError("tax rate") { decodeCatalogue(packetFor(body(taxRate = 200_000))) }
        assertFormatError("missing tax rate") { decodeCatalogue(packetFor(body(taxIndex = 5))) }
        assertFormatError("trailing") { decodeCatalogue(packetFor(body() + byteArrayOf(0))) }
        assertFormatError("truncated") { decodeCatalogue(packetFor(body().copyOf(6))) }
        assertFormatError("too many") { decodeCatalogue(packetFor("AUD".toByteArray() + varint(200_000))) }
        assertFormatError("Invalid text") {
            decodeCatalogue(
                packetFor("AUD".toByteArray() + varint(1) + varint(5000)),
            )
        }
        assertFormatError("Invalid number") {
            decodeCatalogue(
                packetFor("AUD".toByteArray() + ByteArray(10) { 0xFF.toByte() }),
            )
        }
        assertFormatError("truncated") { decodeCatalogue(packetFor("AU".toByteArray())) }
    }

    @Test
    fun `catalogue validates references and encoder limits`() {
        assertThrows(IllegalArgumentException::class.java) { catalogue.copy(currencyCode = "AU") }
        assertThrows(IllegalArgumentException::class.java) {
            catalogue.copy(products = listOf(CatalogueProduct("x", 1, 0, 7, null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeCatalogue(
                catalogue.copy(products = listOf(CatalogueProduct("x".repeat(2000), 1, 0, null, null))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeCatalogue(catalogue.copy(products = listOf(CatalogueProduct("x", -1, 0, null, null))))
        }
    }

    @Test
    fun `transfers carry settings and sealed secrets in version 4, with or without a catalogue`() {
        val secrets = SealedSecrets(byteArrayOf(1, 2, 3, 0, -1))
        val full = Transfer(catalogue, """{"payment":{"currencyCode":"AUD"},"receipt":{"footer":"Ta ☕"}}""", secrets)
        val encoded = TransferCodec.encode(full)
        assertThat(Base45.decode(encoded)[0].toInt()).isEqualTo(4)
        assertThat(TransferCodec.decode(encoded)).isEqualTo(full)
        val settingsOnly = Transfer(settings = "{}")
        assertThat(TransferCodec.decode(TransferCodec.encode(settingsOnly))).isEqualTo(settingsOnly)
        val secretsOnly = Transfer(sealedSecrets = secrets)
        assertThat(TransferCodec.decode(TransferCodec.encode(secretsOnly))).isEqualTo(secretsOnly)
        // Settings longer than a catalogue text are fine, up to their own limit.
        val long = Transfer(settings = "x".repeat(10_000))
        assertThat(TransferCodec.decode(TransferCodec.encode(long))).isEqualTo(long)
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(Transfer(settings = "x".repeat(TransferCodec.MAX_SETTINGS_BYTES + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(Transfer(sealedSecrets = SealedSecrets(ByteArray(TransferCodec.MAX_SECRETS_BYTES + 1))))
        }
        assertThrows(IllegalArgumentException::class.java) { Transfer() }
    }

    @Test
    fun `sealed secrets compare by content and copy their bytes`() {
        val bytes = byteArrayOf(1, 2)
        val sealed = SealedSecrets(bytes)
        bytes[0] = 9
        assertThat(sealed.toByteArray()).isEqualTo(byteArrayOf(1, 2))
        sealed.toByteArray()[1] = 9
        assertThat(sealed).isEqualTo(SealedSecrets(byteArrayOf(1, 2)))
        assertThat(sealed.hashCode()).isEqualTo(SealedSecrets(byteArrayOf(1, 2)).hashCode())
        assertThat(sealed).isNotEqualTo(SealedSecrets(byteArrayOf(1)))
        assertThat(sealed.toString()).isEqualTo("SealedSecrets(2 bytes)")
    }

    @Test
    fun `version 4 decoding validates its sections`() {
        assertFormatError("sections") { TransferCodec.decode(packetFor(varint(0), version = 4)) }
        assertFormatError("sections") { TransferCodec.decode(packetFor(varint(8), version = 4)) }
        assertFormatError("Invalid data") { TransferCodec.decode(packetFor(varint(2) + varint(70_000), version = 4)) }
        assertFormatError("Invalid data") { TransferCodec.decode(packetFor(varint(4) + varint(5) + byteArrayOf(1), version = 4)) }
        assertFormatError(
            "trailing",
        ) { TransferCodec.decode(packetFor(varint(2) + varint(1) + "x".toByteArray() + byteArrayOf(0), version = 4)) }
        assertThat(
            TransferCodec
                .decode(packetFor(varint(1) + body() + varint(0), version = 4))
                .catalogue!!
                .products
                .single()
                .name,
        ).isEqualTo("P")
        assertFormatError("version 6") { TransferCodec.decode(packetFor(varint(1) + body(), version = 6)) }
    }

    @Test
    fun `a connection is carried in version 5, which version 4 may not claim`() {
        val secrets = SealedSecrets(byteArrayOf(7, 8, 9))
        val connection = """{"destination":"network","host":"192.168.1.20","poiId":"S1F2-000158213605014"}"""
        val setup = Transfer(sealedSecrets = secrets, connection = connection)
        val encoded = TransferCodec.encode(setup)
        assertThat(Base45.decode(encoded)[0].toInt()).isEqualTo(5)
        assertThat(TransferCodec.decode(encoded)).isEqualTo(setup)
        val everything = Transfer(catalogue, "{}", secrets, connection)
        assertThat(TransferCodec.decode(TransferCodec.encode(everything))).isEqualTo(everything)
        assertThat(TransferCodec.decode(TransferCodec.encode(Transfer(connection = "{}")))).isEqualTo(Transfer(connection = "{}"))
        // Without a connection, terminals still write version 4, which older builds read.
        assertThat(Base45.decode(TransferCodec.encode(Transfer(settings = "{}")))[0].toInt()).isEqualTo(4)

        assertFormatError("sections") { TransferCodec.decode(packetFor(varint(8) + varint(2) + "{}".toByteArray(), version = 4)) }
        assertFormatError("sections") { TransferCodec.decode(packetFor(varint(16), version = 5)) }
        assertFormatError("Invalid data") { TransferCodec.decode(packetFor(varint(8) + varint(5_000), version = 5)) }
        assertThat(TransferCodec.decode(packetFor(varint(8) + varint(2) + "{}".toByteArray(), version = 5)).connection).isEqualTo("{}")
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(Transfer(connection = "x".repeat(TransferCodec.MAX_CONNECTION_BYTES + 1)))
        }
    }

    @Test
    fun `chunks split, parse and reassemble in any order`() {
        val data = "A".repeat(1000) + "B".repeat(10)
        val chunks = QrChunks.split(data, "SET1", maxDataChars = 400)
        assertThat(chunks.map { it.index to it.total }).containsExactly(1 to 3, 2 to 3, 3 to 3).inOrder()
        val assembler = QrChunkAssembler()
        assertThat(assembler.add(QrChunks.parse(chunks[2].encode())!!)).isTrue()
        assertThat(assembler.add(QrChunks.parse(chunks[2].encode())!!)).isFalse()
        assertThat(assembler.missing).containsExactly(1, 2)
        assertThat(assembler.isComplete).isFalse()
        assertThrows(IllegalStateException::class.java) { assembler.assemble() }
        chunks.take(2).forEach { assembler.add(QrChunks.parse(it.encode())!!) }
        assertThat(assembler.isComplete).isTrue()
        assertThat(assembler.received to assembler.expected).isEqualTo(3 to 3)
        assertThat(assembler.assemble()).isEqualTo(data)
        assembler.reset()
        assertThat(assembler.expected).isEqualTo(0)
        assertThat(QrChunks.split("", "SET1").single().encode()).isEqualTo("MPC1:SET1:1/1:")
    }

    @Test
    fun `a chunk from another set restarts assembly`() {
        val assembler = QrChunkAssembler()
        assembler.add(QrChunks.Chunk("AAAA", 1, 2, "x"))
        assembler.add(QrChunks.Chunk("BBBB", 1, 1, "y"))
        assertThat(assembler.isComplete).isTrue()
        assertThat(assembler.assemble()).isEqualTo("y")
    }

    @Test
    fun `chunk parsing rejects foreign content`() {
        assertThat(
            QrChunks.parse("MPC1:SET1:2/3:DATA:WITH:COLONS"),
        ).isEqualTo(QrChunks.Chunk("SET1", 2, 3, "DATA:WITH:COLONS"))
        listOf(
            "hello",
            "MPC2:SET1:1/1:x",
            "MPC1:SET:1/1:x",
            "MPC1:SET1:1:x",
            "MPC1:SET1:a/1:x",
            "MPC1:SET1:1/b:x",
            "MPC1:SET1:4/3:x",
            "MPC1:SET1:0/3:x",
            "MPC1:SET1:1/1/1:x",
            "MPC1:SET1:1/1000:x",
        ).forEach { assertThat(QrChunks.parse(it)).isNull() }
        assertThrows(IllegalArgumentException::class.java) { QrChunks.split("x", "set1") }
        assertThrows(IllegalArgumentException::class.java) { QrChunks.split("x", "SET12") }
        assertThrows(IllegalArgumentException::class.java) { QrChunks.split("x", "SET1", 0) }
    }

    @Test
    fun `chunks keep Base45 spaces at the ends of their data, but not a scanner's line break`() {
        // Space is a Base45 character, so a chunk's data can start or end with one.
        val chunks = QrChunks.split(" AB C ", "SET1", maxDataChars = 3)
        assertThat(chunks.map { it.data }).containsExactly(" AB", " C ").inOrder()
        val assembler = QrChunkAssembler()
        chunks.forEach { assembler.add(QrChunks.parse(" " + it.encode() + "\r\n")!!) }
        assertThat(assembler.assemble()).isEqualTo(" AB C ")
    }

    @Test
    fun `end to end catalogue transfer over chunks`() {
        val chunks = QrChunks.split(encodeCatalogue(catalogue), "X1Y2", maxDataChars = 50)
        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it.encode().all { c -> c in Base45.ALPHABET }).isTrue() }
        val assembler = QrChunkAssembler()
        chunks.reversed().forEach { assembler.add(QrChunks.parse(it.encode())!!) }
        assertThat(decodeCatalogue(assembler.assemble())).isEqualTo(catalogue)
    }

    @Test
    fun `refund payload round trips and stays URL safe`() {
        val payload =
            RefundQrPayload(
                transactionId = "BV0q001643892070000.VK9DRSLLRCQ2WN82",
                timestamp = Instant.parse("2026-09-29T11:03:55.123Z"),
                amountMinor = 1200,
                currency = "AUD",
                reference = "MP-260929-210355-4F2A",
            )
        val text = payload.encode()
        assertThat(
            text,
        ).isEqualTo("MPR1*BV0q001643892070000.VK9DRSLLRCQ2WN82*1790679835123*1200*AUD*MP-260929-210355-4F2A")
        assertThat(URLEncoder.encode(text, "UTF-8")).isEqualTo(text)
        assertThat(RefundQrPayload.decode(text)).isEqualTo(payload)
        assertThat(
            RefundQrPayload.decode(payload.copy(reference = "has space").encode()),
        ).isEqualTo(payload.copy(reference = null))
    }

    @Test
    fun `refund payload rejects invalid input`() {
        listOf(
            "",
            "MPR1*a*1*2",
            "MPR2*a*1*2*AUD",
            "MPR1*a b*1*2*AUD",
            "MPR1*a*x*2*AUD",
            "MPR1*a*1*-2*AUD",
            "MPR1*a*1*2*aud",
        ).forEach { assertThat(RefundQrPayload.decode(it)).isNull() }
        assertThat(RefundQrPayload.decode("MPR1*a*1*2*AUD*bad ref")!!.reference).isNull()
        assertThrows(IllegalArgumentException::class.java) { RefundQrPayload("a", Instant.EPOCH, 1, "AU") }
    }

    private fun assertFormatError(
        messagePart: String,
        block: () -> Unit,
    ) {
        val error = assertThrows(TransferFormatException::class.java) { block() }
        assertThat(error.message).contains(messagePart)
    }

    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }

    private fun body(
        taxRate: Int = 10_000,
        taxIndex: Int = 0,
    ): ByteArray =
        "AUD".toByteArray() + varint(1) + varint(1) + "G".toByteArray() + varint(taxRate) + varint(0) +
            varint(1) + varint(1) + "P".toByteArray() + varint(100) + varint(taxIndex) + varint(0) + varint(0)

    private fun encodeCatalogue(catalogue: Catalogue) = TransferCodec.encode(Transfer(catalogue))

    private fun decodeCatalogue(text: String): Catalogue = TransferCodec.decode(text).catalogue!!

    private fun packetFor(
        body: ByteArray,
        version: Byte = 1,
    ): String {
        val crc =
            java.util.zip
                .CRC32()
                .apply { update(body) }
                .value
                .toInt()
        val deflater =
            java.util.zip.Deflater(9, true).apply {
                setInput(body)
                finish()
            }
        val buffer = ByteArray(4096)
        val size = deflater.deflate(buffer)
        deflater.end()
        val packet =
            ByteBuffer
                .allocate(5 + size)
                .put(version)
                .putInt(crc)
                .put(buffer, 0, size)
                .array()
        return Base45.encode(packet)
    }
}

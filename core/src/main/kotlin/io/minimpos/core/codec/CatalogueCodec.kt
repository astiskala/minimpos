package io.minimpos.core.codec

import io.minimpos.core.catalogue.Catalogue
import io.minimpos.core.catalogue.CatalogueCategory
import io.minimpos.core.catalogue.CatalogueProduct
import io.minimpos.core.catalogue.CatalogueTaxRate
import io.minimpos.core.tax.TaxRates
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Scanned catalogue data that cannot be imported: not Base45, truncated, corrupted (checksum mismatch), from an
 * unsupported format version, or with values out of range. The message is shown to the operator.
 */
class CatalogueFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Compact binary catalogue encoding for QR transfer:
 * `version(1) | crc32(4) | raw-deflate(body)`, where the body uses unsigned LEB128 varints and length-prefixed
 * UTF-8 strings. The result is Base45-encoded so it fits QR alphanumeric mode.
 *
 * Version 2 stores a product's tax rate as index + 1, with 0 meaning no tax (written only by builds that had untaxed
 * products); version 1 (index only) is still read, so catalogues from terminals running older builds can be imported.
 */
object CatalogueCodec {
    private const val VERSION: Int = 2
    private const val VERSION_TAX_REQUIRED: Int = 1
    private const val HEADER_SIZE = 5
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024
    private const val MAX_ITEMS = 100_000
    private const val BUFFER_BYTES = 4096

    /** How much of an over-long text an error message quotes. */
    private const val PREVIEW_CHARS = 20

    // LEB128: each byte carries 7 bits of the number, least significant first; the high bit says another byte follows.
    private const val VARINT_BITS = 7
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_MORE = 0x80

    /** The shift of the ninth byte; nine 7-bit groups cover every non-negative Long, so a tenth is invalid. */
    private const val VARINT_MAX_SHIFT = 56

    /**
     * Encodes [catalogue] in the current format version, ready to be split into QR codes with [QrChunks].
     *
     * @throws IllegalArgumentException if a name or SKU is longer than 1,024 bytes in UTF-8, or a price or rate is
     *   negative.
     */
    fun encode(catalogue: Catalogue): String {
        val body = BinaryWriter()
        body.writeAscii(catalogue.currencyCode)
        body.writeVarint(catalogue.taxRates.size.toLong())
        catalogue.taxRates.forEach {
            body.writeString(it.name)
            body.writeVarint(it.rateMilliPercent.toLong())
        }
        body.writeVarint(catalogue.categories.size.toLong())
        catalogue.categories.forEach { body.writeString(it.name) }
        body.writeVarint(catalogue.products.size.toLong())
        catalogue.products.forEach {
            body.writeString(it.name)
            body.writeVarint(it.priceMinor)
            body.writeVarint((it.taxRateIndex?.plus(1) ?: 0).toLong())
            body.writeVarint((it.categoryIndex?.plus(1) ?: 0).toLong())
            body.writeString(it.sku.orEmpty())
        }
        val raw = body.toByteArray()
        val crc = CRC32().apply { update(raw) }.value.toInt()
        val deflated = deflate(raw)
        val packet =
            ByteBuffer
                .allocate(
                    HEADER_SIZE + deflated.size,
                ).put(VERSION.toByte())
                .putInt(crc)
                .put(deflated)
                .array()
        return Base45.encode(packet)
    }

    /**
     * Decodes [text] produced by [encode] in format version 1 or 2. Limits on sizes and counts protect against
     * malicious codes: at most 2 MiB of catalogue data, 100,000 entries per list and 1,024 bytes per text.
     *
     * @throws CatalogueFormatException if [text] is not a valid catalogue.
     */
    fun decode(text: String): Catalogue {
        val failure =
            try {
                return unpack(Base45.decode(text))
            } catch (e: IllegalArgumentException) {
                CatalogueFormatException("Invalid catalogue data" + e.message?.let { ": $it" }.orEmpty(), e)
            } catch (e: DataFormatException) {
                CatalogueFormatException("Invalid catalogue data", e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: IndexOutOfBoundsException,
            ) {
                // Reading past the end of the data, wherever it happens, means the QR codes held a truncated catalogue.
                CatalogueFormatException("Catalogue data is truncated", e)
            }
        throw failure
    }

    private fun unpack(packet: ByteArray): Catalogue {
        if (packet.size < HEADER_SIZE) throw CatalogueFormatException("Catalogue data is truncated")
        val version = packet[0].toInt() and 0xFF
        if (version != VERSION && version != VERSION_TAX_REQUIRED) {
            throw CatalogueFormatException("Unsupported catalogue version $version")
        }
        val expectedCrc = ByteBuffer.wrap(packet, 1, 4).int
        val raw = inflate(packet.copyOfRange(HEADER_SIZE, packet.size))
        val crc = CRC32().apply { update(raw) }.value.toInt()
        if (crc != expectedCrc) throw CatalogueFormatException("Catalogue checksum mismatch")
        return read(BinaryReader(raw), taxOptional = version >= VERSION)
    }

    private fun read(
        reader: BinaryReader,
        taxOptional: Boolean,
    ): Catalogue {
        val currency = reader.readAscii(3)
        val taxRates =
            List(reader.readCount()) {
                val name = reader.readString()
                val rate = reader.readVarint().toInt()
                if (!TaxRates.isValid(rate)) throw CatalogueFormatException("Invalid tax rate for '$name'")
                CatalogueTaxRate(name, rate)
            }
        val categories = List(reader.readCount()) { CatalogueCategory(reader.readString()) }
        val products =
            List(reader.readCount()) {
                val name = reader.readString()
                val priceMinor = reader.readVarint()
                val tax = reader.readVarint().toInt()
                CatalogueProduct(
                    name = name,
                    priceMinor = priceMinor,
                    taxRateIndex = if (taxOptional) (tax - 1).takeIf { tax > 0 } else tax,
                    categoryIndex =
                        reader
                            .readVarint()
                            .toInt()
                            .takeIf { stored -> stored > 0 }
                            ?.minus(1),
                    sku = reader.readString().ifEmpty { null },
                )
            }
        if (!reader.isAtEnd) throw CatalogueFormatException("Unexpected trailing catalogue data")
        return Catalogue(currency, taxRates, categories, products)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            // "nowrap" inflaters need one extra dummy byte after the compressed data.
            inflater.setInput(bytes + 0)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw CatalogueFormatException("Catalogue data is truncated")
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw CatalogueFormatException("Catalogue is too large")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private class BinaryWriter {
        private val out = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            require(value >= 0) { "Negative values cannot be encoded" }
            var v = value
            while (v >= VARINT_MORE) {
                out.write(((v and VARINT_PAYLOAD.toLong()) or VARINT_MORE.toLong()).toInt())
                v = v ushr VARINT_BITS
            }
            out.write(v.toInt())
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES) { "Text is too long: ${value.take(PREVIEW_CHARS)}" }
            writeVarint(bytes.size.toLong())
            out.write(bytes)
        }

        fun writeAscii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class BinaryReader(
        private val bytes: ByteArray,
    ) {
        private var position = 0
        val isAtEnd: Boolean get() = position == bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (shift > VARINT_MAX_SHIFT) throw CatalogueFormatException("Invalid number in catalogue")
                val b = bytes[position++].toInt() and 0xFF
                result = result or ((b and VARINT_PAYLOAD).toLong() shl shift)
                if (b and VARINT_MORE == 0) return result
                shift += VARINT_BITS
            }
        }

        fun readCount(): Int {
            val count = readVarint()
            if (count > MAX_ITEMS) throw CatalogueFormatException("Catalogue has too many entries")
            return count.toInt()
        }

        fun readString(): String {
            val length = readVarint().toInt()
            if (length > MAX_STRING_BYTES || position + length > bytes.size) {
                throw CatalogueFormatException("Invalid text in catalogue")
            }
            return String(bytes, position, length, Charsets.UTF_8).also { position += length }
        }

        fun readAscii(length: Int): String {
            if (position + length > bytes.size) throw CatalogueFormatException("Catalogue data is truncated")
            return String(bytes, position, length, Charsets.US_ASCII).also { position += length }
        }
    }
}

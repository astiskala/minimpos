package app.minimpos.core.codec

import app.minimpos.core.catalogue.Catalogue
import app.minimpos.core.catalogue.CatalogueCategory
import app.minimpos.core.catalogue.CatalogueProduct
import app.minimpos.core.catalogue.CatalogueTaxRate
import app.minimpos.core.tax.TaxRates
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Scanned transfer data that cannot be imported: not Base45, truncated, corrupted (checksum mismatch), from an
 * unsupported format version, or with values out of range.
 */
class TransferFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Compact binary encoding of a [Transfer] for QR codes:
 * `version(1) | crc32(4) | raw-deflate(body)`, where the body uses unsigned LEB128 varints and length-prefixed
 * UTF-8 strings. The result is Base45-encoded so it fits QR alphanumeric mode.
 *
 * The current format is version 5. The body starts with a varint of sections present (bit 0: catalogue, bit 1:
 * settings, bit 2: sealed secrets, bit 3: connection), followed by those sections in order. Settings, secrets and
 * connection are length-prefixed bytes. Product records end with flags (bit 0: pre-authorisation).
 * The app and setup helper use this one format; any other version is rejected.
 */
object TransferCodec {
    private const val VERSION: Int = 5
    private const val FLAG_PRE_AUTHORISATION = 1L
    private const val SECTION_CATALOGUE = 1L
    private const val SECTION_SETTINGS = 2L
    private const val SECTION_SECRETS = 4L
    private const val SECTION_CONNECTION = 8L
    private const val ALL_SECTIONS = SECTION_CATALOGUE or SECTION_SETTINGS or SECTION_SECRETS or SECTION_CONNECTION
    private const val HEADER_SIZE = 5
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024

    /** The most settings text a transfer carries, in UTF-8 bytes. */
    const val MAX_SETTINGS_BYTES: Int = 64 * 1024

    /** The most sealed secrets a transfer carries, in bytes. */
    const val MAX_SECRETS_BYTES: Int = 4 * 1024

    /** The most connection text a transfer carries, in UTF-8 bytes. */
    const val MAX_CONNECTION_BYTES: Int = 4 * 1024
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
     * Encodes [transfer] in the current version, ready to be split into QR codes with [QrChunks].
     *
     * @throws IllegalArgumentException if a name or SKU is longer than 1,024 bytes in UTF-8, a price or rate is
     *   negative, or the settings, secrets or connection are longer than [MAX_SETTINGS_BYTES], [MAX_SECRETS_BYTES] or
     *   [MAX_CONNECTION_BYTES].
     */
    fun encode(transfer: Transfer): String = pack(VERSION, body(transfer, includeSecrets = true))

    /** Canonical uncompressed public sections, used as authenticated data when sealing a transfer.
     * Settings and connection retain their exact UTF-8 JSON text. A secrets-only transfer has empty public data.
     * The seal is omitted, so it cannot authenticate itself. Size and value limits match [encode].
     */
    fun authenticationData(transfer: Transfer): ByteArray =
        if (transfer.catalogue == null && transfer.settings == null && transfer.connection == null) {
            byteArrayOf()
        } else {
            body(transfer, includeSecrets = false)
        }

    private fun body(
        transfer: Transfer,
        includeSecrets: Boolean,
    ): ByteArray {
        val body = BinaryWriter()
        var sections = 0L
        if (transfer.catalogue != null) sections = sections or SECTION_CATALOGUE
        if (transfer.settings != null) sections = sections or SECTION_SETTINGS
        if (includeSecrets && transfer.sealedSecrets != null) sections = sections or SECTION_SECRETS
        if (transfer.connection != null) sections = sections or SECTION_CONNECTION
        body.writeVarint(sections)
        transfer.catalogue?.let { writeCatalogue(body, it) }
        transfer.settings?.let { body.writeBytes(it.toByteArray(Charsets.UTF_8), MAX_SETTINGS_BYTES) }
        if (includeSecrets) transfer.sealedSecrets?.let { body.writeBytes(it.toByteArray(), MAX_SECRETS_BYTES) }
        transfer.connection?.let { body.writeBytes(it.toByteArray(Charsets.UTF_8), MAX_CONNECTION_BYTES) }
        return body.toByteArray()
    }

    private fun writeCatalogue(
        body: BinaryWriter,
        catalogue: Catalogue,
    ) {
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
            body.writeVarint(if (it.preAuthorisation) FLAG_PRE_AUTHORISATION else 0)
        }
    }

    private fun pack(
        version: Int,
        raw: ByteArray,
    ): String {
        val crc = CRC32().apply { update(raw) }.value.toInt()
        val deflated = deflate(raw)
        val packet =
            ByteBuffer
                .allocate(
                    HEADER_SIZE + deflated.size,
                ).put(version.toByte())
                .putInt(crc)
                .put(deflated)
                .array()
        return Base45.encode(packet)
    }

    /**
     * Decodes [text] produced by [encode] in the current format. Limits on sizes and counts protect against
     * malicious codes: at most 2 MiB of data, 100,000 entries per list, 1,024 bytes per text, and the settings,
     * secrets and connection limits.
     *
     * @throws TransferFormatException if [text] is not a valid transfer.
     */
    fun decode(text: String): Transfer {
        val failure =
            try {
                return unpack(Base45.decode(text))
            } catch (e: IllegalArgumentException) {
                TransferFormatException("Invalid transfer data" + e.message?.let { ": $it" }.orEmpty(), e)
            } catch (e: DataFormatException) {
                TransferFormatException("Invalid transfer data", e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: IndexOutOfBoundsException,
            ) {
                // Reading past the end of the data, wherever it happens, means the QR codes held a truncated transfer.
                TransferFormatException("Transfer data is truncated", e)
            }
        throw failure
    }

    private fun unpack(packet: ByteArray): Transfer {
        if (packet.size < HEADER_SIZE) throw TransferFormatException("Transfer data is truncated")
        val version = packet[0].toInt() and 0xFF
        if (version != VERSION) {
            throw TransferFormatException("Unsupported transfer version $version")
        }
        val expectedCrc = ByteBuffer.wrap(packet, 1, 4).int
        val raw = inflate(packet.copyOfRange(HEADER_SIZE, packet.size))
        val crc = CRC32().apply { update(raw) }.value.toInt()
        if (crc != expectedCrc) throw TransferFormatException("Transfer checksum mismatch")
        val reader = BinaryReader(raw)
        val transfer = readSections(reader)
        if (!reader.isAtEnd) throw TransferFormatException("Unexpected trailing transfer data")
        return transfer
    }

    private fun readSections(reader: BinaryReader): Transfer {
        val sections = reader.readVarint()
        if (sections == 0L || sections and ALL_SECTIONS.inv() != 0L) throw TransferFormatException("Invalid transfer sections")
        val catalogue = if (sections and SECTION_CATALOGUE != 0L) readCatalogue(reader) else null
        val settings =
            if (sections and SECTION_SETTINGS != 0L) reader.readBytes(MAX_SETTINGS_BYTES).toString(Charsets.UTF_8) else null
        val secrets = if (sections and SECTION_SECRETS != 0L) SealedSecrets(reader.readBytes(MAX_SECRETS_BYTES)) else null
        val connection =
            if (sections and SECTION_CONNECTION != 0L) reader.readBytes(MAX_CONNECTION_BYTES).toString(Charsets.UTF_8) else null
        return Transfer(catalogue, settings, secrets, connection)
    }

    private fun readCatalogue(reader: BinaryReader): Catalogue {
        val currency = reader.readAscii(3)
        val taxRates =
            List(reader.readCount()) {
                val name = reader.readString()
                val rate = reader.readVarint().toInt()
                if (!TaxRates.isValid(rate)) throw TransferFormatException("Invalid tax rate for '$name'")
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
                    taxRateIndex = (tax - 1).takeIf { tax > 0 },
                    categoryIndex =
                        reader
                            .readVarint()
                            .toInt()
                            .takeIf { stored -> stored > 0 }
                            ?.minus(1),
                    sku = reader.readString().ifEmpty { null },
                    preAuthorisation = (reader.readVarint() and FLAG_PRE_AUTHORISATION) != 0L,
                )
            }
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
                    throw TransferFormatException("Transfer data is truncated")
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw TransferFormatException("Transfer is too large")
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

        fun writeBytes(
            value: ByteArray,
            max: Int,
        ) {
            require(value.size <= max) { "Data is too long: ${value.size} bytes" }
            writeVarint(value.size.toLong())
            out.write(value)
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
                if (shift > VARINT_MAX_SHIFT) throw TransferFormatException("Invalid number in transfer")
                val b = bytes[position++].toInt() and 0xFF
                result = result or ((b and VARINT_PAYLOAD).toLong() shl shift)
                if (b and VARINT_MORE == 0) return result
                shift += VARINT_BITS
            }
        }

        fun readCount(): Int {
            val count = readVarint()
            if (count > MAX_ITEMS) throw TransferFormatException("Transfer has too many entries")
            return count.toInt()
        }

        fun readString(): String {
            val length = readVarint().toInt()
            if (length > MAX_STRING_BYTES || position + length > bytes.size) {
                throw TransferFormatException("Invalid text in transfer")
            }
            return String(bytes, position, length, Charsets.UTF_8).also { position += length }
        }

        fun readBytes(max: Int): ByteArray {
            val length = readVarint()
            if (length > max || position + length > bytes.size) throw TransferFormatException("Invalid data in transfer")
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }

        fun readAscii(length: Int): String {
            if (position + length > bytes.size) throw TransferFormatException("Transfer data is truncated")
            return String(bytes, position, length, Charsets.US_ASCII).also { position += length }
        }
    }
}

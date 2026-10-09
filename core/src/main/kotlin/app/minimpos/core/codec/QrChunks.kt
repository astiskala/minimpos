package app.minimpos.core.codec

/**
 * Splits long payloads across several QR codes: `MPC1:<set>:<index>/<total>:<data>`. Every character stays within
 * the QR alphanumeric set when the data is Base45, so each code uses the densest text mode.
 */
object QrChunks {
    /** The first field of every chunk, which also versions the format. */
    const val PREFIX = "MPC1"

    /** Length of a set ID: upper-case letters and digits that tell one export's codes from another's. */
    const val SET_ID_LENGTH = 4

    /** Default number of data characters per code; the header adds at most 18 more. */
    const val DEFAULT_MAX_DATA_CHARS = 480
    private const val MAX_CHUNKS = 999

    /** Prefix, set ID, position and data. */
    private const val FIELDS = 4

    /**
     * One QR code's share of a payload.
     *
     * @property setId The export this chunk belongs to ([SET_ID_LENGTH] upper-case letters and digits).
     * @property index Position of this chunk, from 1 to [total].
     * @property total Number of chunks in the set, from 1 to 999.
     * @property data This chunk's part of the payload; may be empty only when the whole payload is.
     */
    data class Chunk(
        val setId: String,
        val index: Int,
        val total: Int,
        val data: String,
    ) {
        /** The text to put in the QR code. */
        fun encode(): String = "$PREFIX:$setId:$index/$total:$data"
    }

    /**
     * Splits [data] into chunks of at most [maxDataChars] characters, numbered from 1; empty [data] gives one empty
     * chunk so that an empty payload can still be transferred.
     *
     * @throws IllegalArgumentException if [setId] is not [SET_ID_LENGTH] upper-case letters or digits, or
     *   [maxDataChars] is not positive.
     */
    fun split(
        data: String,
        setId: String,
        maxDataChars: Int = DEFAULT_MAX_DATA_CHARS,
    ): List<Chunk> {
        require(setId.length == SET_ID_LENGTH && setId.all { it in '0'..'9' || it in 'A'..'Z' }) { "Invalid set id" }
        require(maxDataChars > 0) { "Chunk size must be positive" }
        val parts = if (data.isEmpty()) listOf("") else data.chunked(maxDataChars)
        return parts.mapIndexed { i, part -> Chunk(setId, i + 1, parts.size, part) }
    }

    /**
     * Parses scanned [text]; null when it is not a well-formed chunk. Whitespace before the prefix is ignored, and after
     * the data only line breaks and tabs: space is a Base45 character, so the data can end with one.
     */
    fun parse(text: String): Chunk? {
        val parts = text.trimStart().trimEnd { it.isWhitespace() && it != ' ' }.split(':', limit = FIELDS)
        if (parts.size != FIELDS || parts[0] != PREFIX || parts[1].length != SET_ID_LENGTH) return null
        val position = parts[2].split('/')
        val index = position.getOrNull(0)?.toIntOrNull() ?: return null
        val total = position.getOrNull(1)?.toIntOrNull() ?: return null
        if (position.size != 2 || total !in 1..MAX_CHUNKS || index !in 1..total) return null
        return Chunk(parts[1], index, total, parts[FIELDS - 1])
    }
}

/**
 * Collects chunks scanned in any order; switching to a chunk from a different set starts over. Not thread-safe: feed
 * it from one thread (the scanner's callback).
 */
class QrChunkAssembler {
    private var setId: String? = null
    private var total = 0
    private val parts = sortedMapOf<Int, String>()

    /** Number of distinct chunks of the current set scanned so far. */
    val received: Int get() = parts.size

    /** Number of chunks in the current set; 0 before the first chunk. */
    val expected: Int get() = total

    /** Whether every chunk of the current set has been scanned, so [assemble] can be called. */
    val isComplete: Boolean get() = total > 0 && parts.size == total

    /** Indexes (from 1) of the chunks of the current set still to be scanned, in order. */
    val missing: List<Int> get() = (1..total).filterNot { it in parts }

    /** Adds [chunk] and returns true when its index had not been scanned yet in the current set. */
    fun add(chunk: QrChunks.Chunk): Boolean {
        if (chunk.setId != setId || chunk.total != total) {
            setId = chunk.setId
            total = chunk.total
            parts.clear()
        }
        return parts.put(chunk.index, chunk.data) == null
    }

    /**
     * Joins the chunks' data in index order.
     *
     * @throws IllegalStateException if the set is not [isComplete].
     */
    fun assemble(): String {
        check(isComplete) { "Missing chunks: $missing" }
        return parts.values.joinToString(separator = "")
    }

    /** Forgets everything scanned, ready for a new set. */
    fun reset() {
        setId = null
        total = 0
        parts.clear()
    }
}

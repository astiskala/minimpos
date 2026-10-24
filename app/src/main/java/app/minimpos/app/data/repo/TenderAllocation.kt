package app.minimpos.app.data.repo

import app.minimpos.app.data.settings.StorageJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * How one tender's frozen purchase amount was chosen, stored as JSON in `sale_tenders.allocationJson`; an operator
 * amount or the whole balance has none. It is persisted with the tender before anything is sent, so restarts, refunds
 * and later tenders read the same allocation.
 */
@Serializable
sealed interface TenderAllocation {
    /**
     * Share [index] (zero-based) of [count] equal shares of [baseMinor], the balance due when the first share started.
     *
     * @property index Zero-based share position.
     * @property count Number of equal shares.
     * @property baseMinor Balance divided into shares, in minor units.
     */
    @Serializable
    @SerialName("share")
    data class Share(
        val index: Int,
        val count: Int,
        val baseMinor: Long,
    ) : TenderAllocation

    /**
     * Sold units this tender pays for, at their apportioned sold gross.
     *
     * @property lines Allocated units per sale line.
     */
    @Serializable
    @SerialName("items")
    data class Items(
        val lines: List<AllocatedLine>,
    ) : TenderAllocation
}

/**
 * Units of one sale line allocated to a tender.
 *
 * @property position The line's [app.minimpos.app.data.db.SaleLineEntity.position], known before a new sale is stored.
 * @property quantity Units allocated.
 * @property grossMinor Their apportioned share of the line's sold gross, in minor units.
 */
@Serializable
data class AllocatedLine(
    val position: Int,
    val quantity: Int,
    val grossMinor: Long,
)

/**
 * JSON codec for [TenderAllocation]. Decoding is lenient: an unreadable value decodes to null, which readers treat as an
 * amount allocation, so item modes stay closed rather than allowing units to be allocated twice.
 */
object TenderAllocationJson {
    private val serializer = serializer<TenderAllocation>()

    /** Encodes [allocation], or returns null for an amount allocation. */
    fun encode(allocation: TenderAllocation?): String? = allocation?.let { StorageJson.encodeToString(serializer, it) }

    /** Decodes what [encode] stored; null when [json] is null or unreadable. */
    fun decode(json: String?): TenderAllocation? = json?.let { runCatching { StorageJson.decodeFromString(serializer, it) }.getOrNull() }
}

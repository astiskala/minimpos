package app.minimpos.app.data.repo

import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.core.money.CurrencySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Localized fictional café shared by app samples and website screenshots. Prices use the fixture currency's minor units.
 * @param market Localized names, prices and receipt business fields.
 * @param categories English category keys and their ordered English product keys.
 */
class SampleDataFixture private constructor(
    private val market: JsonObject,
    private val categories: JsonObject,
) {
    private val names get() = market.getValue("names").jsonObject

    /**
     * Localized categories and products, priced in [currency] using the merchant's [taxRateId].
     * Currency and tax settings stay unchanged.
     */
    fun products(
        currency: CurrencySpec,
        taxRateId: Long,
    ): Map<String, List<ProductEntity>> {
        val source = CurrencySpec.of(market.getValue("currencyCode").jsonPrimitive.content)
        val prices = market.getValue("pricesMinor").jsonObject
        return categories
            .map { (category, items) ->
                names.getValue(category).jsonPrimitive.content to
                    items.jsonArray.map { item ->
                        val key = item.jsonPrimitive.content
                        ProductEntity(
                            name = names.getValue(key).jsonPrimitive.content,
                            taxRateId = taxRateId,
                            priceMinor = currency.toMinor(source.toMajor(prices.getValue(key).jsonPrimitive.long)),
                            kind = if (category == "Bookings") SaleKind.PRE_AUTHORISATION else SaleKind.SALE,
                            sample = true,
                        )
                    }
            }.toMap()
    }

    /** Replaces receipt business fields with fictional details; layout, title, footer and delivery settings stay intact. */
    fun receipt(existing: ReceiptSettings): ReceiptSettings {
        val receipt = market.getValue("receipt").jsonObject

        fun field(name: String) = receipt.getValue(name).jsonPrimitive.content
        return existing.copy(
            businessName = field("businessName"),
            addressLines = field("addressLines"),
            phone = field("phone"),
            taxId = field("taxId"),
        )
    }

    /** Reads the shared UTF-8 JSON asset without network access. */
    companion object {
        /** Selects [languageTag], including regional Chinese variants; unsupported languages use English. */
        fun read(
            json: String,
            languageTag: String,
        ): SampleDataFixture {
            val root = Json.parseToJsonElement(json).jsonObject
            val language =
                when {
                    languageTag.startsWith("ja") -> "ja"
                    languageTag.startsWith("zh") && listOf("Hant", "HK", "TW", "MO").any { languageTag.contains(it) } -> "zh-Hant"
                    languageTag.startsWith("zh") -> "zh-CN"
                    else -> "en"
                }
            return SampleDataFixture(
                root
                    .getValue("languages")
                    .jsonObject
                    .getValue(language)
                    .jsonObject,
                root.getValue("categories").jsonObject,
            )
        }
    }
}

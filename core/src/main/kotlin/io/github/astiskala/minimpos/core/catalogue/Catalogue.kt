package io.github.astiskala.minimpos.core.catalogue

import io.github.astiskala.minimpos.core.money.CurrencySpec

/**
 * Portable product catalogue, as transferred between terminals (see `TransferCodec`). Products reference tax
 * rates/categories by index into this catalogue's lists rather than by database id, so it does not depend on the
 * sending terminal's database.
 *
 * The constructor throws [IllegalArgumentException] when [currencyCode] is not three characters long or a product's
 * index points outside [taxRates] or [categories]. The code is not checked against Adyen's currency table.
 *
 * @property currencyCode the ISO 4217 code the product prices are in, e.g. "AUD".
 * @property taxRates the tax rates products refer to by [CatalogueProduct.taxRateIndex].
 * @property categories the categories products refer to by [CatalogueProduct.categoryIndex].
 * @property products the products, in the order they are listed.
 */
data class Catalogue(
    val currencyCode: String,
    val taxRates: List<CatalogueTaxRate>,
    val categories: List<CatalogueCategory>,
    val products: List<CatalogueProduct>,
) {
    init {
        require(currencyCode.length == CurrencySpec.CODE_LENGTH) { "Currency code must have three letters" }
        products.forEach { product ->
            require(product.taxRateIndex == null || product.taxRateIndex in taxRates.indices) {
                "Product '${product.name}' references a missing tax rate"
            }
            require(product.categoryIndex == null || product.categoryIndex in categories.indices) {
                "Product '${product.name}' references a missing category"
            }
        }
    }
}

/**
 * A named tax rate in a [Catalogue].
 *
 * @property name the label shown in the app and on receipts, e.g. "GST".
 * @property rateMilliPercent the rate in thousandths of a percent (10% = 10_000), see `TaxRates`.
 */
data class CatalogueTaxRate(
    val name: String,
    val rateMilliPercent: Int,
)

/**
 * A product category in a [Catalogue].
 *
 * @property name the category name shown on the sale screen, e.g. "Coffee".
 */
data class CatalogueCategory(
    val name: String,
)

/**
 * A product in a [Catalogue].
 *
 * @property name the product name shown on the sale screen and receipts.
 * @property priceMinor the unit price in minor units of [Catalogue.currencyCode]; the codec cannot encode negative
 *   prices.
 * @property taxRateIndex the index of the product's rate in [Catalogue.taxRates]; null imports as a 0% rate.
 * @property categoryIndex the index of the product's category in [Catalogue.categories], or null when uncategorised.
 * @property sku the barcode or SKU used to find the product by scanning, or null when it has none.
 * @property preAuthorisation true for a product taken as a pre-authorisation (sold on its own, with the amount only
 *   held on the card) rather than in a sale.
 */
data class CatalogueProduct(
    val name: String,
    val priceMinor: Long,
    val taxRateIndex: Int?,
    val categoryIndex: Int?,
    val sku: String?,
    val preAuthorisation: Boolean = false,
)

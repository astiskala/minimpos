package app.minimpos.terminal

import app.minimpos.terminal.transport.StoreAddress
import com.adyen.model.management.StoreLocation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import java.util.Properties

class StoreAddressTest {
    @Test
    fun `postal layouts follow the destination country rather than device locale`() {
        assertThat(format(" au ", "Sydney", "NSW", "2000"))
            .isEqualTo("1 Main St\nSydney NSW 2000\nAustralia")
        assertThat(format("US", "Seattle", "WA", "98101"))
            .isEqualTo("1 Main St\nSeattle, WA 98101\nUnited States")
        assertThat(format("CA", "Toronto", "ON", "M5V 2T6"))
            .isEqualTo("1 Main St\nToronto ON M5V 2T6\nCanada")
        assertThat(format("DE", "Berlin", postal = "10115"))
            .isEqualTo("1 Main St\n10115 Berlin\nGermany")
        assertThat(format("GB", "London", postal = "SW1A 1AA"))
            .isEqualTo("1 Main St\nLondon\nSW1A 1AA\nUnited Kingdom")
        assertThat(format("FI", "Helsinki", postal = "00100"))
            .isEqualTo("1 Main St\nFI-00100 Helsinki\nFinland")
    }

    @Test
    fun `Japanese and Chinese addresses select local or Latin script order`() {
        val japanese =
            StoreLocation()
                .country("JP")
                .line1("\u795e\u5bae\u524d 1-2-3")
                .city("\u6e0b\u8c37\u533a")
                .stateOrProvince("\u6771\u4eac\u90fd")
                .postalCode("150-0001")
        assertThat(StoreAddress.format(japanese))
            .isEqualTo("\u3012150-0001\n\u6771\u4eac\u90fd\n\u6e0b\u8c37\u533a\n\u795e\u5bae\u524d 1-2-3\nJapan")
        assertThat(StoreAddress.format(japanese.line1("1-2-3 Jingumae").city("Shibuya").stateOrProvince("Tokyo")))
            .isEqualTo("1-2-3 Jingumae\nShibuya, Tokyo\n150-0001\nJapan")
        assertThat(StoreAddress.format(japanese.line1("\uD840\uDC00")))
            .isEqualTo("\u3012150-0001\nTokyo\nShibuya\n\uD840\uDC00\nJapan")

        val chinese =
            StoreLocation()
                .country("CN")
                .line1("\u6df1\u5357\u5927\u9053 1\u53f7")
                .city("\u6df1\u5733\u5e02")
                .stateOrProvince("\u5e7f\u4e1c\u7701")
                .postalCode("518000")
        assertThat(StoreAddress.format(chinese))
            .isEqualTo("518000\n\u5e7f\u4e1c\u7701\u6df1\u5733\u5e02\n\u6df1\u5357\u5927\u9053 1\u53f7\nChina")
        assertThat(StoreAddress.format(chinese.line1("1 Shennan Road").city("Shenzhen").stateOrProvince("Guangdong")))
            .isEqualTo("1 Shennan Road\nShenzhen\nGuangdong, 518000\nChina")
    }

    @Test
    fun `Hong Kong order follows script and all three street lines survive`() {
        val address =
            StoreLocation()
                .country("HK")
                .line1("\u7687\u540e\u5927\u9053\u4e2d 1\u865f")
                .line2(" Shop 2 ")
                .line3(" Building A ")
                .city("\u4e2d\u74b0")
                .stateOrProvince("\u9999\u6e2f")
        assertThat(StoreAddress.format(address))
            .isEqualTo(
                "\u9999\u6e2f\n\u4e2d\u74b0\n\u7687\u540e\u5927\u9053\u4e2d 1\u865f\nShop 2\nBuilding A\n${countryName("HK")}",
            )
        assertThat(StoreAddress.format(address.line1("1 Queen's Road Central").city("Central").stateOrProvince("Hong Kong")))
            .isEqualTo("1 Queen's Road Central\nShop 2\nBuilding A\nCentral\nHong Kong\n${countryName("HK")}")
    }

    @Test
    fun `missing components leave no orphan punctuation or postal prefixes`() {
        assertThat(format("US", state = "WA", postal = "98101"))
            .isEqualTo("1 Main St\nWA 98101\nUnited States")
        assertThat(format("US", "Seattle", postal = "98101"))
            .isEqualTo("1 Main St\nSeattle 98101\nUnited States")
        assertThat(format("US", "Seattle", "WA"))
            .isEqualTo("1 Main St\nSeattle, WA\nUnited States")
        assertThat(format("US")).isEqualTo("1 Main St\nUnited States")
        assertThat(format("FI", "Helsinki")).isEqualTo("1 Main St\nHelsinki\nFinland")
        assertThat(StoreAddress.format(StoreLocation().country("JP").line1("\u6771\u4eac")))
            .isEqualTo("\u6771\u4eac\nJapan")
    }

    @Test
    fun `unknown countries and fields outside postal templates are not lost`() {
        assertThat(format(" xx ", "City", "State", "12345")).isEqualTo("1 Main St\nCity\nState\n12345\nxx")
        assertThat(format(null, "City", "State", "12345")).isEqualTo("1 Main St\nCity\nState\n12345")
        assertThat(format("ZZ", "City", "State", "12345")).isEqualTo("1 Main St\nCity\nState\n12345\nZZ")
        assertThat(format("DE", "Berlin", "Berlin", "10115")).isEqualTo("1 Main St\n10115 Berlin\nBerlin\nGermany")
        assertThat(format("SG", "Singapore", postal = "123456")).isEqualTo("1 Main St\nSINGAPORE 123456\nSingapore")
        assertThat(StoreAddress.format(StoreLocation().country("AG").city("St John's").postalCode("12345")))
            .isEqualTo("St John's\n12345\nAntigua & Barbuda")
    }

    @Test
    fun `empty fields stay blank and country-only records add no synthetic address lines`() {
        assertThat(StoreAddress.format(null)).isEmpty()
        assertThat(StoreAddress.format(StoreLocation())).isEmpty()
        assertThat(StoreAddress.format(StoreLocation().country(" "))).isEmpty()
        assertThat(
            StoreAddress.format(
                StoreLocation()
                    .country("AU")
                    .line1(" ")
                    .line2(" ")
                    .line3(" "),
            ),
        ).isEqualTo("Australia")
        assertThat(StoreAddress.format(StoreLocation().country("AX"))).isEqualTo(countryName("AX"))
    }

    @Test
    fun `bundled metadata covers countries and only supported format tokens`() {
        val formats = formats()
        assertThat(formats.stringPropertyNames()).containsAtLeastElementsIn(Locale.getISOCountries().toList())
        formats.stringPropertyNames().forEach { key ->
            val pattern = formats.getProperty(key)
            assertThat(key).matches("[A-Z]{2}(-Latn)?")
            assertThat(pattern).contains("%A")
            assertThat(pattern.replace(Regex("%[ACDFLNOSTXZn]"), "")) // Other fields are unavailable in Adyen StoreLocation.
                .doesNotContain("%")
        }
    }

    @Test
    fun `every template preserves supplied components with any combination of missing fields`() {
        formats()
            .stringPropertyNames()
            .filterNot { it.endsWith("-Latn") }
            .forEach { country ->
                val choices = listOf("A", "C", "S", "Z")
                val combinations = choices.fold(listOf(emptySet<String>())) { sets, field -> sets + sets.map { it + field } }
                combinations.forEach { present ->
                    listOf("Latin", "\u6587").forEach { script ->
                        val address =
                            StoreLocation()
                                .country(country)
                                .line1("Street$script".takeIf { "A" in present })
                                .city("City$script".takeIf { "C" in present })
                                .stateOrProvince("State$script".takeIf { "S" in present })
                                .postalCode("12345".takeIf { "Z" in present })
                        val result = StoreAddress.format(address)
                        listOfNotNull(address.line1, address.city, address.stateOrProvince, address.postalCode).forEach {
                            assertThat(result).contains(it)
                        }
                        assertThat(result).doesNotContain("%")
                        assertThat(result).doesNotContain("null")
                        assertThat(result).doesNotContain("\n\n")
                        assertThat(result).isEqualTo(result.trim())
                    }
                }
            }
    }

    private fun format(
        country: String?,
        city: String? = null,
        state: String? = null,
        postal: String? = null,
    ): String =
        StoreAddress.format(
            StoreLocation()
                .country(country)
                .line1("1 Main St")
                .city(city)
                .stateOrProvince(state)
                .postalCode(postal),
        )

    private fun countryName(country: String): String = Locale.forLanguageTag("und-$country").getDisplayCountry(Locale.ENGLISH)

    private fun formats(): Properties =
        Properties().apply {
            checkNotNull(StoreAddressTest::class.java.getResourceAsStream("/google-address-formats.properties"))
                .bufferedReader(Charsets.UTF_8)
                .use { load(it) }
        }
}

package app.minimpos.core.giftcard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GiftCardProvidersTest {
    @Test
    fun `operations follow Adyen's provider matrix`() {
        assertThat(GiftCardProvider.GIVEX.supports(GiftCardOperation.DEACTIVATE)).isTrue()
        listOf(GiftCardProvider.SVS, GiftCardProvider.FISERV, GiftCardProvider.INTERSOLVE).forEach {
            assertThat(it.supports(GiftCardOperation.DEACTIVATE)).isFalse()
            assertThat(it.supports(GiftCardOperation.UNDO_LOAD)).isTrue()
            assertThat(it.partialCashOut).isFalse()
        }
        assertThat(GiftCardProvider.LOCAL.operations).containsExactly(GiftCardOperation.REDEEM)
        assertThat(GiftCardProvider.GIVEX.partialCashOut).isTrue()
    }

    @Test
    fun `only Intersolve and local cards configure their card type`() {
        assertThat(GiftCardProvider.entries.filter { it.configuresCode })
            .containsExactly(GiftCardProvider.INTERSOLVE, GiftCardProvider.LOCAL)
        assertThat(GiftCardProvider.FISERV.wireCode).isEqualTo("valuelink")
        assertThat(GiftCardProvider.validCode("fashioncheque")).isTrue()
        assertThat(GiftCardProvider.validCode("")).isFalse()
        assertThat(GiftCardProvider.validCode("Gift Card")).isFalse()
        assertThat(GiftCardProvider.UNDO_WINDOW_MILLIS).isEqualTo(86_400_000)
        assertThat(GiftCardEntry.entries).containsExactly(GiftCardEntry.SCANNED, GiftCardEntry.SWIPED, GiftCardEntry.KEYED).inOrder()
    }
}

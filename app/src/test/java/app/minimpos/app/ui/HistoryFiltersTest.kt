package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** History's filter row on the smallest terminal. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class HistoryFiltersTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        compose.setContent { MiniMposApp(container) }
    }

    private fun sale(
        reference: String,
        brand: String?,
    ) = SaleEntity(
        id = reference,
        createdAt = System.currentTimeMillis(),
        currency = "AUD",
        taxMode = "INCLUSIVE",
        netMinor = 1000,
        taxMinor = 100,
        totalMinor = 1100,
        status = SaleStatus.APPROVED,
        merchantReference = reference,
        paymentBrand = brand,
    )

    @Test
    fun `keeps the payment method menu in view when it appears in open history`() {
        await { container.database.saleDao().insert(sale("REF-A", null)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("history"), 15_000)
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasText("REF-A"), 15_000)
        compose.onNodeWithTag("paymentMethod").assertDoesNotExist()
        // A first branded payment adds the menu before the filter chips while history is open.
        await { container.database.saleDao().insert(sale("REF-B", "mc")) }
        compose.waitUntilAtLeastOneExists(hasTestTag("paymentMethod"), 15_000)
        compose.waitForIdle()
        assertThat(
            compose
                .onNodeWithTag("paymentMethod")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
                .left,
        ).isAtLeast(0.dp)
    }
}

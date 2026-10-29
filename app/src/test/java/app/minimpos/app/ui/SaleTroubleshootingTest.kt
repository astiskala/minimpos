package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.feature.settings.SectionOpening
import app.minimpos.app.feature.settings.SettingsSectionScreen
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SaleTroubleshootingTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private val result = Route.SaleResult("sale")
    private val navigator = Navigator(NavBackStack<NavKey>(Route.Home, result))

    private fun show(
        status: SaleStatus,
        destination: String,
    ) {
        env.useSimulator()
        await {
            env.container.database.saleDao().insert(
                SaleEntity(
                    id = "sale",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 100,
                    taxMinor = 0,
                    totalMinor = 100,
                    status = status,
                    merchantReference = "reference",
                    context = PaymentContext(destination, "POI", "POS", "Merchant", "TEST"),
                ),
            )
        }
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    when (val route = navigator.current) {
                        is Route.SaleResult -> {
                            SaleResultScreen(route.saleId, navigator)
                        }

                        is Route.SettingsSection -> {
                            SettingsSectionScreen(
                                route.section,
                                navigator,
                                opening = SectionOpening(troubleshooting = route.troubleshooting),
                            )
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    @Test
    fun `failed Payments app result links to expanded Help without duplicating details or changing payment`() {
        show(SaleStatus.FAILED, "PAYMENTS_APP")
        openHelp(SaleStatus.FAILED)
    }

    @Test
    fun `unknown network payment offers same Help without resolving its outcome`() {
        show(SaleStatus.UNKNOWN, "TERMINAL")
        openHelp(SaleStatus.UNKNOWN)
    }

    private fun openHelp(status: SaleStatus) {
        compose.waitUntilAtLeastOneExists(hasTestTag("paymentTroubleshooting"), 15_000)
        compose.onNodeWithTag("tapTroubleshootingDetails").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertDoesNotExist()
        compose.onNodeWithTag("shareTapReport").assertDoesNotExist()
        compose.onNodeWithTag("paymentTroubleshooting").performScrollTo().performClick()
        compose.awaitCondition("Help opens at troubleshooting details") {
            navigator.current == Route.SettingsSection(SettingsSections.HELP, troubleshooting = true)
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("shareTapReport"), 15_000)
        compose.waitUntilDoesNotExist(hasTestTag("diagnosticsLoading"), 15_000)
        compose.onNodeWithTag("tapTroubleshootingDetails").assertIsDisplayed()
        compose.onNodeWithTag("diagnostic_appVersion").assertIsDisplayed()
        compose.onNodeWithTag("helpUserGuide").assertIsNotDisplayed()
        compose.onNodeWithTag("diagnostic_integration").performScrollTo().assertTextContains("SIMULATOR")
        compose.onNodeWithText("Read-only checks do not prove", substring = true).assertDoesNotExist()
        assertThat(
            await {
                env.container.database
                    .saleDao()
                    .sale("sale")
            }?.sale?.status,
        ).isEqualTo(status)
        compose.runOnIdle { navigator.back() }
        compose.awaitCondition("Back preserves payment result") { navigator.current == result }
        compose.waitUntilAtLeastOneExists(hasTestTag("paymentTroubleshooting"), 15_000)
    }

    @Test
    fun `approved result contains neither troubleshooting details nor Help action`() {
        show(SaleStatus.APPROVED, "PAYMENTS_APP")
        compose.waitUntilAtLeastOneExists(hasTestTag("home"), 15_000)
        compose.onNodeWithTag("paymentTroubleshooting").assertDoesNotExist()
        compose.onNodeWithTag("tapTroubleshootingDetails").assertDoesNotExist()
        compose.onNodeWithTag("shareTapReport").assertDoesNotExist()
    }
}

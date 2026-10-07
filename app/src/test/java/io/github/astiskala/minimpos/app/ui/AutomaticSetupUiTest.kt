package io.github.astiskala.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.feature.settings.SettingsSectionScreen
import io.github.astiskala.minimpos.app.feature.transfer.ImportUiState
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportViewModel
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.SharedKeyUpdate
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class AutomaticSetupUiTest {
    private var lookupAvailable = true
    private var sharedKeyAvailable = true
    private var emptyListing = false
    private val device = FakeDevice()
    private val paymentsApp = FakePaymentsApp()
    private val searches = mutableListOf<TerminalEnvironment>()
    private var keyLookups = 0
    private val terminal = FakeTerminal()
    private var generatedKey: DiscoveredKey? = null
    private var activateKey = true
    private var creations = 0
    private val details: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) = CredentialLookup.Allowed

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing {
                searches += environment
                return if (lookupAvailable) {
                    TerminalListing.Listed(
                        if (emptyListing) emptyList() else listOf(TerminalDetails(POI_ID, "Merchant", "192.168.1.42")),
                        environment,
                    )
                } else {
                    TerminalListing.Failed("Lookup denied")
                }
            }

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup {
                keyLookups++
                generatedKey?.let { return SharedKeyLookup.Found(it) }
                return if (sharedKeyAvailable) {
                    SharedKeyLookup.Found(
                        DiscoveredKey("store-key", 2, "correct horse battery staple"),
                    )
                } else {
                    SharedKeyLookup.Missing
                }
            }

            override suspend fun createSharedKey(
                id: String,
                environment: TerminalEnvironment,
                key: DiscoveredKey,
            ): SharedKeyUpdate {
                creations++
                generatedKey = key
                if (activateKey) terminal.passphrase = key.passphrase
                return SharedKeyUpdate.Ready(key, created = true)
            }
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(device, terminal = terminal, paymentsApp = paymentsApp, terminalDetails = details)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun importAutomatic(
        destination: String = "network",
        environment: TerminalEnvironment = TerminalEnvironment.TEST,
        automatic: Boolean = true,
        manualDetails: String = "",
        secrets: String = """{"ADYEN_API_KEY":"imported-key"}""",
        succeeds: Boolean = true,
        register: Boolean = false,
        awaitKey: Boolean = false,
    ): TransferImportViewModel {
        val container = env.container
        container.start()
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val account = if (destination == "tapToPay") """"merchantAccount":"Merchant",""" else ""
        val connection =
            """{"destination":"$destination","environment":"$environment","automatic":$automatic,""" +
                """$account$manualDetails"liveUrlPrefix":"1797a841fbb37ca7-AdyenDemo"}"""
        val public = Transfer(connection = connection)
        val encrypted = seal.seal(secrets.toByteArray(), code, TransferCodec.authenticationData(public))
        val transfer = public.copy(sealedSecrets = SealedSecrets(encrypted))
        val vm = TransferImportViewModel(container.setupTransfer, "AUD", container.setupImport)
        compose.awaitCondition("Opening scanner") { vm.state.value is ImportUiState.Scanning }
        QrChunks.split(TransferCodec.encode(transfer), "AUTO").forEach { vm.onCode(it.encode()) }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.TransferImport))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    when (val route = navigator.current) {
                        is Route.SettingsSection -> SettingsSectionScreen(route.section, navigator, helperSetup = route.helperSetup)
                        else -> TransferImportScreen(navigator, vm = vm)
                    }
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithTag("transferCodeInput").performScrollTo().performTextInput(code)
        compose.onNodeWithTag("import").performClick()
        if (awaitKey) {
            compose.awaitCondition("Explicit key creation is offered") { (vm.state.value as? ImportUiState.Ready)?.sharedKeyOffer != null }
            waitForTag("confirmSharedKey")
        } else if (!succeeds) {
            compose.awaitCondition("Rejected candidate remains editable") { (vm.state.value as? ImportUiState.Ready)?.outcome != null }
        } else {
            finishImport(vm, register)
        }
        return vm
    }

    private fun finishImport(
        vm: TransferImportViewModel,
        register: Boolean,
    ) {
        if (register) {
            compose.awaitCondition("Explicit registration is offered") {
                val ready = vm.state.value as? ImportUiState.Ready
                ready?.boardingRequired == true
            }
            assertThat(paymentsApp.opened).isEmpty()
            compose.onNodeWithTag("import").assertTextContains(env.context.getString(R.string.settings_set_up_tap_to_pay)).performClick()
        }
        waitForTag("importDone")
        compose.onNodeWithTag("importFinished").assertIsDisplayed().performClick()
    }

    @Test
    fun `Automatic import verifies before saving and keeps all obtained sections visible`() {
        importAutomatic()
        waitForTag("host")
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("store-key", substring = true)
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("apiKey").assertExists()
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")
        assertThat(searches).isNotEmpty()
    }

    @Test
    fun `Automatic LIVE cloud setup keeps prefix and terminal visible without retesting API`() {
        importAutomatic("cloud", TerminalEnvironment.LIVE)
        waitForTag("livePrefix")
        compose.onNodeWithTag("livePrefix").performScrollTo().assertTextContains("1797a841fbb37ca7-AdyenDemo", substring = true)
        compose.onNodeWithTag("poiId").performScrollTo().assertTextContains(POI_ID, substring = true)
        assertThat(env.container.settingsState.value.terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(searches).doesNotContain(TerminalEnvironment.TEST)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `unavailable lookup rejects import without saving credentials or offering manual bypass`() {
        lookupAvailable = false
        importAutomatic(succeeds = false)
        compose.onNodeWithText(env.context.getString(R.string.setup_management_unavailable)).performScrollTo().assertIsDisplayed()
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
        assertThat(env.container.settingsState.value.terminal.merchantAccount).isEmpty()
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `empty terminal list reports account access rather than offering an empty selector`() {
        emptyListing = true
        importAutomatic(succeeds = false)
        compose.onNodeWithText(env.context.getString(R.string.setup_terminal_access)).performScrollTo().assertIsDisplayed()
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
    }

    @Test
    fun `missing automatic key requires visible terminal confirmation and cancellation writes nothing`() = cancelMissingKey()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese missing key confirmation fits AMS1 and cancellation writes nothing`() = cancelMissingKey()

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese missing key confirmation fits AMS1 and cancellation writes nothing`() = cancelMissingKey()

    private fun cancelMissingKey() {
        sharedKeyAvailable = false
        val vm = importAutomatic(awaitKey = true)
        compose
            .onNodeWithTag(
                "sharedKeyConfirmation",
            ).assertTextContains(POI_ID, substring = true)
            .assertTextContains("TEST", substring = true)
        compose.onNodeWithTag("confirmSharedKey").assertIsDisplayed()
        assertThat(creations).isEqualTo(0)
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
        compose.onNodeWithText(env.context.getString(R.string.action_cancel)).performClick()
        compose.awaitCondition("Confirmation dismissed") { (vm.state.value as? ImportUiState.Ready)?.sharedKeyOffer == null }
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
        assertThat(env.container.settingsState.value.terminal.host).isEmpty()
        assertThat(creations).isEqualTo(0)
    }

    @Test
    fun `created key remains recoverable until terminal activation and Check again completes import`() {
        sharedKeyAvailable = false
        activateKey = false
        val vm = importAutomatic(awaitKey = true)
        compose.onNodeWithTag("confirmSharedKey").assertIsDisplayed().performClick()
        compose.awaitCondition("Created key awaits terminal activation") { (vm.state.value as? ImportUiState.Ready)?.keyPending == true }
        compose
            .onNodeWithTag(
                "import",
            ).assertIsDisplayed()
            .assertTextContains(env.context.getString(R.string.settings_shared_key_check_again))
        compose.onNodeWithText(env.context.getString(R.string.transfer_key_not_imported)).performScrollTo().assertIsDisplayed()
        assertThat(creations).isEqualTo(1)
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isTrue()
        terminal.passphrase = checkNotNull(generatedKey).passphrase
        compose.onNodeWithTag("import").performClick()
        waitForTag("importDone")
        assertThat(creations).isEqualTo(1)
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo(checkNotNull(generatedKey).passphrase)
    }

    @Test
    fun `complete Tap to Pay QR visibly requires explicit registration before committing`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        importAutomatic(
            "tapToPay",
            manualDetails = """"keyIdentifier":"manual-key","keyVersion":3,""",
            secrets = """{"ADYEN_API_KEY":"imported-key","TERMINAL_PASSPHRASE":"manual secret","PAYMENTS_APP_API_KEY":"boarding-key"}""",
            register = true,
        )
        waitForTag("keyIdentifier")
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("manual-key", substring = true)
        compose.onNodeWithTag("paymentsAppKey").assertExists()
        assertThat(searches).isEmpty()
        assertThat(paymentsApp.opened).isNotEmpty()
    }

    @Test
    fun `incomplete Tap to Pay QR does not open registration or save partial setup`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        importAutomatic("tapToPay", succeeds = false)
        assertThat(paymentsApp.opened).isEmpty()
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
        compose.onNodeWithText(env.context.getString(R.string.transfer_missing_fields)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Manual helper imports tested complete fields expanded without retrieving a shared key`() {
        importAutomatic(
            automatic = false,
            manualDetails = """"merchantAccount":"Merchant","host":"192.168.1.42","poiId":"$POI_ID","keyIdentifier":"manual-key",""",
            secrets = """{"ADYEN_API_KEY":"imported-key","TERMINAL_PASSPHRASE":"correct horse battery staple"}""",
        )
        waitForTag("host")
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("passphrase").assertExists()
        assertThat(keyLookups).isEqualTo(0)
    }

    private companion object {
        const val POI_ID = "S1F2-000158213605014"
    }
}

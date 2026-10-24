package app.minimpos.app.data

import androidx.datastore.core.CorruptionException
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.PaymentContextConverter
import app.minimpos.app.data.repo.RefundedLine
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.security.RecoveryJournal
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretBlob
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.JsonDataStoreSerializer
import app.minimpos.app.data.settings.StorageJson
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.newInstallation
import app.minimpos.app.terminal.BoardingCredential
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.receipt.CardReceiptLine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Settings, secrets and the JSON kept in Room columns and encrypted journals are stored as JSON without the schema
 * export Room gives tables. An unreadable settings or secrets file is replaced with the defaults, so a renamed field,
 * enum constant or [Secret] would silently reset every merchant on upgrade. This test keeps the formats reviewable
 * (`stored/formats.txt`) and loads the files each released version wrote (`stored/<version>/`).
 */
@RunWith(RobolectricTestRunner::class)
class StoredFormatTest {
    private val contextJson = Json { encodeDefaults = true }

    /** Every stored JSON root and its type; the journals' payload types are private to their owners. */
    private val roots: Map<String, SerialDescriptor> =
        linkedMapOf(
            "settings.json" to serializer<AppSettings>().descriptor,
            "secrets.json" to serializer<SecretBlob>().descriptor,
            "Room column: payment context" to serializer<PaymentContext>().descriptor,
            "Room column: card receipt lines" to serializer<List<CardReceiptLine>>().descriptor,
            "Room column: refunded lines" to serializer<List<RefundedLine>>().descriptor,
            "Room column: tender allocation" to serializer<TenderAllocation>().descriptor,
            "Room column: history switch target" to serializer<TerminalSettings>().descriptor,
            "Encrypted journal: setup import" to journal("app.minimpos.app.data.transfer.PendingSetupImport"),
            "Encrypted journal: Tap to Pay registration" to journal("app.minimpos.app.terminal.TapToPaySetup\$RegistrationRecovery"),
            "Encrypted journal: key creation" to journal("app.minimpos.app.terminal.SharedKeySetup\$KeyRecords"),
            "Encrypted journal: boarding credentials" to serializer<List<BoardingCredential>>().descriptor,
        )

    private fun journal(name: String): SerialDescriptor = serializer(Class.forName(name)).descriptor

    private fun resource(path: String): String = checkNotNull(javaClass.getResource("/stored/$path")) { "No stored/$path" }.readText()

    /** The versions whose files are kept under `stored/`, oldest first. */
    private val released: List<String> =
        File(checkNotNull(javaClass.getResource("/stored")).toURI())
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()

    private val reports = File("build/reports/stored-formats")

    /** Secrets earlier versions stored that this one no longer has; the next secret write deletes them. */
    private val retiredSecrets = setOf("PAYMENTS_APP_API_KEY")

    @Test
    fun `stored formats match the reviewed snapshot`() {
        val actual = roots.entries.joinToString("\n") { (root, descriptor) -> "$root:" + StoredFixtures.format(descriptor) }
        val expected = resource("formats.txt")
        if (actual != expected) reports.apply { mkdirs() }.resolve("formats.txt").writeText(actual)
        assertWithMessage(
            "A stored JSON format changed. Files earlier versions wrote must still load, so keep every fixture under " +
                "src/test/resources/stored/ passing, then copy build/reports/stored-formats/formats.txt over stored/formats.txt.",
        ).that(actual).isEqualTo(expected)
    }

    @Test
    fun `settings, secrets and payment contexts each released version wrote still load without losing anything`() {
        assertThat(released).contains("1.0.1")
        released.forEach { version ->
            loads(version, resource("$version/settings.json"), resource("$version/secrets.json"), resource("$version/payment-context.json"))
        }
    }

    @Test
    fun `the current version's files load too, and are kept for the next release's fixture`() {
        val written = TestEnvironment()
        val settings: String
        val secrets: String
        try {
            val container = written.container
            val filled = StoredFixtures.filled(StorageJson, serializer<AppSettings>(), newInstallation("AU"), "current")
            await {
                container.settings.update { filled }
                Secret.entries.forEach { container.secrets.set(it, "${it.name} current") }
                RecoveryJournal.entries.forEach { container.secrets.journal(it, String.serializer()).write("${it.name} current") }
            }
            settings = File(written.dir, "settings.json").readText()
            secrets = File(written.dir, "secrets.json").readText()
        } finally {
            written.close()
        }
        val context =
            StoredFixtures.filled(
                contextJson,
                serializer<PaymentContext>(),
                PaymentContext("TERMINAL", "S1F2", "POS", "Merchant", "TEST"),
                "current",
            )
        val contextText = checkNotNull(PaymentContextConverter().encode(context))
        reports.resolve("current").apply { mkdirs() }.let { dir ->
            dir.resolve("settings.json").writeText(settings)
            dir.resolve("secrets.json").writeText(secrets)
            dir.resolve("payment-context.json").writeText(contextText + "\n")
        }
        loads("current", settings, secrets, contextText)
    }

    @Test
    fun `a renamed enum constant makes a stored file unreadable, and lost fields are reported`() {
        val renamed = resource("1.0.1/settings.json").replace("\"EXCLUSIVE\"", "\"EXCLUSIVE_OF_TAX\"")
        assertThrows(CorruptionException::class.java) {
            await { JsonDataStoreSerializer(serializer<AppSettings>(), AppSettings()).readFrom(renamed.byteInputStream()) }
        }
        val stored = Json.parseToJsonElement("""{"kept":1,"changed":"a","list":[1,2],"gone":{"x":true}}""")
        val current = Json.parseToJsonElement("""{"kept":1,"changed":"b","list":[1],"added":0}""")
        assertThat(StoredFixtures.lost(stored, current)).containsExactly(
            "$.changed: \"a\" → \"b\"",
            "$.list: [1,2] → [1]",
            "$.gone: {\"x\":true} → (no longer stored)",
        )
    }

    /** [settings], [secrets] and [context] as the app reads them: every stored field kept, every secret and journal found. */
    private fun loads(
        version: String,
        settings: String,
        secrets: String,
        context: String,
    ) {
        // Read as the app reads them: an unreadable settings or secrets file would be replaced with the defaults.
        val readSettings = await { JsonDataStoreSerializer(serializer<AppSettings>(), AppSettings()).readFrom(settings.byteInputStream()) }
        assertKept("$version settings.json", settings, StorageJson.encodeToJsonElement(serializer<AppSettings>(), readSettings))
        val readSecrets = await { JsonDataStoreSerializer(serializer<SecretBlob>(), SecretBlob()).readFrom(secrets.byteInputStream()) }
        assertKept("$version secrets.json", secrets, StorageJson.encodeToJsonElement(serializer<SecretBlob>(), readSecrets))
        val readContext = checkNotNull(PaymentContextConverter().decode(context.trim()))
        assertKept("$version payment context", context, contextJson.encodeToJsonElement(serializer<PaymentContext>(), readContext))

        // The container opens a data folder holding these files as it would after an upgrade.
        val env = TestEnvironment(storedFiles = mapOf("settings.json" to settings, "secrets.json" to secrets))
        try {
            assertWithMessage("$version settings were replaced")
                .that(await { env.container.settings.current() })
                .isEqualTo(readSettings.normalized())
            readSecrets.values.keys.forEach { name ->
                val secret = Secret.entries.find { it.name == name }
                if (name in retiredSecrets) {
                    assertWithMessage("$version retired secret $name").that(secret).isNull()
                    return@forEach
                }
                assertWithMessage("$version stores secret $name, which this version no longer reads").that(secret).isNotNull()
                assertWithMessage(
                    "$version secret $name",
                ).that(await { env.container.secrets.get(checkNotNull(secret)) }).startsWith("$name ")
            }
            RecoveryJournal.entries.forEach { kind ->
                assertWithMessage("$version $kind journal")
                    .that(
                        await {
                            env.container.secrets
                                .hasJournal(kind)
                                .first()
                        },
                    ).isEqualTo(kind.get(readSecrets) != null)
            }
            await { env.container.secrets.set(Secret.SMTP_PASSWORD, "SMTP_PASSWORD changed") }
            val rewritten = File(env.dir, "secrets.json").readText()
            retiredSecrets.forEach { assertWithMessage("$version $it after a secret write").that(rewritten).doesNotContain("\"$it\"") }
        } finally {
            env.close()
        }
    }

    private fun assertKept(
        what: String,
        stored: String,
        current: JsonElement,
    ) = assertWithMessage("$what lost stored fields").that(StoredFixtures.lost(Json.parseToJsonElement(stored), current)).isEmpty()
}

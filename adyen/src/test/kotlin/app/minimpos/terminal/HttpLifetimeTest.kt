package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.ConnectClients
import app.minimpos.terminal.transport.TerminalHttpClient
import com.google.common.truth.Truth.assertThat
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaConstructor
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import okhttp3.OkHttpClient
import org.junit.Test

class HttpLifetimeTest {
    @Test
    fun `HTTP senders never clone clients for individual requests`() {
        val all =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.minimpos.terminal.transport")
        val owners = listOf(AdyenHttp::class.java.name, TerminalHttpClient::class.java.name)
        clientLifetime.check(
            all.that(
                DescribedPredicate.describe("HTTP sender families") { type ->
                    owners.any { type.name == it || type.name.startsWith(it + "$") }
                },
            ),
        )
    }

    @Test
    fun `client lifetime rule rejects rebuilding in a request`() {
        assertThat(clientLifetime.evaluate(ClassFileImporter().importClasses(PerRequestClient::class.java)).hasViolation()).isTrue()
    }

    private class PerRequestClient {
        fun request(client: OkHttpClient): OkHttpClient = client.newBuilder().build()
    }

    @Test
    fun `connect variants reuse policy pools and timeouts with bounded least recently used retention`() {
        val base = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val clients = ConnectClients(base)
        val one = clients.get(10)
        val two = clients.get(20)
        clients.get(30)
        clients.get(40)
        assertThat(clients.get(10)).isSameInstanceAs(one)
        clients.get(50)
        assertThat(clients.get(10)).isSameInstanceAs(one)
        assertThat(clients.get(20)).isNotSameInstanceAs(two)
        assertThat(one.connectTimeoutMillis).isEqualTo(10)
        assertThat(one.connectionPool).isSameInstanceAs(base.connectionPool)
        assertThat(one.dispatcher).isSameInstanceAs(base.dispatcher)
        assertThat(one.sslSocketFactory).isSameInstanceAs(base.sslSocketFactory)
        assertThat(one.hostnameVerifier).isSameInstanceAs(base.hostnameVerifier)
        assertThat(one.retryOnConnectionFailure).isFalse()
    }

    private companion object {
        // Only construction chooses transport policy; sends reuse it and attach independent call deadlines.
        val clientLifetime: ArchRule =
            noClasses().should().callMethodWhere(
                object : DescribedPredicate<JavaMethodCall>("clone an HTTP client outside construction") {
                    override fun test(call: JavaMethodCall): Boolean =
                        call.origin !is JavaConstructor &&
                            call.target.owner.name == "okhttp3.OkHttpClient" && call.target.name == "newBuilder"
                },
            )
    }
}

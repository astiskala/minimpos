package app.minimpos.app

import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.JSON
import com.adyen.model.checkout.LineItem
import com.adyen.model.checkout.PaymentAmountUpdateRequest
import com.adyen.model.checkout.PaymentCaptureRequest
import com.adyen.model.checkout.PaymentLinkRequest
import com.adyen.model.checkout.PaymentMethodsRequest
import com.adyen.model.checkout.UpdatePaymentLinkRequest
import com.adyen.model.clouddevice.ConnectedDevicesResponse
import com.adyen.model.management.Key
import com.adyen.model.management.Merchant
import com.adyen.model.management.Nexo
import com.adyen.model.management.PaymentMethod
import com.adyen.model.management.PaymentMethodResponse
import com.adyen.model.management.Store
import com.adyen.model.management.StoreLocation
import com.adyen.model.management.Terminal
import com.adyen.model.management.TerminalSettings
import com.adyen.model.paymentsapp.BoardingTokenRequest
import com.android.tools.r8.ClassFileConsumer
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import jakarta.ws.rs.ext.ContextResolver
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

class AdyenModelR8Test {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `release rules preserve official API JSON without exposing bookkeeping fields`() {
        val cases =
            mapOf(
                PaymentMethodsRequest::class.java to """{"merchantAccount":"HarbourCoffeeCOM"}""",
                PaymentCaptureRequest::class.java to
                    """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},"reference":"capture"}""",
                PaymentAmountUpdateRequest::class.java to
                    """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},"industryUsage":"delayedCharge"}""",
                PaymentLinkRequest::class.java to
                    """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},""" +
                    """"lineItems":[{"id":"coffee","quantity":1}]}""",
                UpdatePaymentLinkRequest::class.java to """{"status":"expired"}""",
                TerminalSettings::class.java to
                    """{"nexo":{"encryptionKey":{"identifier":"test-key","version":1,"passphrase":"synthetic"}}}""",
                Merchant::class.java to """{"id":"HarbourCoffeeCOM","name":"Harbour Coffee"}""",
                Store::class.java to
                    """{"id":"ST1","shopperStatement":"Harbour Coffee","phoneNumber":"+61212345678",""" +
                    """"address":{"line1":"1 Main St","city":"Sydney","country":"AU"}}""",
                PaymentMethodResponse::class.java to
                    """{"data":[{"type":"wechatpay_pos","enabled":true,"allowed":true,"shopperInteraction":"pos","currencies":["AUD"]}]}""",
                Terminal::class.java to """{"id":"S1F2L-123456789","model":"S1F2L","countryCode":"AU"}""",
                BoardingTokenRequest::class.java to """{"boardingRequestToken":"synthetic"}""",
                ConnectedDevicesResponse::class.java to """{"uniqueDeviceIds":["AMS1-1"]}""",
            )
        shrink(
            cases.keys +
                listOf(
                    Amount::class.java,
                    LineItem::class.java,
                    Nexo::class.java,
                    Key::class.java,
                    PaymentMethod::class.java,
                    StoreLocation::class.java,
                ),
        ).use { loader ->
            cases.forEach { (model, json) ->
                val type = loader.loadClass(model.name)
                val value = JSON.getMapper().readValue(json, type)
                val body = type.getMethod("toJson").invoke(value) as String
                assertThat(JsonParser.parseString(body)).isEqualTo(JsonParser.parseString(json))
            }
            val model = loader.loadClass(PaymentMethodsRequest::class.java.name)
            val request = model.getConstructor().newInstance()
            model.getMethod("merchantAccount", String::class.java).invoke(request, "HarbourCoffeeCOM")
            assertThat(JsonParser.parseString(model.getMethod("toJson").invoke(request) as String))
                .isEqualTo(JsonParser.parseString(cases.getValue(PaymentMethodsRequest::class.java)))
        }
    }

    private fun shrink(models: Collection<Class<*>>): URLClassLoader {
        val sdk = location(PaymentMethodsRequest::class.java)
        val output = temporary.root.resolve("models.jar").toPath()
        val builder =
            R8Command
                .builder()
                .setMode(CompilationMode.RELEASE)
                .setProgramConsumer(ClassFileConsumer.ArchiveConsumer(output))
                .addProguardConfigurationFiles(File(checkNotNull(System.getProperty("minimpos.adyenModelRules"))).toPath())
                .addProguardConfiguration(
                    models.map {
                        "-keep,allowoptimization class ${it.name} { public <init>(); " +
                            "public *** merchantAccount(...); public *** toJson(...); }"
                    } +
                        listOf(
                            "-allowaccessmodification",
                            "-keepnames class com.adyen.model.**",
                            "-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }",
                        ),
                    Origin.unknown(),
                ).addLibraryResourceProvider(JdkClassFileProvider.fromSystemJdk())
        listOf(
            PaymentMethodsRequest::class.java,
            JsonIgnore::class.java,
            JsonFactory::class.java,
            ObjectMapper::class.java,
            ContextResolver::class.java,
        ).map(::location)
            .distinct()
            .forEach { builder.addLibraryFiles(it.toPath()) }
        val paths = models.map { it.name.replace('.', '/') }
        val names = mutableSetOf<String>()
        JarFile(sdk).use { jar ->
            jar
                .entries()
                .asSequence()
                .filter { entry ->
                    paths.any { entry.name == "$it.class" || entry.name.startsWith("$it$") }
                }.forEach { entry ->
                    names += entry.name.removeSuffix(".class").replace('/', '.')
                    builder.addClassProgramData(jar.getInputStream(entry).use { it.readBytes() }, Origin.unknown())
                }
        }
        R8.run(builder.build())
        return object : URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader) {
            override fun loadClass(
                name: String,
                resolve: Boolean,
            ): Class<*> =
                if (name in names) {
                    synchronized(this) {
                        (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    }
                } else {
                    super.loadClass(name, resolve)
                }
        }
    }

    private fun location(type: Class<*>): File = File(checkNotNull(type.protectionDomain).codeSource.location.toURI())
}

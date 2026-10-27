package app.minimpos.app

import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.CardDetails
import com.adyen.model.checkout.CheckoutPaymentMethod
import com.adyen.model.checkout.JSON
import com.adyen.model.checkout.LineItem
import com.adyen.model.checkout.PaymentAmountUpdateRequest
import com.adyen.model.checkout.PaymentCaptureRequest
import com.adyen.model.checkout.PaymentLinkRequest
import com.adyen.model.checkout.PaymentMethodsRequest
import com.adyen.model.checkout.PaymentRequest
import com.adyen.model.checkout.UpdatePaymentLinkRequest
import com.adyen.model.clouddevice.ConnectedDevicesResponse
import com.adyen.model.management.ApiCredential
import com.adyen.model.management.CreateApiCredentialResponse
import com.adyen.model.management.CreateMerchantApiCredentialRequest
import com.adyen.model.management.Dcc
import com.adyen.model.management.GenerateApiKeyResponse
import com.adyen.model.management.GenerateClientKeyResponse
import com.adyen.model.management.Gratuity
import com.adyen.model.management.Key
import com.adyen.model.management.ListMerchantApiCredentialsResponse
import com.adyen.model.management.ListStoresResponse
import com.adyen.model.management.Merchant
import com.adyen.model.management.MinorUnitsMonetaryValue
import com.adyen.model.management.ModelConfiguration
import com.adyen.model.management.Moto
import com.adyen.model.management.Nexo
import com.adyen.model.management.OfflineProcessing
import com.adyen.model.management.PaymentMethod
import com.adyen.model.management.PaymentMethodResponse
import com.adyen.model.management.Store
import com.adyen.model.management.StoreAndForward
import com.adyen.model.management.StoreLocation
import com.adyen.model.management.SupportedCardTypes
import com.adyen.model.management.Surcharge
import com.adyen.model.management.Terminal
import com.adyen.model.management.TerminalSettings
import com.adyen.model.payment.VoidPendingRefundRequest
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
import com.adyen.model.payment.ModificationResult as PalModificationResult

class AdyenModelR8Test {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `release rules preserve official API JSON without exposing bookkeeping fields`() {
        val cases = modelCases()
        shrink(cases.keys + extraTypes()).use { loader ->
            // Constructing the wrapper initializes its GenericType subclasses, just as pressing Charge does.
            val cardType = loader.loadClass(CardDetails::class.java.name)
            val card = cardType.getConstructor().newInstance()
            cardType.getMethod("setStoredPaymentMethodId", String::class.java).invoke(card, "TOKEN1")
            val methodType = loader.loadClass(CheckoutPaymentMethod::class.java.name)
            val method = methodType.getConstructor(cardType).newInstance(card)
            val requestType = loader.loadClass(PaymentRequest::class.java.name)
            val request = requestType.getConstructor().newInstance()
            requestType.getMethod("setPaymentMethod", methodType).invoke(request, method)
            assertThat(JsonParser.parseString(requestType.getMethod("toJson").invoke(request) as String))
                .isEqualTo(JsonParser.parseString("""{"paymentMethod":{"storedPaymentMethodId":"TOKEN1"}}"""))
            cases.forEach { (model, json) ->
                val type = loader.loadClass(model.name)
                val value = JSON.getMapper().readValue(json, type)
                val body = type.getMethod("toJson").invoke(value) as String
                assertThat(JsonParser.parseString(body)).isEqualTo(JsonParser.parseString(json))
            }
            val model = loader.loadClass(PaymentMethodsRequest::class.java.name)
            val methodsRequest = model.getConstructor().newInstance()
            model.getMethod("merchantAccount", String::class.java).invoke(methodsRequest, "HarbourCoffeeCOM")
            assertThat(JsonParser.parseString(model.getMethod("toJson").invoke(methodsRequest) as String))
                .isEqualTo(JsonParser.parseString(cases.getValue(PaymentMethodsRequest::class.java)))
        }
    }

    private fun modelCases(): Map<Class<*>, String> =
        mapOf(
            PaymentMethodsRequest::class.java to """{"merchantAccount":"HarbourCoffeeCOM"}""",
            PaymentRequest::class.java to
                """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":1250},""" +
                """"paymentMethod":{"type":"scheme","storedPaymentMethodId":"TOKEN1"},""" +
                """"shopperReference":"CUST-001","shopperInteraction":"ContAuth","recurringProcessingModel":"UnscheduledCardOnFile"}""",
            PaymentCaptureRequest::class.java to
                """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},"reference":"capture"}""",
            PaymentAmountUpdateRequest::class.java to
                """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},"industryUsage":"delayedCharge"}""",
            PaymentLinkRequest::class.java to
                """{"merchantAccount":"HarbourCoffeeCOM","amount":{"currency":"AUD","value":3900},""" +
                """"lineItems":[{"id":"coffee","quantity":1}]}""",
            UpdatePaymentLinkRequest::class.java to """{"status":"expired"}""",
            VoidPendingRefundRequest::class.java to """{"merchantAccount":"HarbourCoffeeCOM","originalReference":"PSPR1"}""",
            PalModificationResult::class.java to """{"pspReference":"VOID1","response":"[voidPendingRefund-received]"}""",
            TerminalSettings::class.java to
                """{"dcc":{"enableDcc":true},"gratuities":[{"allowCustomAmount":true,""" +
                """"currency":"AUD","predefinedTipEntries":["10%"],"usePredefinedTipEntries":true}],""" +
                """"moto":{"enableMoto":true,"maxAmount":500},""" +
                """"nexo":{"encryptionKey":{"identifier":"test-key","version":1,"passphrase":"synthetic"}},""" +
                """"offlineProcessing":{"chipFloorLimit":50},""" +
                """"storeAndForward":{"maxAmount":[{"amount":5000,"currencyCode":"AUD"}],""" +
                """"maxPayments":10,"supportedCardTypes":{"credit":true,"debit":true}},""" +
                """"surcharge":{"askConfirmation":true}}""",
            Merchant::class.java to """{"id":"HarbourCoffeeCOM","name":"Harbour Coffee"}""",
            Store::class.java to
                """{"id":"ST1","shopperStatement":"Harbour Coffee","phoneNumber":"+61212345678",""" +
                """"address":{"line1":"1 Main St","city":"Sydney","country":"AU"}}""",
            ListStoresResponse::class.java to
                """{"data":[{"id":"ST1","reference":"counter","description":"Main counter"}],"_links":{"next":{"href":"next"}}}""",
            PaymentMethodResponse::class.java to
                """{"data":[{"type":"wechatpay_pos","enabled":true,"allowed":true,"shopperInteraction":"pos","currencies":["AUD"]}]}""",
            Terminal::class.java to """{"id":"S1F2L-123456789","model":"S1F2L","countryCode":"AU"}""",
            BoardingTokenRequest::class.java to """{"boardingRequestToken":"synthetic"}""",
            CreateMerchantApiCredentialRequest::class.java to
                """{"description":"Mini mPOS synthetic","roles":["Adyen Payments App role"]}""",
            CreateApiCredentialResponse::class.java to
                """{"id":"C1","apiKey":"synthetic","clientKey":"test_synthetic","active":true,"roles":["Adyen Payments App role"]}""",
            ListMerchantApiCredentialsResponse::class.java to
                """{"data":[{"id":"C1","description":"Mini mPOS synthetic"}],"pagesTotal":1}""",
            GenerateApiKeyResponse::class.java to """{"apiKey":"synthetic"}""",
            GenerateClientKeyResponse::class.java to """{"clientKey":"test_synthetic"}""",
            ConnectedDevicesResponse::class.java to """{"uniqueDeviceIds":["AMS1-1"]}""",
        )

    private fun extraTypes(): List<Class<*>> =
        listOf(
            Amount::class.java,
            CardDetails::class.java,
            CheckoutPaymentMethod::class.java,
            LineItem::class.java,
            Dcc::class.java,
            Gratuity::class.java,
            Moto::class.java,
            OfflineProcessing::class.java,
            StoreAndForward::class.java,
            MinorUnitsMonetaryValue::class.java,
            SupportedCardTypes::class.java,
            Surcharge::class.java,
            ModelConfiguration::class.java,
            Nexo::class.java,
            Key::class.java,
            PaymentMethod::class.java,
            StoreLocation::class.java,
            ApiCredential::class.java,
        )

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
                        "-keep,allowoptimization class ${it.name} { public <init>(...); " +
                            "public *** merchantAccount(...); public *** toJson(...); }"
                    } +
                        listOf(
                            "-allowaccessmodification",
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

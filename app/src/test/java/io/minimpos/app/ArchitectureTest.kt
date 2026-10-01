package io.minimpos.app

import androidx.compose.runtime.internal.StabilityInferred
import androidx.lifecycle.ViewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.simulator.TerminalSimulator
import io.minimpos.terminal.transport.TerminalTransport
import org.junit.Test

class ArchitectureTest {
    @Test
    fun `layers only depend downwards`() =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(UI)
            .definedBy(
                "io.minimpos.app.feature..",
                "io.minimpos.app.ui..",
                "io.minimpos.app.scan..",
                "io.minimpos.app.qr..",
            ).layer(PAYMENT)
            .definedBy("io.minimpos.app.payment..")
            .layer(EMAIL)
            .definedBy("io.minimpos.app.email..")
            .layer(RECEIPT)
            .definedBy("io.minimpos.app.receipt..")
            .layer(REFUND)
            .definedBy("io.minimpos.app.refund..")
            .layer(TERMINAL)
            .definedBy("io.minimpos.app.terminal..")
            .layer(DATA)
            .definedBy("io.minimpos.app.data..")
            .whereLayer(UI)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(PAYMENT)
            .mayOnlyBeAccessedByLayers(UI)
            .whereLayer(EMAIL)
            .mayOnlyBeAccessedByLayers(PAYMENT, UI)
            .whereLayer(RECEIPT)
            .mayOnlyBeAccessedByLayers(EMAIL, PAYMENT, UI)
            .whereLayer(REFUND)
            .mayOnlyBeAccessedByLayers(RECEIPT, PAYMENT, UI)
            .whereLayer(TERMINAL)
            .mayOnlyBeAccessedByLayers(RECEIPT, PAYMENT, UI)
            .whereLayer(DATA)
            .mayOnlyBeAccessedByLayers(TERMINAL, REFUND, RECEIPT, EMAIL, PAYMENT, UI)
            .check(app)

    @Test
    fun `only the UI reaches into the dependency container`() =
        noClasses()
            .that()
            .resideOutsideOfPackages("io.minimpos.app", "io.minimpos.app.feature..", "io.minimpos.app.ui..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching("io\\.minimpos\\.app\\.(AppContainer|MainActivity|MiniMposApplication)\\b.*")
            .check(app)

    @Test
    fun `features and data packages have no dependency cycles`() {
        slices()
            .matching("io.minimpos.app.feature.(*)..")
            .should()
            .beFreeOfCycles()
            .check(app)
        slices()
            .matching("io.minimpos.app.data.(*)..")
            .should()
            .beFreeOfCycles()
            .check(app)
    }

    @Test
    fun `business logic stays free of Compose`() =
        noClasses()
            .that()
            .resideInAnyPackage(*BUSINESS_PACKAGES)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("androidx.compose..", "androidx.activity..", "androidx.navigation3..")
                    // The Compose compiler adds this annotation to every class in the module.
                    .and(not(type(StabilityInferred::class.java))),
            ).check(app)

    @Test
    fun `view models live in feature packages and hold no Android UI types`() {
        classes()
            .that()
            .areAssignableTo(ViewModel::class.java)
            .should()
            .resideInAPackage("io.minimpos.app.feature..")
            .andShould()
            .haveSimpleNameEndingWith("ViewModel")
            .check(app)
        noClasses()
            .that()
            .areAssignableTo(ViewModel::class.java)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("android.content..", "android.view..", "android.widget..", "androidx.compose.ui..")
            .check(app)
    }

    @Test
    fun `Room stays inside the data layer`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.data..")
            // The container opens the database and hands its DAOs to the repositories.
            .and()
            .haveNameNotMatching("io\\.minimpos\\.app\\.AppContainer\\b.*")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(Dao::class.java)
            .orShould()
            .dependOnClassesThat()
            .areAnnotatedWith(Database::class.java)
            .check(app)
        classes()
            .that()
            .areAnnotatedWith(Entity::class.java)
            .or()
            .areAnnotatedWith(Dao::class.java)
            .should()
            .resideInAPackage("io.minimpos.app.data.db..")
            .check(app)
    }

    @Test
    fun `secrets are encrypted in one place`() =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.data.security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("javax.crypto..", "android.security.keystore..")
            .check(app)

    @Test
    fun `the Adyen library stays behind terminal-api`() =
        // terminal-api's interface has its own print model and enums, so nexo knowledge lives in one module.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.adyen..")
            .check(app)

    @Test
    fun `only the gateway talks to the terminal`() =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.terminal..")
            // The container passes the gateway its way of connecting to a real terminal.
            .and()
            .haveNameNotMatching("io\\.minimpos\\.app\\.AppContainer\\b.*")
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(TerminalTransport::class.java, TerminalSimulator::class.java)
            .orShould()
            .callConstructorWhere(target(owner(assignableTo(TerminalClient::class.java))))
            .check(app)

    @Test
    fun `the app never logs`() {
        // Logs on a payment terminal can leak shared keys, SMTP passwords and card data.
        noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("android.util.Log")
            .check(app)
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(app)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(app)
    }

    private companion object {
        const val UI = "UI"
        const val PAYMENT = "Payment"
        const val EMAIL = "Email"
        const val RECEIPT = "Receipt"
        const val REFUND = "Refund"
        const val TERMINAL = "Terminal"
        const val DATA = "Data"

        val BUSINESS_PACKAGES =
            arrayOf(
                "io.minimpos.app.data..",
                "io.minimpos.app.terminal..",
                "io.minimpos.app.receipt..",
                "io.minimpos.app.refund..",
                "io.minimpos.app.email..",
                "io.minimpos.app.payment..",
            )

        // Android unit tests compile to build/intermediates/…/debugUnitTest/…, which DO_NOT_INCLUDE_TESTS does not know.
        val app: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("io.minimpos.app")
    }
}

package app.minimpos.app

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaConstructor
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaFieldAccess.AccessType
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmProperty
import kotlin.metadata.MemberKind
import kotlin.metadata.Visibility
import kotlin.metadata.isConst
import kotlin.metadata.isData
import kotlin.metadata.isInline
import kotlin.metadata.isSecondary
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility

/**
 * Production code that production never uses is dead, even when tests call it: it still has to be read, kept compiling
 * and tested. detekt finds unused private declarations within a file, the Kotlin compiler unused variables and Android
 * Lint unused resources. This finds the rest in every production module (:core, :adyen, :app and :tooling) from their
 * bytecode: classes, constructors, functions and properties no other production code refers to. Delete what it
 * reports, or move it to the tests that use it; deleting one finding can reveal the code only it used.
 */
class DeadCodeTest {
    @Test
    fun `production uses every declaration`() {
        val roots =
            checkNotNull(System.getProperty("minimpos.productionSources")) { "minimpos.productionSources is set by Gradle" }
                .split(File.pathSeparator)
                .map(::File)
        val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }
        val files = sources.map { "${packageOf(it)}/${it.name}" }.toSet()
        val dead =
            DeadCode(
                classes =
                    ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .withImportOption { location -> !location.contains("UnitTest") }
                        .importPackages("app.minimpos"),
                // Generated sources (Room, R, BuildConfig) are not in src/main.
                declared = { type ->
                    type.source
                        .flatMap { it.fileName }
                        .map { "${type.packageName}/$it" in files }
                        .orElse(false)
                },
                entryPoints = manifestClasses(roots.map { File(it, "AndroidManifest.xml") }.filter(File::exists)),
                sourceLines = sources.flatMap(File::readLines),
            ).findings()
        assertWithMessage("Production never uses these; delete them or move them to the tests that use them")
            .that(dead)
            .isEmpty()
    }

    @Test
    fun `unused classes, constructors, functions and properties are found`() {
        val fixtures =
            listOf(
                FixtureLive::class.java,
                FixturePair::class.java,
                FixtureLimits::class.java,
                FixtureUnused::class.java,
                FixtureCaller::class.java,
            )
        val dead =
            DeadCode(
                classes = ClassFileImporter().importClasses(fixtures),
                declared = { type -> fixtures.any { type.name == it.name } },
                entryPoints = emptySet(),
                sourceLines = listOf("const val USED_LIMIT = 1", "const val UNUSED_LIMIT = 2", "FixtureLimits.USED_LIMIT"),
            ).findings()
        val prefix = "${DeadCodeTest::class.java.name}$"
        assertThat(dead.map { "${it.kind} ${it.name.removePrefix(prefix)}" }).containsExactly(
            "class FixtureCaller",
            "class FixtureUnused",
            "function FixtureLive.unused",
            "property FixtureLive.unread",
            "constructor FixturePair",
            "property FixturePair.second",
            "property FixtureLimits.UNUSED_LIMIT",
        )
    }

    // Read and called by FixtureCaller, overrides a library method, or never used (the "un" names).
    private class FixtureLive(
        val read: Int,
        val unread: Int,
    ) : Runnable {
        fun used() = read + 1

        fun unused() = toString()

        override fun run() {
            used()
        }
    }

    // Destructured for its first value only, and never constructed.
    private data class FixturePair(
        val first: Int,
        val second: Int,
    )

    // Constants are copied into their callers, so only their names show their use.
    private object FixtureLimits {
        const val USED_LIMIT = 1
        const val UNUSED_LIMIT = 2
    }

    private class FixtureUnused

    // Nothing refers to the caller itself.
    private class FixtureCaller {
        fun call(pair: FixturePair): Int {
            val live = FixtureLive(1, 2)
            val (first, _) = pair
            return live.used() + live.read + first + FixtureLimits.USED_LIMIT
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

        /** The package [file] declares, or "" for the default package. */
        fun packageOf(file: File) =
            PACKAGE
                .find(file.readText())
                ?.groupValues
                ?.get(1)
                .orEmpty()

        /** The fully qualified class names `android:name` attributes in [manifests] refer to. */
        fun manifestClasses(manifests: List<File>): Set<String> {
            val namespace = BuildConfig::class.java.name.substringBeforeLast('.')
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            return manifests
                .flatMap { manifest ->
                    val elements = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("*")
                    (0..<elements.length).mapNotNull { index ->
                        elements
                            .item(index)
                            .attributes
                            .getNamedItemNS(ANDROID, "name")
                            ?.nodeValue
                    }
                }.map { if (it.startsWith('.')) "$namespace$it" else it }
                .toSet()
        }
    }
}

/** A declaration production never uses: its [kind] (class, constructor, function or property), [name] and [location]. */
internal data class DeadDeclaration(
    val kind: String,
    val name: String,
    val location: String,
) {
    override fun toString() = "$kind $name $location"
}

/**
 * Finds the declarations in the [declared] [classes] (those compiled from hand-written Kotlin sources) that no other code
 * in [classes] uses. Their Kotlin metadata says what the source declares, so members the compiler generates (data
 * class `copy`, enum `values`, serializers) are never reported. Code the system runs is used: [entryPoints] (the classes
 * the manifest names), `main` methods and overrides of library methods. Constants and inline functions leave no
 * reference in bytecode, as the compiler copies them into their callers; they are used when their name appears in
 * [sourceLines] outside their declaration and comments. Private declarations are detekt's.
 *
 * Use by unused code counts, so a chain of dead code is found one link at a time.
 */
internal class DeadCode(
    classes: Iterable<JavaClass>,
    private val declared: (JavaClass) -> Boolean,
    private val entryPoints: Set<String>,
    sourceLines: List<String>,
) {
    private val imported = classes.toSet()
    private val own = imported.filter(declared).sortedBy { it.name }
    private val metadata = HashMap<JavaClass, KotlinClassMetadata?>()
    private val reachedUnits = HashMap<JavaCodeUnit, Boolean>()
    private val names =
        sourceLines
            .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .flatMap { line -> WORD.findAll(line).map { it.value } }
            .groupingBy { it }
            .eachCount()

    private val Visibility.checked get() = this == Visibility.PUBLIC || this == Visibility.INTERNAL || this == Visibility.PROTECTED

    private val JavaCodeUnit.location get() = sourceCodeLocation.toString()

    /** Every unused declaration, by class name; an unused class's members are not listed separately. */
    fun findings(): List<DeadDeclaration> =
        own.flatMap { type ->
            when (val kotlin = metadataOf(type)) {
                is KotlinClassMetadata.Class -> classFindings(type, kotlin.kmClass)
                is KotlinClassMetadata.FileFacade -> members(type, kotlin.kmPackage, null).unused()
                is KotlinClassMetadata.MultiFileClassPart -> members(type, kotlin.kmPackage, null).unused()
                else -> emptyList()
            }
        }

    private fun classFindings(
        type: JavaClass,
        km: KmClass,
    ): List<DeadDeclaration> {
        if (km.kind == ClassKind.ENUM_ENTRY || km.kind == ClassKind.ANNOTATION_CLASS) return emptyList()
        val members = members(type, km, km)
        // Constants and inline functions are copied into their callers, which then need not refer to the class.
        val usedByName = members.any { it.used && it.byName }
        if (km.kind != ClassKind.COMPANION_OBJECT && !referenced(type) && !usedByName) {
            return listOf(DeadDeclaration("class", type.name, type.sourceCodeLocation.toString()))
        }
        // Deserializing creates an instance through the serialization plugin's constructor, which stands for the primary one.
        val deserialized = type.constructors.any { it.rawParameterTypes.lastOrNull()?.name == SERIALIZATION_MARKER && reached(it) }
        val constructors =
            km.constructors
                .filter { it.visibility.checked && !(deserialized && !it.isSecondary) }
                .map { codeUnit(type, checkNotNull(it.signature)) }
                .filterNot(::reached)
                .map { DeadDeclaration("constructor", type.name, it.location) }
        return constructors + members.unused()
    }

    /** A function or property: [finding] if it is unused, whether it is [used], and whether only its name shows it ([byName]). */
    private class Member(
        val finding: DeadDeclaration,
        val used: Boolean,
        val byName: Boolean,
    )

    private fun List<Member>.unused() = filterNot { it.used }.map { it.finding }

    /** The functions and properties [container] (in [type], a class described by [km] or a file) declares. */
    private fun members(
        type: JavaClass,
        container: KmDeclarationContainer,
        km: KmClass?,
    ): List<Member> {
        // The serialization plugin adds serializer() to @Serializable classes and their companions.
        val serializable = listOfNotNull(type, type.enclosingClass.orElse(null)).any { it.isAnnotatedWith(SERIALIZABLE) }
        val functions =
            container.functions
                .filter { it.kind == MemberKind.DECLARATION && it.visibility.checked && !(serializable && it.name == "serializer") }
                .map { function ->
                    val method = codeUnit(type, checkNotNull(function.signature))
                    val used = if (function.isInline) usedByName(function.name) else reached(method)
                    Member(DeadDeclaration("function", "${type.name}.${function.name}", method.location), used, function.isInline)
                }
        val properties =
            container.properties
                .filter { it.kind == MemberKind.DECLARATION && it.visibility.checked && it.name != km?.inlineClassUnderlyingPropertyName }
                .map { property ->
                    val getter = property.getterSignature?.let { codeUnit(type, it) }
                    val used = if (property.isConst) usedByName(property.name) else read(type, property, getter, km)
                    val location = getter?.location ?: type.sourceCodeLocation.toString()
                    Member(DeadDeclaration("property", "${type.name}.${property.name}", location), used, property.isConst)
                }
        return functions + properties
    }

    /** Whether code other than [property]'s own accessors and a data class's generated members reads it. */
    private fun read(
        type: JavaClass,
        property: KmProperty,
        getter: JavaCodeUnit?,
        km: KmClass?,
    ): Boolean {
        if (getter != null && reached(getter)) return true
        val accessors = setOfNotNull(getter, property.setterSignature?.let { codeUnit(type, it) })
        val field = property.fieldSignature?.let { signature -> field(type, signature.name) } ?: return false
        return field.accessesToSelf.any { access ->
            val origin = access.origin
            access.accessType == AccessType.GET &&
                origin !in accessors &&
                when {
                    km?.isData != true -> live(origin)
                    origin.name.startsWith("component") -> reached(origin)
                    else -> origin.name.substringBefore('$') !in DATA_MEMBERS && live(origin)
                }
        }
    }

    /** Whether code outside [type] and its nested classes refers to it, a used class extends it, or the system runs it. */
    private fun referenced(type: JavaClass): Boolean =
        type.name in entryPoints ||
            type.methods.any { it.isMain() } ||
            type.directDependenciesToSelf.any { !it.originClass.isWithin(type) } ||
            type.subclasses.any { it.isWithin(type) && declared(it) && referenced(it) }

    private fun JavaClass.isWithin(type: JavaClass) = this == type || name.startsWith("${type.name}$")

    /**
     * Whether [unit] is used: live code calls or refers to it, or to a method it overrides or that overrides it (a call
     * through a supertype runs every override, and they and what they override can only be removed together), or one of
     * those is a library's, which calls it, or it is an entry point.
     */
    private fun reached(unit: JavaCodeUnit): Boolean {
        reachedUnits[unit]?.let { return it }
        val related = if (unit is JavaMethod) family(unit) else setOf(unit)
        // A cycle of calls alone does not make its members used.
        related.forEach { reachedUnits[it] = false }
        val reached =
            related.any { member -> member.owner !in imported || member.accessesToSelf.any { it.origin != member && live(it.origin) } } ||
                (unit is JavaConstructor && unit.owner.name in entryPoints) ||
                unit.isMain()
        related.forEach { reachedUnits[it] = reached }
        return reached
    }

    /**
     * Whether an access from [origin] counts: always, unless it is generated code that only forwards to the declaration
     * (default-argument and no-argument overloads, bridges, a data class's `copy`), which counts only when used itself.
     */
    private fun live(origin: JavaCodeUnit): Boolean = !forwarder(origin) || reached(origin)

    private fun forwarder(unit: JavaCodeUnit): Boolean =
        JavaModifier.BRIDGE in unit.modifiers ||
            (JavaModifier.SYNTHETIC in unit.modifiers && unit.name.endsWith("\$default")) ||
            (unit.name == "copy" && kotlinClass(unit.owner)?.isData == true) ||
            (unit is JavaConstructor && unit.rawParameterTypes.lastOrNull()?.name == DEFAULT_CONSTRUCTOR_MARKER) ||
            (
                unit is JavaConstructor &&
                    unit.rawParameterTypes.isEmpty() &&
                    kotlinClass(unit.owner)?.constructors?.none { it.signature?.descriptor == unit.descriptor } == true
            )

    private fun kotlinClass(type: JavaClass): KmClass? = (metadataOf(type) as? KotlinClassMetadata.Class)?.kmClass

    /** [method] and the methods it overrides or that override it, transitively. */
    private fun family(method: JavaMethod): Set<JavaMethod> {
        val found = mutableSetOf(method)
        val pending = ArrayDeque(listOf(method))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            // A library method is enough to make the family used; its other overrides need not be listed.
            if (current.owner !in imported || JavaModifier.STATIC in current.modifiers ||
                JavaModifier.PRIVATE in current.modifiers
            ) {
                continue
            }
            val types = current.owner.allRawSuperclasses + current.owner.allRawInterfaces + current.owner.allSubclasses
            types
                .flatMap { it.methods }
                .filter { it.overrides(current) && found.add(it) }
                .forEach(pending::addLast)
        }
        return found
    }

    private fun JavaMethod.overrides(other: JavaMethod) =
        name == other.name &&
            JavaModifier.STATIC !in modifiers &&
            JavaModifier.PRIVATE !in modifiers &&
            rawParameterTypes.map { it.name } == other.rawParameterTypes.map { it.name }

    private fun JavaCodeUnit.isMain() =
        name == "main" &&
            JavaModifier.STATIC in modifiers &&
            rawParameterTypes.singleOrNull()?.let { it.isArray && it.componentType.name == "java.lang.String" } == true

    private fun usedByName(name: String) = (names[name] ?: 0) > 1

    private fun codeUnit(
        type: JavaClass,
        signature: JvmMethodSignature,
    ): JavaCodeUnit =
        checkNotNull(type.codeUnits.firstOrNull { it.name == signature.name && it.descriptor == signature.descriptor }) {
            "${type.name} has no $signature, which its Kotlin metadata declares"
        }

    // A companion object's properties keep their fields in the class around it.
    private fun field(
        type: JavaClass,
        name: String,
    ): JavaField? =
        type.fields.firstOrNull { it.name == name }
            ?: type.enclosingClass
                .orElse(null)
                ?.fields
                ?.firstOrNull { it.name == name }

    private fun metadataOf(type: JavaClass): KotlinClassMetadata? =
        metadata.getOrPut(type) {
            type.tryGetAnnotationOfType("kotlin.Metadata").orElse(null)?.properties?.let { values ->
                fun strings(name: String) = (values[name] as Array<*>?)?.map { checkNotNull(it) as String }?.toTypedArray()
                KotlinClassMetadata.readStrict(
                    Metadata(
                        kind = values["k"] as Int?,
                        metadataVersion = values["mv"] as IntArray?,
                        data1 = strings("d1"),
                        data2 = strings("d2"),
                        extraString = values["xs"] as String?,
                        packageName = values["pn"] as String?,
                        extraInt = values["xi"] as Int?,
                    ),
                )
            }
        }

    private companion object {
        val WORD = Regex("""\b[A-Za-z_]\w*\b""")
        const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"
        const val SERIALIZABLE = "kotlinx.serialization.Serializable"
        const val SERIALIZATION_MARKER = "kotlinx.serialization.internal.SerializationConstructorMarker"

        /** The members the compiler generates for a data class, which read every property. */
        val DATA_MEMBERS = setOf("equals", "hashCode", "toString", "copy")
    }
}

package io.minimpos.app

import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * :core and :terminal-api run on the terminals too, but they are JVM modules, so Android Lint never checks which
 * Android versions have the Java APIs they reach (such as `URLEncoder.encode(String, Charset)`, which needs API 33).
 * This checks them as Lint would at the app's minimum SDK: against the compile SDK's API database (`api-versions.xml`),
 * accepting what D8 backports; the build passes all three in (see `AndroidApiArguments` in `app/build.gradle.kts`).
 */
class AndroidApiLevelTest {
    @Test
    fun `the JVM modules reach only APIs the oldest supported Android has`() {
        fun property(name: String) = checkNotNull(System.getProperty("minimpos.$name")) { "minimpos.$name is set by Gradle" }
        val apis = AndroidApis.load(File(property("apiDatabase")), File(property("backportedMethods")))
        val minSdk = property("minSdk").toInt()
        classes()
            .should(reachOnlyApisAt(apis, minSdk))
            .check(
                ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("io.minimpos.core", "io.minimpos.terminal"),
            )
    }

    private fun reachOnlyApisAt(
        apis: AndroidApis,
        minSdk: Int,
    ) = object : ArchCondition<JavaClass>("reach only classes, methods and fields that Android $minSdk has") {
        override fun check(
            item: JavaClass,
            events: ConditionEvents,
        ) {
            item.directDependenciesFromSelf
                .map { it.targetClass.baseComponentType }
                .distinct()
                .forEach { target ->
                    apis.missing(target, minSdk)?.let { events.add(SimpleConditionEvent.violated(item, "${item.name} uses $it")) }
                }
            item.accessesFromSelf.forEach { access ->
                apis.missing(access, minSdk)?.let { events.add(SimpleConditionEvent.violated(access, "${access.description}: $it")) }
            }
        }
    }
}

/**
 * Android's API database: per class (`java/net/URLEncoder`, `java/util/Map$Entry`), the API level it was added in, its
 * supertypes, and the API level each member was added in, keyed by field name or method name and descriptor; and the
 * methods D8 backports, which every API level has (`java/math/BigInteger#longValueExact()J`).
 */
private class AndroidApis(
    private val classes: Map<String, ApiClass>,
    private val backported: Set<String>,
) {
    private val packages = classes.keys.map { it.substringBeforeLast('/') }.toSet()

    /** Why Android [minSdk] lacks the class [type], or null when it has it or it is not a platform class at all. */
    fun missing(
        type: JavaClass,
        minSdk: Int,
    ): String? {
        val name = type.apiName
        val api = classes[name] ?: return "$name, which Android does not have".takeIf { name.substringBeforeLast('/') in packages }
        return "$name, added in API ${api.since}".takeIf { api.since > minSdk }
    }

    /**
     * Why Android [minSdk] lacks the member [access] reaches, or null when it has it. A member of a class outside the
     * database (the modules' own, or a library's) is checked where it is inherited from a platform class, if it is.
     */
    fun missing(
        access: JavaAccess<*>,
        minSdk: Int,
    ): String? {
        val owner = access.targetOwner
        if (owner.isArray) return null
        val member =
            when (access) {
                is JavaCodeUnitAccess<*> -> {
                    val target = access.target
                    "${target.name}(${target.rawParameterTypes.joinToString("") { it.descriptor() }})${target.rawReturnType.descriptor()}"
                }

                else -> {
                    access.target.name
                }
            }
        val platformOwner = owner.apiName in classes
        val declaring =
            if (platformOwner) {
                sequenceOf(owner.apiName, OBJECT)
            } else {
                (owner.classHierarchy.asSequence() + owner.allRawInterfaces.asSequence()).map { it.apiName }.filter { it in classes }
            }
        if (declaring.any { "$it#$member" in backported }) return null
        val since = declaring.firstNotNullOfOrNull { since(it, member, mutableSetOf()) }
        return when {
            since == null && platformOwner -> "${owner.apiName}.$member, which Android does not have"
            since != null && since > minSdk -> "${owner.apiName}.$member, added in API $since"
            else -> null
        }
    }

    private fun since(
        owner: String,
        member: String,
        seen: MutableSet<String>,
    ): Int? {
        val api = classes[owner]?.takeIf { seen.add(owner) } ?: return null
        return api.members[member] ?: api.supertypes.firstNotNullOfOrNull { since(it, member, seen) }
    }

    companion object {
        private const val OBJECT = "java/lang/Object"

        /** Reads [file], an SDK platform's `data/api-versions.xml`, and [backports], D8's `BackportedMethodList`. */
        fun load(
            file: File,
            backports: File,
        ): AndroidApis {
            val classes = mutableMapOf<String, ApiClass>()
            var name: String? = null
            var since = 1
            val supertypes = mutableListOf<String>()
            val members = mutableMapOf<String, Int>()
            val handler =
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String,
                        localName: String,
                        qName: String,
                        attributes: Attributes,
                    ) {
                        when (qName) {
                            "class" -> {
                                name = attributes.getValue("name")
                                since = level(attributes) ?: 1
                                supertypes.clear()
                                members.clear()
                            }

                            "extends", "implements" -> {
                                supertypes += attributes.getValue("name")
                            }

                            "method", "field" -> {
                                members[attributes.getValue("name")] = level(attributes) ?: since
                            }
                        }
                    }

                    override fun endElement(
                        uri: String,
                        localName: String,
                        qName: String,
                    ) {
                        if (qName == "class") classes[checkNotNull(name)] = ApiClass(since, supertypes.toList(), members.toMap())
                    }
                }
            SAXParserFactory.newInstance().newSAXParser().parse(file, handler)
            return AndroidApis(classes, backports.readLines().filter { it.isNotBlank() }.toSet())
        }

        /** The `since` API level (such as `33`, or `37.0` for a minor release), or null when the element has none. */
        fun level(attributes: Attributes): Int? = attributes.getValue("since")?.substringBefore('.')?.toInt()

        /** The name the API database knows the class by, such as `java/util/Map$Entry`. */
        val JavaClass.apiName: String get() = name.replace('.', '/')

        /** The descriptors of the primitive types (and `void`), by name. */
        val PRIMITIVE_DESCRIPTORS =
            mapOf(
                "boolean" to "Z",
                "byte" to "B",
                "char" to "C",
                "short" to "S",
                "int" to "I",
                "long" to "J",
                "float" to "F",
                "double" to "D",
                "void" to "V",
            )

        /** The JVM type descriptor, such as `I`, `[B` or `Ljava/lang/String;`. */
        fun JavaClass.descriptor(): String =
            when {
                isArray -> "[${componentType.descriptor()}"
                isPrimitive -> PRIMITIVE_DESCRIPTORS.getValue(name)
                else -> "L$apiName;"
            }
    }
}

/** A class in [AndroidApis]: the API level it was added in, its supertypes, and the API level each member was added in. */
private class ApiClass(
    val since: Int,
    val supertypes: List<String>,
    val members: Map<String, Int>,
)

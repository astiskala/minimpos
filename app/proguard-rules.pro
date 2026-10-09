# JavaMail (android-mail) loads providers reflectively from META-INF resources.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }

# Adyen Java API library: Gson (de)serialises the Terminal API models by reflection (field names, @SerializedName,
# enum fromValue lookups and @JsonAdapter classes). Other APIs use Jackson; the library ships no consumer rules.
-keep class com.adyen.model.nexo.** { *; }
-keep class com.adyen.model.terminal.** { *; }
-keep class com.adyen.model.applicationinfo.** { *; }
-keep class com.adyen.terminal.serialization.** { *; }
-keep class com.adyen.serializer.SaleToAcquirerDataSerializer { *; }
-keepclassmembers class com.adyen.model.checkout.**,
    com.adyen.model.management.**,com.adyen.model.paymentsapp.**,com.adyen.model.clouddevice.**,com.adyen.model.ApiError {
    <fields>;
    public <init>();
    @com.fasterxml.jackson.annotation.* <methods>;
}
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep,allowoptimization,allowobfuscation interface app.minimpos.terminal.transport.NexoSettings
-keep @interface com.fasterxml.jackson.annotation.JsonIncludeProperties { *; }
# The library's default Apache HttpClient is excluded (it does not run on Android); TerminalHttpClient replaces it.
-dontwarn org.apache.hc.**

# javax.xml.datatype.DatatypeFactory.newInstance() loads Xerces' implementation by class name.
-keep class org.apache.xerces.jaxp.datatype.** { *; }
# Xerces' META-INF/services also name an API Android does not have (StAX) and two system property names that are not
# classes. R8 reads them before the packaging excludes in build.gradle.kts drop them from the APK.
-dontwarn javax.xml.stream.XMLEventFactory
-dontwarn org.w3c.dom.DOMImplementationSourceList
-dontwarn org.xml.sax.driver

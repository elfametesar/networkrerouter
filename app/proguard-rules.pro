# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class net.ip.rerouter.model.** {
    *** Companion;
}
-keep,includedescriptorclasses class net.ip.rerouter.model.**$$serializer { *; }
-keepclassmembers class net.ip.rerouter.model.** {
    *** INSTANCE;
}

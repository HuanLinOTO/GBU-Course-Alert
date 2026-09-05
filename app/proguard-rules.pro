-keepattributes *Annotation*, InnerClasses, Signature

-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class me.huanlin.gbuca.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class me.huanlin.gbuca.**$$serializer {
    *;
}

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

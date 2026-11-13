# Readable release stack traces
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# JNI / reflection
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.github.luben.zstd.** { *; }
-keep class com.topjohnwu.superuser.** { *; }

# JGit uses reflection + ServiceLoader
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**

# ServiceLoader files + Compose/coroutines are handled by AGP defaults
-keep class org.slf4j.** { *; }
-keep class org.apache.commons.compress.** { *; }

# Optional commons-compress backends that are not bundled
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**

-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.googlecode.javaewah.**

# ARSCLib / APKEditor (bundled jar)
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**
-dontwarn org.jf.**
-dontwarn org.antlr.**
-dontwarn com.beust.**
-dontwarn org.apache.commons.collections4.**
-dontwarn com.android.apksig.**

# apksig: ASN.1 encoder uses reflection on fields and annotations
-keep class com.android.apksig.** { *; }

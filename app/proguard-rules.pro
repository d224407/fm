-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

-keepclasseswithmembernames class * { native <methods>; }
-keep class com.github.luben.zstd.** { *; }
-keep class com.topjohnwu.superuser.** { *; }

-keep class org.eclipse.jgit.** { *; }
-keep class org.slf4j.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class net.lingala.zip4j.** { *; }
-keep class com.reandroid.** { *; }

# apksig reads ASN.1 fields/annotations by reflection; without this signing fails
-keep class com.android.apksig.** { *; }

-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.googlecode.javaewah.**
-dontwarn com.reandroid.**
-dontwarn org.jf.**
-dontwarn org.antlr.**
-dontwarn com.beust.**
-dontwarn org.apache.commons.collections4.**
-dontwarn com.android.apksig.**
-dontwarn net.lingala.zip4j.**

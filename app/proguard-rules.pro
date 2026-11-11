# SafeStitch R8 rules.
# Room, CameraX, Compose, Navigation and kotlinx.serialization ship their own consumer
# rules. Keep the type-safe navigation route classes, sync payload models, and Room entities intact.
-keep @kotlinx.serialization.Serializable class com.safestitch.app.** { *; }
-keepclassmembers class com.safestitch.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.safestitch.app.data.db.** { *; }
-keep enum com.safestitch.app.domain.** { *; }

# PDFBox-Android rules for release R8 builds
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**

# Keep line numbers in crash stack traces (Play Console de-obfuscates them with mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

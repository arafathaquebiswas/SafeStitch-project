# SafeStitch R8 rules.
# Room, CameraX, Compose, Navigation and kotlinx.serialization ship their own consumer
# rules. Keep the type-safe navigation route classes' serializers intact.
-keep @kotlinx.serialization.Serializable class com.safestitch.app.ui.navigation.** { *; }

# Keep line numbers in crash stack traces (Play Console de-obfuscates them with mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

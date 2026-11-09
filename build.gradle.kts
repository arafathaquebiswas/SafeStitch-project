// Top-level build file. Module configuration lives in app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    // Pins the Kotlin compiler used by AGP's built-in Kotlin support (not applied).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

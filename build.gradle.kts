// termagent — root build script
// NOTE: no "org.jetbrains.kotlin.android" — AGP 9 has built-in Kotlin support and rejects
// that plugin (see app/build.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Root build: plugins declared here, applied per module. AGP 9 built-in Kotlin: kotlin-android is declared
// (for the classpath) but never applied to Android modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.wire) apply false
}

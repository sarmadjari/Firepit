// Plugins are declared here (not applied) so they land on the build classpath
// for every module. The convention plugins in build-logic then apply them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
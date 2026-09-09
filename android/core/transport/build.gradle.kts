plugins {
    alias(libs.plugins.firepit.android.library)
}

android {
    namespace = "com.getfirepit.core.transport"
}

dependencies {
    api(projects.core.protocol)
    api(libs.kable.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

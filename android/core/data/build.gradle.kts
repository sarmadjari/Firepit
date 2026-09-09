plugins {
    alias(libs.plugins.firepit.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.getfirepit.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.database)
    api(projects.core.protocol)
    api(projects.core.transport)
    api(projects.core.crypto)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

plugins {
    alias(libs.plugins.firepit.android.library.compose)
}

android {
    namespace = "com.getfirepit.core.designsystem"
}

dependencies {
    // Pure data types; lets the tick mapping live in one place.
    api(projects.core.model)

    api(libs.androidx.compose.material3)
    api(libs.androidx.adaptive)
    api(libs.androidx.adaptive.layout)
    api(libs.androidx.adaptive.navigation)
    api(libs.androidx.material3.navigation.suite)
}

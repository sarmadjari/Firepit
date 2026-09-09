plugins {
    alias(libs.plugins.firepit.android.library.compose)
}

android {
    namespace = "com.getfirepit.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
}

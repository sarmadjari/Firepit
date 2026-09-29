plugins {
    alias(libs.plugins.firepit.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.getfirepit.core.database"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Exported schemas make migrations reviewable in diffs, and let the
    // migration test build each old version to upgrade from.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

dependencies {
    api(projects.core.model)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Messages, positions and names are encrypted at rest, not only the keys.
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The cascade that keeps receipts from outliving messages is a promise made
    // by SQLite, so it is checked against SQLite rather than a fake.
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

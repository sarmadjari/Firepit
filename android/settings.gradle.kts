pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Firepit"

include(":app")

// Pure Kotlin — no Android dependency, so the riskiest logic (packet parsing,
// ACK state machine, slot manager) runs in fast JVM tests.
include(":core:model")
include(":core:protocol")

// Android libraries.
include(":core:designsystem")
include(":core:transport")
include(":core:database")
include(":core:data")

// Modules are added as their stage arrives rather than pre-created empty:
// :core:crypto :core:adaptive :core:service :core:testing :feature:*

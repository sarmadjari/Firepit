import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure Kotlin/JVM module: no Android dependency, so it runs in fast unit tests.
 * Used for :core:model and :core:protocol, where the packet parsing, ACK state
 * machine and slot manager live.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        configureJavaToolchain()
        configureKotlinJvmTarget(version("javaTarget"))

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}

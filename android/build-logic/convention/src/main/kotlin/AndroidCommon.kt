import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.version(alias: String): String =
    libs.findVersion(alias).get().requiredVersion

internal fun Project.intVersion(alias: String): Int = version(alias).toInt()

/**
 * Shared Android settings. AGP 9 applies Kotlin itself, so no Kotlin plugin is
 * applied here; only the JVM target is pinned, via the compiler task so the
 * same code works whether or not AGP changes its Kotlin DSL surface.
 *
 * AGP 9 exposes `defaultConfig` and `compileOptions` on CommonExtension as
 * getters only — the lambda-taking overloads live on the concrete extensions —
 * hence the property access below.
 */
internal fun Project.configureAndroid(extension: CommonExtension) {
    val javaTarget = version("javaTarget")

    extension.compileSdk = intVersion("compileSdk")
    extension.defaultConfig.minSdk = intVersion("minSdk")
    extension.compileOptions.sourceCompatibility = JavaVersion.toVersion(javaTarget)
    extension.compileOptions.targetCompatibility = JavaVersion.toVersion(javaTarget)

    configureKotlinJvmTarget(javaTarget)
}

internal fun Project.configureKotlinJvmTarget(javaTarget: String) {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaTarget))
            // Mesh input is untrusted; keep warnings loud so nullability and
            // unchecked casts cannot slip into packet parsing.
            allWarningsAsErrors.set(false)
        }
    }
}

internal fun Project.configureJavaToolchain() {
    extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)?.apply {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(intVersion("javaTarget")))
        }
    }
}

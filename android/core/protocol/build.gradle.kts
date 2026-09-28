plugins {
    alias(libs.plugins.firepit.jvm.library)
    alias(libs.plugins.wire)
}

// The vendored .proto tree lives outside the Gradle root so the iOS app
// generates from the exact same files. See protos/UPSTREAM.md.
val protosDir = rootProject.layout.projectDirectory.dir("../protos")

wire {
    kotlin {
        javaInterop = false
    }
    sourcePath {
        srcDir(protosDir)
        // Explicit: the directory also holds .options, .md and the key file,
        // and an include list here replaces Wire's default "**/*.proto".
        // nanopb.proto is deliberately absent — see protoPath below.
        include("meshtastic/*.proto", "meshchat/*.proto")
    }
    protoPath {
        srcDir(protosDir)
        // Only carries firmware field-size annotations and extends
        // google.protobuf descriptors that Wire does not generate. Resolvable
        // so deviceonly.proto's import works, but never generated.
        include("nanopb.proto")
    }
}

kotlin {
    compilerOptions {
        // Wire's generated decoders assert non-null on packed lists that Kotlin
        // 2.4 can already prove non-null. Generated code cannot be edited, and
        // the assertion is a no-op, so this one diagnostic is silenced here.
        freeCompilerArgs.add("-Xwarning-level=UNNECESSARY_NOT_NULL_ASSERTION:disabled")
    }
}

dependencies {
    api(projects.core.model)
    api(libs.wire.runtime)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

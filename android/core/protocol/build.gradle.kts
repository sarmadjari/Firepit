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

dependencies {
    api(libs.wire.runtime)
}

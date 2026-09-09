plugins {
    alias(libs.plugins.firepit.jvm.library)
}

dependencies {
    // Invite payloads are protobuf, so the codec needs the generated types.
    api(projects.core.protocol)
}

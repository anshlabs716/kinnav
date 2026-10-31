plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The standalone Luogo relay. Serves the API the Android client speaks, using the same
// authorisation engine the client embeds.
dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    // Matches the JDK the project is built with; a toolchain requirement for 17 would
    // force a download on machines that only have 21.
    jvmToolchain(21)
}

application {
    mainClass.set("org.geonav.relay.RelayServerKt")
}
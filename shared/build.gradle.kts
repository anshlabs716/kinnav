plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Relay logic shared by the Android app and the standalone server.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

kotlin {
    // Matches the JDK the project is built with; a toolchain requirement for 17 would
    // force a download on machines that only have 21.
    jvmToolchain(21)
}
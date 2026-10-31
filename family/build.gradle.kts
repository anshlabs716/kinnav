plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Location sharing with contacts, ported from Luogo-FOSS.
//
// This module is deliberately self-contained: it knows nothing about GeoNav's map or
// routing. The app feeds it location fixes through [org.geonav.family.Family.init] hooks
// and hands it callbacks for "navigate here", so sharing can be compiled out of the UI
// story entirely and the app still works as a plain maps/navigation app.
android {
    namespace = "org.geonav.family"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    // Invite QR codes (ZXing, Apache-2.0)
    api(libs.zxing.core)

    testImplementation(libs.junit)
}

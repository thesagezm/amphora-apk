plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.personal.amphora"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.personal.amphora"
        minSdk = 26
        targetSdk = 36
        // AGENTS.md versioning rule: never bump.
        versionCode = 1
        versionName = "0.1.0"
    }

    // APK names per AGENTS.md: Amphora-debug.apk / Amphora-release-unsigned.apk.
    base {
        archivesName = "Amphora"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Debug-key signing so release builds are installable on the
            // user's phone without a release keystore (same choice as the
            // user's speechnotes-android). Minify stays off until the port
            // is complete; R8 can be revisited in A9.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Lint-vital resolves jars that are not cached on this workstation;
        // the build gates are :core tests + on-device verification.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}

// The M3 Expressive BOM (2026.09.00) resolves Compose 1.12.x, which
// requires compileSdk 37 and AGP 9.1. Pin the compose UI/foundation
// families back to the 1.9.x line the AGP 8.9.1 toolchain supports.
// Block copied verbatim (proven working) from the user's
// speechnotes-android, including their material3/icons rationale.
configurations.all {
    resolutionStrategy {
        force("androidx.compose.ui:ui-android:1.9.1")
        force("androidx.compose.ui:ui:1.9.1")
        force("androidx.compose.ui:ui-graphics-android:1.9.1")
        force("androidx.compose.ui:ui-text-android:1.9.1")
        force("androidx.compose.ui:ui-unit-android:1.9.1")
        force("androidx.compose.ui:ui-tooling-preview-android:1.9.1")
        force("androidx.compose.ui:ui-tooling-android:1.9.1")
        force("androidx.compose.ui:ui-tooling-data-android:1.9.1")
        force("androidx.compose.foundation:foundation-android:1.9.1")
        force("androidx.compose.foundation:foundation-layout-android:1.9.1")
        force("androidx.compose.runtime:runtime-android:1.9.1")
        force("androidx.compose.runtime:runtime-saveable-android:1.9.1")
        force("androidx.compose.animation:animation-android:1.9.1")
        force("androidx.compose.animation:animation-core-android:1.9.1")
        force("androidx.compose.material:material-ripple-android:1.9.1")
        force("androidx.compose.material:material-android:1.9.1")
        force("androidx.compose.material:material:1.9.1")
        // material-icons-core/extended stay at the BOM's own pin (1.7.8)
        // — the icons artifact family is versioned independently from
        // the compose UI line and 1.9.1 does not exist for it.
        force("androidx.compose.material:material-icons-core:1.7.8")
        force("androidx.compose.material:material-icons-core-android:1.7.8")
        force("androidx.compose.material:material-icons-extended:1.7.8")
        force("androidx.compose.material:material-icons-extended-android:1.7.8")
        // material3 pinned to 1.3.2 to match the 1.9.x ui/runtime line:
        // the BOM's 1.4.0 is compiled against the 1.12 runtime.
        force("androidx.compose.material3:material3:1.3.2")
        force("androidx.compose.material3:material3-android:1.3.2")
    }
}

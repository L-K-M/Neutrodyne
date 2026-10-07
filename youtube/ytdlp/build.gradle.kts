// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.android.library)
    id("com.chaquo.python")
}

android {
    buildFeatures { aidl = true } // IYtxEngine, IYtxCallback (04 Binder API)
    defaultConfig {
        // Chaquopy publishes no CPython >= 3.12 runtime for 32-bit ABIs (D77; the armeabi-v7a
        // APK of :app carries no engine and runs YouTube in external mode).
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

chaquopy {
    defaultConfig {
        version = "3.14" // fallback "3.13" (01 S7)
        // Host CPython for build-time .pyc compilation must match the app's minor version.
        // Auto-detected on PATH (CI provides python3.14 via actions/setup-python); override locally
        // with -Pneutrodyne.buildPython=<python3.14 executable> (01 S7, 09 release.yml).
        providers.gradleProperty("neutrodyne.buildPython").orNull?.let { buildPython(it) }
        pip {
            // No pip packages in v1 (no yt-dlp extras, so never mutagen); a package needs a
            // python-components.lock entry first.
        }
    }
    sourceSets {
        getByName("main") {
            // The shared shim package neutrodyne_ytx (04 Shared engine module), compiled to .pyc
            // with buildPython and packaged as the chaquopy app asset.
            srcDir("../engine/python")
        }
    }
}

dependencies {
    implementation(project(":youtube:engine"))
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network:okhttp"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.truth)
}

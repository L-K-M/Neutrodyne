// SPDX-License-Identifier: Unlicense
plugins {
    // AGP is already on the build classpath via build-logic, so id-only apply
    // (same as the other convention plugins in this build).
    id("com.android.application")
}

// The M4 P6b bgstart trigger: a standalone APK in its own package and uid —
// deliberately outside the self-instrumenting :tools:bmgrdriver (TOP all leg).
// Its `status` provider method performs a native Media3 MediaController connect
// to the target's exported MediaLibraryService (bound clients are visible to
// the bound app — the r13 framework-relay play() was omitted because the
// package was invisible: "Package ... doesn't exist" -> untrusted ->
// COMMAND_PLAY_PAUSE unavailable) and reports the granted command set. The
// driver measures it twice: untrusted (no enabled listener -> a real bind with
// no player commands) and trusted (enabled NotificationListenerService -> the
// bind itself stamps the PROC_STATE_BFGS/startForegroundCount:0 grant — the
// impossibility row).
// Java only: no Kotlin.
android {
    namespace = "ch.lkmc.neutrodyne.tools.bgtrigger"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ch.lkmc.neutrodyne.tools.bgtrigger"
        minSdk = 26
        // Deliberately old: the fixture acts like a pre-S external controller
        // (no FGS-type manifest requirements).
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }
    lint {
        // Deliberate, load-bearing choice: the fixture models a pre-S external
        // controller. Never distributed — the "expired" warning does not apply.
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        // Same committed public key as every published artifact (D61, 01 Signing config)
        create("release") {
            storeFile = rootProject.file("signing/neutrodyne-public.keystore")
            storeType = "pkcs12"
            storePassword = "neutrodyne"
            keyAlias = "neutrodyne"
            keyPassword = "neutrodyne"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // The native Media3 client surface a real trusted controller connects
    // through; the same version the target ships.
    implementation(libs.androidx.media3.session)
}

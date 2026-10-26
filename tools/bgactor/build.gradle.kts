// SPDX-License-Identifier: Unlicense
plugins {
    // AGP is already on the build classpath via build-logic, so id-only apply
    // (same as the other convention plugins in this build).
    id("com.android.application")
}

// The M4 P6b bgstart actor: a standalone, inert APK in its own package and uid —
// deliberately outside the self-instrumenting :tools:bmgrdriver, whose uid stays
// TOP for the whole leg and would stamp every binding it makes with a granting
// capability record. Its single background service binds the target's exported
// MediaLibraryService from a demoted state, the external analogue of the in-app
// MediaController.connect() path the case models. Java only: no Kotlin, no deps.
android {
    namespace = "ch.lkmc.neutrodyne.tools.bgactor"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ch.lkmc.neutrodyne.tools.bgactor"
        minSdk = 26
        // Below S the shell `am startservice` that brings this package up is exempt
        // from the background service-start restriction (appRestrictedInBackgroundLocked
        // only gates packages targeting 31+).
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }
    lint {
        // Deliberate, load-bearing choice: targetSdk below S is what exempts the
        // shell `am startservice` that brings this fixture up from the bg-start
        // restriction. Never distributed — the "expired" warning does not apply.
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

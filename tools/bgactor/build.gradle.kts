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
// MediaController.connect() path the case models. Java only: no Kotlin.
android {
    namespace = "ch.lkmc.neutrodyne.tools.bgactor"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ch.lkmc.neutrodyne.tools.bgactor"
        minSdk = 26
        // Deliberately old: the fixture acts like a pre-S external controller
        // (no FGS-type manifest requirements). The package comes up via
        // `am start` on BgActorActivity — an activity launch is exempt from the
        // bg-start restriction that denies `am startservice` on a cold package.
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
    // The native Media3 client surface: a real external controller's connect +
    // prepare() is what surfaces the durable paused session as PAUSED so the
    // SystemUI card can render. The same version the target ships.
    implementation(libs.androidx.media3.session)
}

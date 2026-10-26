// SPDX-License-Identifier: Unlicense
plugins {
    // AGP is already on the build classpath via build-logic, so id-only apply
    // (same as the other convention plugins in this build).
    id("com.android.application")
}

// The M4 P6b bgstart trigger: a standalone, inert APK in its own package and uid —
// deliberately outside both the self-instrumenting :tools:bmgrdriver (TOP all leg)
// and the demoted :tools:bgactor binder (its binding must stay the only client on
// the target's ServiceRecord). An enabled NotificationListenerService makes this
// uid trusted for media control (MediaSessionManager.isTrustedForMediaControl);
// its exported provider then issues prepare/play through the framework
// MediaController — commands relay via MediaSessionRecord in system_server, so
// NO app->app binding ever lands on the target's ServiceRecord and nothing the
// trigger's own elevated uid does can stamp a granting allow record there.
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

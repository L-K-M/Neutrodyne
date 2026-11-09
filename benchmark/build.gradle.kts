// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.android.systemtest)
}

android {
    namespace = "ch.lkmc.neutrodyne.benchmark"
    targetProjectPath = ":app"
    defaultConfig {
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Resolution 9: use the recorded fallback while the plain release mapping is
    // unverified. The target is minified and profileable, never the published APK.
    buildTypes {
        create("benchmarkRelease") {
            isDebuggable = true
            matchingFallbacks += "release"
        }
    }
    testOptions {
        animationsDisabled = true
        managedDevices {
            localDevices {
                create("bench34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                    require64Bit = true
                }
                create("api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                    require64Bit = true
                }
            }
        }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
// The producer plugin creates `nonMinifiedRelease` (profile generation) and
// `benchmarkRelease` (Macrobenchmarks); `release` and `debug` stay disabled so the
// module builds only the two variants that run on a managed device.
androidComponents {
    beforeVariants {
        it.enable = it.buildType == "benchmarkRelease" || it.buildType == "nonMinifiedRelease"
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        // Other test classes exercise benchmarkRelease's fixtures and Macrobenchmark rules.
        // The profile producer runs only its generator on this unminified target.
        variant.instrumentationRunnerArguments.put(
            "class",
            "ch.lkmc.neutrodyne.benchmark.BaselineProfileGenerator",
        )
    }
}
baselineProfile {
    managedDevices += "bench34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit4)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.mockwebserver3)
}

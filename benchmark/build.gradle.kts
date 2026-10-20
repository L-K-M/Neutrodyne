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
androidComponents { beforeVariants { it.enable = it.buildType == "benchmarkRelease" } }
baselineProfile {
    managedDevices += "bench34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit4)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.mockwebserver3)
}

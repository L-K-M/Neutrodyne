// SPDX-License-Identifier: Unlicense
plugins {
    id("neutrodyne.android.systemtest")
}

android {
    namespace = "ch.lkmc.neutrodyne.benchmark"
    targetProjectPath = ":app"
    defaultConfig {
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmarkRelease") {
            isDebuggable = true
            matchingFallbacks += "release"
        }
    }
    testOptions {
        animationsDisabled = true
        managedDevices.localDevices.create("bench34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
            require64Bit = true
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
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit4)
}

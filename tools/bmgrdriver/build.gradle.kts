// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// The M3 black-box bmgr driver (05 Testing with bmgr, owner decision 2026-10-21): a
// self-instrumenting device-test APK in its own package and process. It builds backup
// fixtures with the real :feeds codec, serves them through its own FileProvider and drives
// the published release build's `ExternalImportActivity` over UiAutomator. It never depends
// on :app and never loads target classes — the host (`scripts/ci/bmgr-check.sh`) owns root,
// transport control, evidence capture and every assertion.
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).apply {
            // The FileProvider paths file travels inside the driver library's own AAR.
            androidResources { enable = true }
            withDeviceTest { }
            // The self-instrumenting runner: without the full arming triple it drops the
            // driver class before discovery, so ordinary GMD runs only see DriverSelfTest.
            compilations.configureEach {
                if (this is KotlinMultiplatformAndroidDeviceTestCompilation) {
                    instrumentationRunner = "ch.lkmc.neutrodyne.tools.bmgrdriver.BmgrDriverRunner"
                }
            }
        }
    }

    sourceSets {
        findByName("androidDeviceTest")?.dependencies {
            implementation(project(":feeds"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.junit4)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.uiautomator)
            implementation(libs.androidx.core.ktx)
        }
    }
}

// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
    alias(libs.plugins.roborazzi)
}

kotlin {
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { isIncludeAndroidResources = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":playback:api"))
            implementation(project(":download:api"))
            implementation(project(":youtube:api"))
            implementation(libs.aboutlibraries.core)
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            implementation(project(":core:testing"))
            // A Main dispatcher for `collectAsStateWithLifecycle` on the desktop JVM.
            implementation(libs.kotlinx.coroutines.swing)
            // Skiko natives so runComposeUiTest can render on this machine's OS.
            implementation(compose.desktop.currentOs)
        }
    }
}

roborazzi { outputDir.set(layout.projectDirectory.dir("screenshots")) }

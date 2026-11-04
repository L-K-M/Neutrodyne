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
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
        }

        commonMain.dependencies {
            implementation(project(":download:api"))
            implementation(project(":playback:api"))
            implementation(project(":youtube:api"))
        }
    }
}

roborazzi { outputDir.set(layout.projectDirectory.dir("screenshots")) }

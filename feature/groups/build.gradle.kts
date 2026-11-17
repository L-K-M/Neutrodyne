// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
    alias(libs.plugins.roborazzi)
}

kotlin {
    sourceSets {
        desktopTest.dependencies {
            implementation(libs.roborazzi.compose.desktop)
            implementation(libs.cmp.ui.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
        }
        commonMain.dependencies {
            implementation(project(":youtube:api"))
            implementation(project(":feeds"))
            implementation(libs.reorderable)
            implementation(libs.lifecycle.viewmodel.savedstate)
        }
    }
}

roborazzi { outputDir.set(layout.projectDirectory.dir("screenshots")) }

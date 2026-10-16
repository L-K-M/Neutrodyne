// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":youtube:api"))
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
        }
    }
}

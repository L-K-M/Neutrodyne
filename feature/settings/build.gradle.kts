// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
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

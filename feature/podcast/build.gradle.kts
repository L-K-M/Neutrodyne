// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":playback:api"))
            implementation(project(":download:api"))
            implementation(project(":youtube:api"))
        }
        commonTest.dependencies {
            // asSnapshot drives the paged feed's PagingData assertions.
            implementation(libs.androidx.paging.testing)
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            // A Main dispatcher for `collectAsLazyPagingItems` on the desktop JVM.
            implementation(libs.kotlinx.coroutines.swing)
            // Skiko natives so runComposeUiTest can render on this machine's OS.
            implementation(compose.desktop.currentOs)
        }
    }
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
}

kotlin {
    sourceSets {
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
        }
        commonMain.dependencies {
            implementation(project(":playback:api"))
            implementation(project(":download:api"))
            implementation(project(":youtube:api"))
        }
        commonTest.dependencies {
            // asSnapshot drives the paged import rows' PagingData assertions.
            implementation(libs.androidx.paging.testing)
        }
    }
}

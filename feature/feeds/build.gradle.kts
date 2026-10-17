// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
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
        }
        commonTest.dependencies {
            // asSnapshot drives the paged feed's PagingData assertions.
            implementation(libs.androidx.paging.testing)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.espresso.core)
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

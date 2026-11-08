// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.feature)
}

kotlin {
    targets.named("android") {
        // androidHostTest renders the real SyncSetupScreen under Robolectric; the manifest that
        // registers the compose host activity reaches it only with resources included.
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { isIncludeAndroidResources = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":sync:api"))
            implementation(project(":core:designsystem"))
        }
        androidMain.dependencies {
            // rememberLauncherForActivityResult in the local-network prompt bridge.
            implementation(libs.androidx.activity.compose)
        }
        named("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            // ui-test-junit4's junit/espresso coordinates miss the offline cache; the catalog pins
            // resolve to the versions the app tests already carry.
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.espresso.core)
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            // A Main dispatcher for `collectAsStateWithLifecycle` on the desktop JVM.
            implementation(libs.kotlinx.coroutines.swing)
            // Skiko natives so runComposeUiTest can render on this machine's OS.
            implementation(compose.desktop.currentOs)
        }
    }
}

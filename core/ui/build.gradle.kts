// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.compose)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    // Host (Robolectric) tests for the shared navigation host: the transition proof needs real
    // Android frame advancement, which the desktop Skiko harness does not drive (08 Transitions).
    // Device tests live in :app (01 Convention plugins: device tests never in library modules).
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).apply {
            // includeAndroidResources so src/androidHostTest/AndroidManifest.xml (which registers
            // the compose test host activity) reaches Robolectric's package manager.
            withHostTest { isIncludeAndroidResources = true }
        }
    }

    compilerOptions {
        // adaptive-navigation3 / navigation-suite APIs (ListDetailSceneStrategy, directives).
        optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:designsystem"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:navigation"))
            implementation(project(":download:api"))
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.navigation3.ui.jb)
            // The suite type + pane directive classes are used directly here; :core:designsystem
            // holds the same artifacts as `implementation`, so they do not leak onto this classpath.
            implementation(libs.cmp.material3.navigationSuite)
            implementation(libs.cmp.material3.adaptive)
            implementation(libs.cmp.material3.adaptive.layout)
            implementation(libs.cmp.material3.adaptive.navigation3)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.metrox.viewmodel.compose)
        }
        androidMain.dependencies {
            // rememberLauncherForActivityResult in rememberAndroidPlatformActions().
            implementation(libs.androidx.activity.compose)
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            // Skiko natives so runComposeUiTest can render on this machine's OS.
            implementation(compose.desktop.currentOs)
        }
        findByName("androidHostTest")?.dependencies {
            // Robolectric host tests render the real Android NavDisplay (task `testAndroidHostTest`),
            // including pixel capture under native graphics; device tests stay in :app (01).
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            // ui-test-junit4 asks for junit:1.1.5 / espresso:3.5.0, whose metadata is not in the
            // offline cache; the catalog pins resolve to the cached versions the app tests use.
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.espresso.core)
        }
    }
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.compose)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
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
        commonTest.dependencies {
            implementation(libs.cmp.ui.test)
        }
        desktopTest.dependencies {
            implementation(libs.cmp.ui.test)
            // Skiko natives so runComposeUiTest can render on this machine's OS.
            implementation(compose.desktop.currentOs)
        }
    }
}

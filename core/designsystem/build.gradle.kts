// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.compose)
}

kotlin {
    // currentWindowAdaptiveInfo / WindowSizeClass carry the adaptive opt-in.
    compilerOptions {
        optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(libs.cmp.material3.navigationSuite)
            implementation(libs.cmp.material3.adaptive)
            implementation(libs.graphics.shapes)
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.collections.immutable)
        }
        androidMain.dependencies {
            // WindowCompat for the status-bar appearance actual.
            implementation(libs.androidx.core.ktx)
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

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    id("org.jetbrains.kotlin.plugin.serialization")
    // The compose compiler plugin so `(@Composable () -> Unit)?` in NdSceneMetadata erases to
    // Function2 — the same runtime type every compose-enabled caller compiles against.
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.navigation3.runtime)
            api(libs.cmp.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

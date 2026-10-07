// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:common"))
            api(project(":playback:api"))
            api(project(":download:api"))
            api(project(":youtube:api"))
            api(libs.androidx.paging.common)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

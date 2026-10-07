// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":youtube:api"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:network"))
        }
    }
}

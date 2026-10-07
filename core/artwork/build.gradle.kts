// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:network"))
            implementation(project(":youtube:api"))
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(libs.coil.core)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(project(":core:network:okhttp"))
            implementation(libs.coil.network.okhttp)
        }
        desktopMain.dependencies {
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(project(":core:network:okhttp"))
            implementation(libs.coil.network.okhttp)
        }
    }
}

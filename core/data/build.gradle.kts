// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:datastore"))
            implementation(project(":core:network"))
            implementation(project(":core:artwork"))
            implementation(project(":feeds"))
            implementation(project(":youtube:api"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(project(":feeds:jvm"))
        }
        desktopMain.dependencies {
            implementation(project(":feeds:jvm"))
        }
        // Wiring DataStoreBindings' providers in desktopTest mentions the DataStore type
        // (implementation deps of :core:datastore are otherwise invisible here).
        desktopTest.dependencies {
            implementation(libs.androidx.datastore.preferences.core)
        }
    }
}

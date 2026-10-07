// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    // Host (Robolectric) tests for ConnectivityNetworkMonitor — runs on the JVM, no emulator.
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            api(project.dependencies.platform(libs.ktor.bom))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            // `StateFlow`/`callbackFlow` for the platform NetworkMonitors.
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.ktor.bom))
            // api, not implementation: the island's Metro contributions only reach a shell graph when
            // the island is on the shell's compile classpath (S8, 2026-10-06)
            api(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
        desktopMain.dependencies {
            implementation(project.dependencies.platform(libs.ktor.bom))
            api(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
        }
        desktopTest.dependencies {
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.okhttp.mockwebserver3.junit4)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.test)
            // BuildInfo's signature exposes ImmutableList; :core:model keeps it `implementation`.
            implementation(libs.kotlinx.collections.immutable)
        }
    }
}

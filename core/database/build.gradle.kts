// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    alias(libs.plugins.neutrodyne.room)
}

kotlin {
    // Host (Robolectric) tests for the SQLite drivers (S4, 2026-10-06); sqlite-framework
    // comes from neutrodyne.room. The task is `testAndroidHostTest`. Device tests live in
    // `:app` (01 Convention plugins: device tests never in library modules).
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(libs.kotlinx.serialization.json)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
        }
    }
}

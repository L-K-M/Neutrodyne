// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    alias(libs.plugins.neutrodyne.room)
}

kotlin {
    // Host (Robolectric) tests for the SQLite drivers (S4, 2026-10-06); sqlite-framework
    // comes from neutrodyne.room. The task is `testAndroidHostTest`.
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).apply {
            withHostTest { }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(libs.kotlinx.serialization.json)
        }
        findByName("desktopTest")?.dependencies {
            // Test-only edge (02 Testing; module-graph rule 13 does not assert test edges): the
            // shared TestDb/MigrationInvariants/SqlEnumLiterals helpers live in :core:testing.
            implementation(project(":core:testing"))
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(project(":core:testing"))
        }
    }
}

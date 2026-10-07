// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    alias(libs.plugins.neutrodyne.room)
}

kotlin {
    // Host (Robolectric) tests for the SQLite drivers (S4, 2026-10-06); room3-testing and
    // sqlite-framework come from neutrodyne.room. The task is `testAndroidHostTest`.
    // Device (GMD) tests keep the Android side of migration/rebuild honest (02 Testing); the
    // task is `testDebugAndroidTest…` on a Gradle Managed Device.
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).apply {
            withHostTest { }
            withDeviceTest { }
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
        findByName("androidDeviceTest")?.dependencies {
            implementation(libs.junit4)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.room3.testing)
            implementation(libs.androidx.sqlite.framework)
            implementation(libs.androidx.sqlite.bundled)
            implementation(project(":core:testing"))
        }
    }
}

// AGP's KMP device-test compilation registers no `assets` source dir, but its `resources` dir
// merges into the test APK verbatim. Files under `src/androidDeviceTest/resources/assets/` land
// at `assets/…` inside the APK, which is exactly where the Android MigrationTestHelper looks for
// `assets/<database-qualified-name>/<version>.json`; `db/v1-fixture.sql` rides along the same
// way. Both are symlinks to the canonical copies (frozen `schemas/…/1.json`,
// `src/desktopTest/resources/db/v1-fixture.sql`), so they cannot drift.

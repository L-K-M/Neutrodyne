// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    targets.named("android") {
        (this as com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget).apply {
            withHostTest { }
        }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        desktopTest.dependencies {
            // KtorSyncClientTest: the real SYNC client stack against a scripted server.
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.okio)
        }
        findByName("androidMain")?.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.lifecycle.process)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.work.testing)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.lifecycle.process)
            implementation(project(":core:testing"))
        }
        commonMain.dependencies {
            implementation(project(":sync:api"))
            implementation(project(":sync:protocol"))
            implementation(project(":core:domain"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:datastore"))
            implementation(project(":core:network"))
            implementation(project(":feeds"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

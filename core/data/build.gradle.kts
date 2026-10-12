// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Host (Robolectric) tests: the WorkManager test-driver suite, the foreground observer and the
    // golden corpus on the platform parser (03 corpus leg b). The task is `testAndroidHostTest`.
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { }
    }

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
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.lifecycle.process)
        }
        desktopMain.dependencies {
            implementation(project(":feeds:jvm"))
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.work.testing)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(project(":core:testing"))
            // Corpus leg b: `XmlPullFeedParser` on the platform parser (03 Testing).
            implementation(project(":feeds:jvm"))
        }
        // Wiring DataStoreBindings' providers in desktopTest mentions the DataStore type
        // (implementation deps of :core:datastore are otherwise invisible here).
        desktopTest.dependencies {
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.okhttp.mockwebserver3.junit4)
            implementation(libs.okio)
            // TestSupport builds the island stack itself (Ktor's OkHttp engine).
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            // `PullParserFactory.Discovered`'s runtime parser (kxml2 is `compileOnly` in :feeds:jvm).
            implementation(libs.kxml2)
        }
    }
}

// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { }
    }

    sourceSets {
        val jvmMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmMain)
        desktopMain.get().dependsOn(jvmMain)

        commonMain.dependencies {
            implementation(project(":download:api"))
            implementation(project(":youtube:api"))
            implementation(project(":core:domain"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:datastore"))
            implementation(project(":core:network"))
            implementation(project(":core:artwork"))
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        desktopTest.dependencies {
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.okhttp.tls)
            implementation("com.squareup.okio:okio-fakefilesystem:${libs.versions.okio.get()}")
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(libs.truth)
        }
    }
}

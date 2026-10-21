// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    targets.named("android") {
        (this as KotlinMultiplatformAndroidLibraryTarget).withHostTest { isIncludeAndroidResources = true }
    }
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
            implementation(libs.androidx.work.runtime)
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(project(":core:network:okhttp"))
            implementation(libs.coil.network.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(project(":core:testing"))
            implementation(libs.robolectric)
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.work.testing)
            implementation(libs.androidx.sqlite.framework)
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.okhttp.mockwebserver3.junit4)
        }
        desktopMain.dependencies {
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(project(":core:network:okhttp"))
            implementation(libs.coil.network.okhttp)
        }
    }
}

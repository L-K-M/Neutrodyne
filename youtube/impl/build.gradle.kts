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
    sourceSets.named("desktopTest") {
        resources.srcDir("src/test/resources")
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets.named("androidHostTest") {
        resources.srcDir("src/test/resources")
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":youtube:api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:network"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        val replayDependencies = listOf("desktopTest", "androidHostTest")
        replayDependencies.forEach { name ->
            named(name).dependencies {
                implementation(libs.okhttp.mockwebserver3)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.junit4)
                implementation(project(":core:network:okhttp"))
            }
        }
    }
}

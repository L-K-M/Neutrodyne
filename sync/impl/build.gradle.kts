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
            // TwoClientWireTest runs the production DesktopJobRunner; a test edge does not
            // widen :sync:impl's production surface (rule 13: test edges are not asserted).
            implementation(project(":core:data"))
        }
        findByName("androidMain")?.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.lifecycle.process)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.androidx.work.testing)
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
            implementation(project(":playback:api"))
            implementation(project(":core:domain"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:datastore"))
            implementation(project(":core:network"))
            implementation(project(":feeds"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}

// The two-client wire suite launches the packaged server as a loopback child JVM. The task edge
// and declared input hand it the fat JAR's path without a :sync:impl -> :sync:server compile edge;
// a missing JAR fails the test rather than skipping it.
tasks.named<org.gradle.api.tasks.testing.Test>("desktopTest") {
    dependsOn(":sync:server:fatJar")
    val serverJar =
        rootProject.layout.projectDirectory.file(
            "sync/server/build/libs/neutrodyne-server-${providers.gradleProperty("neutrodyne.versionName").get()}.jar",
        )
    inputs.files(serverJar)
    systemProperty("neutrodyne.serverJar", serverJar.asFile.absolutePath)
}

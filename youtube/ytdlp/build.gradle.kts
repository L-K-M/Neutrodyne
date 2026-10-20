// SPDX-License-Identifier: Unlicense
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.neutrodyne.android.library)
    id("com.chaquo.python")
}

android {
    buildFeatures { aidl = true } // IYtxEngine, IYtxCallback (04 Binder API)
    defaultConfig {
        // Chaquopy publishes no CPython >= 3.12 runtime for 32-bit ABIs (D77; the armeabi-v7a
        // APK of :app carries no engine and runs YouTube in external mode).
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets["androidTest"].kotlin.directories += "src/deviceSelectionTest/kotlin"
    sourceSets["test"].kotlin.directories += "src/deviceSelectionTest/kotlin"
}

tasks.withType<Test>().configureEach {
    // The selection test invokes this negative control through the pinned runner itself.
    exclude("ch/lkmc/neutrodyne/youtube/ytdlp/ytx/SupportedPythonDeviceTest\$FailureFixture.class")
}

chaquopy {
    defaultConfig {
        version = "3.14" // fallback "3.13" (01 S7)
        // Host CPython for build-time .pyc compilation must match the app's minor version.
        // Auto-detected on PATH (CI provides python3.14 via actions/setup-python); override locally
        // with -Pneutrodyne.buildPython=<python3.14 executable> (01 S7, 09 release.yml).
        providers.gradleProperty("neutrodyne.buildPython").orNull?.let { buildPython(it) }
        pip {
            // No pip packages in v1 (no yt-dlp extras, so never mutagen); a package needs a
            // python-components.lock entry first.
        }
    }
    sourceSets {
        getByName("main") {
            // The shared shim package neutrodyne_ytx (04 Shared engine module), compiled to .pyc
            // with buildPython and packaged as the chaquopy app asset.
            srcDir("../engine/python")
            exclude("tests/**", "neutrodyne_ytx/record.py")
        }
    }
}

// Package only data needed at runtime; upstream verification and its key remain build-only.
val bundledAssetsDirectory = layout.buildDirectory.dir("generated/bundledEngineAssets")
val bundledAssets =
    tasks.register<Sync>("prepareBundledYtDlpAssets") {
        dependsOn("verifyBundledYtDlp")
        from(layout.projectDirectory.dir("engine")) {
            include("yt-dlp", "bundled.json")
            into("ytdlp")
        }
        into(bundledAssetsDirectory)
    }
// Chaquopy exposes legacy Kotlin DSL accessors; select AGP's actual source-set type explicitly.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    // AGP rejects SourceSet providers; preBuild below supplies the producer dependency explicitly.
    sourceSets.named("main") { assets.srcDir(bundledAssetsDirectory.get().asFile) }
}
tasks.named("preBuild") { dependsOn(bundledAssets) }

dependencies {
    implementation(project(":youtube:engine"))
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network:okhttp"))

    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.truth)
}

// Host-only strict replay uses the same verified asset packaged above.
tasks.register<Exec>("shimTest") {
    dependsOn("verifyBundledYtDlp")
    group = "verification"
    description = "Run Python shim tests against hash-verified yt-dlp with strict offline replay"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "youtube/engine/python/tests/run.py")
    // Always run: the selected host interpreter and upstream API are part of this proof.
}

// Keep host process execution in build tooling; the Android test supplies the real store/payload.
val preparedShimDirectory = layout.buildDirectory.dir("preparedShim")
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        val outputDirectory = preparedShimDirectory.get().asFile
        outputs.dir(outputDirectory)
        doFirst { outputDirectory.deleteRecursively() }
    }
}
tasks.register<Exec>("preparedShimTest") {
    group = "verification"
    description = "Probe the real prepared store and worker payload through the Python host adapter"
    dependsOn("testDebugUnitTest", "verifyBundledYtDlp")
    inputs.dir(preparedShimDirectory)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "-I",
        "-B",
        "youtube/engine/python/tests/probe_prepared.py",
        preparedShimDirectory.get().asFile.absolutePath,
    )
}

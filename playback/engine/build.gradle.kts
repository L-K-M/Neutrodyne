// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
    // On the build classpath via build-logic, so id-only apply (01 Gradle-side policy tasks).
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":playback:native"))
    implementation(project(":playback:api"))
    implementation(project(":core:network:okhttp"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit4)
}

// The engine's JDK 25 fakes live with the shared test doubles. Compiling these
// sources here avoids exposing FFM/JDK 25 types to core:testing's JDK 21 artifact.
kotlin.sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("core/testing/src/desktopEngineMain/kotlin"))
}

// `ffmpegTest` runs the corpus suite that loads the built FFmpeg libraries
// (11 Testing, MD0 AC1) on its own task rather than inside `test`, because the
// suite needs the four shared libraries of the host it runs on: buildFfmpeg
// produces them for the target this machine serves, so the suite runs on each
// of the four desktop runners and never where no build exists. `test`
// therefore excludes it instead of letting it fail on a host with no build.
//
// The task is depended on by path, not by provider: `:playback:engine` is
// configured before `:playback:native`, and a task provider from a project
// that has not been evaluated yet cannot be resolved.
tasks.register<Test>("ffmpegTest") {
    group = "verification"
    description = "Decode the MD0 corpus through the FFM bindings on this host's FFmpeg build."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    dependsOn(":playback:native:buildFfmpeg", ":playback:native:buildNdmedia")
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching("*FfmpegCorpusTest")
    filter.excludeTestsMatching("*EngineClockTest")
    filter.excludeTestsMatching("*EngineCommandsTest")
    filter.excludeTestsMatching("*FfAudioEngineTest")
}

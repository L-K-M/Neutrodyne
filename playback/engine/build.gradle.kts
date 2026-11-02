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
}

/**
 * `ffmpegTest`: the corpus suite that loads the built FFmpeg libraries (11 Testing,
 * MD0 AC1). It runs on its own task rather than inside `test`, because it needs
 * the four shared libraries of the host it runs on: `buildFfmpeg` produces them
 * for the target this machine serves, so the suite runs on each of the four
 * desktop runners and never where no build exists. `test` therefore excludes it
 * instead of letting it fail on a host without a native build.
 */
val nativeBuild = project(":playback:native").tasks.named("buildFfmpeg")

tasks.register<Test>("ffmpegTest") {
    group = "verification"
    description = "Decode the MD0 corpus through the FFM bindings on this host's FFmpeg build."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    dependsOn(nativeBuild)
    // The directory buildFfmpeg installed into, read from the task itself so the
    // target this machine serves is never spelled out twice. It is resolved at
    // execution time, when the build has produced its output.
    doFirst {
        systemProperty("neutrodyne.ffmpegDir", nativeBuild.get().outputs.files.singleFile)
    }
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching("*FfmpegCorpusTest")
}

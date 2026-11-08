// SPDX-License-Identifier: Unlicense
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver3)
}

// `UpstreamReleaseVerifierTest` verifies the real vendored release vector (resolved through
// `neutrodyne.rootDir`); declaring the files keeps `test` out-of-date whenever they change.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.layout.projectDirectory.file("youtube/ytdlp/engine/SHA2-256SUMS"),
        rootProject.layout.projectDirectory.file("youtube/ytdlp/engine/SHA2-256SUMS.sig"),
        rootProject.layout.projectDirectory.file("youtube/ytdlp/engine/bundled.json"),
        rootProject.layout.projectDirectory.file("youtube/ytdlp/engine/yt-dlp"),
        rootProject.layout.projectDirectory.file("youtube/ytdlp/keys/yt-dlp-release-key.asc"),
        rootProject.layout.projectDirectory.file("youtube/ytdlp/keys/engine-manifest-ed25519.pub"),
    )
}

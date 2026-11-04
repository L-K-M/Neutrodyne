// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
}

dependencies {
    implementation(project(":playback:core"))
    implementation(project(":playback:api"))
    implementation(project(":playback:engine"))
    implementation(project(":desktop:system"))
    implementation(project(":download:api"))
    implementation(project(":youtube:api"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:artwork"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":core:network:okhttp"))
}

// Keep JDK 25 engine fakes out of core:testing's Android/JDK 21 artifact.
kotlin.sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("core/testing/src/desktopEngineMain/kotlin"))
}

// The production boundary runs only on a host with its built libraries, always on null output.
tasks.named<Test>("test") {
    filter.excludeTestsMatching("*DesktopNativePlaybackTest")
}

tasks.register<Test>("desktopPlaybackTest") {
    group = "verification"
    description = "Exercise the desktop controller through FFmpeg and the null audio backend."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter.includeTestsMatching("*DesktopNativePlaybackTest")
    dependsOn(":playback:native:buildFfmpeg", ":playback:native:buildNdmedia")
    systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    val target =
        when {
            System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
            System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
            System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
            else -> "linux-x64"
        }
    systemProperty("neutrodyne.nativeTarget", target)
}

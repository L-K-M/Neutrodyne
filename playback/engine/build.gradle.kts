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

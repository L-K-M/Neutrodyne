// SPDX-License-Identifier: Unlicense
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
    // Test-only: fixture clients go through the network island; checkBannedApis forbids
    // constructing OkHttp here directly.
    testImplementation(project(":core:network:okhttp"))
    testImplementation(libs.okhttp.mockwebserver3)
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
}

dependencies {
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
}

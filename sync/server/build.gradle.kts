// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.server.application)
}

dependencies {
    implementation(project(":sync:protocol"))
    implementation(project(":feeds"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
}

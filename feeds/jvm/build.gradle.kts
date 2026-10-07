// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
}

dependencies {
    implementation(project(":feeds"))
    implementation(libs.jsoup)
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
}

dependencies {
    implementation(project(":playback:native"))
    implementation(project(":playback:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}

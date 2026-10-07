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
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
}

dependencies {
    implementation(project(":youtube:engine"))
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
}

// The golden-corpus tree (03 Golden corpus) is a plain data directory of the :feeds project, added as
// test resources here so the same fixtures also feed the Android parser tests from :core:data.
sourceSets.test {
    resources.srcDir(rootProject.layout.projectDirectory.dir("feeds/src/test/resources"))
}

dependencies {
    implementation(project(":feeds"))
    implementation(libs.jsoup)
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    // Golden-corpus JSON encoding; :feeds exposes its models with implementation, not api.
    testImplementation(libs.kotlinx.serialization.json)
}

// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.okio)
    // `LocalNetworkAccess.syncAllowed` is a StateFlow — needed on the compile classpath.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit4)
    testImplementation(libs.okhttp.tls)
}

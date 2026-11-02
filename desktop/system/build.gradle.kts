// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
}

dependencies {
    implementation(project(":playback:native"))
    implementation(project(":playback:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    // MPRIS/logind/portals all ride the Linux session bus; dbus-java is the
    // documented choice (11 OS integration). Unix-socket transport covers the
    // session and the private test bus.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)

    testImplementation(project(":core:testing"))
}

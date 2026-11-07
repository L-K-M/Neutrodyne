// SPDX-License-Identifier: Unlicense
import java.time.Duration
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
    // HKCU Run value and StartupApproved read through Advapi32Util (11 Start at login).
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(project(":core:testing"))
}

// This suite requires the native prototype and a real OS session API.
tasks.register<Test>("systemSessionTest") {
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter.includeTestsMatching("*NativeSystemMediaSessionTest")
    timeout.set(Duration.ofMinutes(3))
    dependsOn(":playback:native:buildNdmedia")
}

tasks.named<Test>("test") {
    filter.excludeTestsMatching("*NativeSystemMediaSessionTest")
    // The D-Bus tests claim well-known names (the portal, notifications, the file manager) on the
    // one session bus that dbus-run-session gives the whole build; two forks claiming the portal
    // name at once failed with "Failed to register bus name". One fork runs them in turn.
    maxParallelForks = 1
}

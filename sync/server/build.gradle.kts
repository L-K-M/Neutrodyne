// SPDX-License-Identifier: Unlicense
import app.cash.licensee.LicenseeExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.WriteProperties
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named

plugins {
    alias(libs.plugins.neutrodyne.server.application)
}

// slf4j's POM names its MIT licence by URL only, in opensource.org's current URL form, which
// Licensee cannot map to an SPDX id (01 Licensee allow-list: scoped allowUrl with because).
extensions.configure<LicenseeExtension> {
    allowUrl("https://opensource.org/license/mit") {
        because("slf4j-api and slf4j-simple are MIT; the POM lists only this licence URL")
    }
}

// serverVersion of the discovery document: a resource generated at build time from
// neutrodyne.versionName, with no timestamps (M0b; 10 Discovery, 10 Deployment).
// ServerVersion reads it from the classpath.
val generateServerVersionResource =
    tasks.register("generateServerVersionResource", WriteProperties::class) {
        description = "Writes neutrodyne-server.properties carrying neutrodyne.versionName."
        property(serverVersionKey, providers.gradleProperty(versionNameProperty))
        destinationFile = layout.buildDirectory.file("generated/server-version/$serverPropertiesFile")
    }

tasks.named<Copy>("processResources") {
    from(generateServerVersionResource)
}

dependencies {
    implementation(project(":sync:protocol"))
    implementation(project(":feeds"))

    implementation(platform(libs.ktor.bom))
    implementation(libs.bundles.server.ktor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
}

// Keys of the generated resource (read by ServerVersion).
private val serverPropertiesFile = "neutrodyne-server.properties"
private val serverVersionKey = "serverVersion"
private val versionNameProperty = "neutrodyne.versionName"

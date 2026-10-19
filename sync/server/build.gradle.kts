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
    allowDependency("org.bouncycastle", "bcprov-jdk18on", libs.versions.bouncycastle.get()) {
        because("Bouncy Castle Licence is MIT; the POM declares only its project licence URL")
    }
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
        property("repoUrl", providers.gradleProperty("neutrodyne.repoUrl"))
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
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
}

// Keys of the generated resource (read by ServerVersion).
private val serverPropertiesFile = "neutrodyne-server.properties"
private val serverVersionKey = "serverVersion"
private val versionNameProperty = "neutrodyne.versionName"

// Compile JVM-only domain fakes here; :core:testing must not depend on the server application.
kotlin.sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("core/testing/src/serverFixtures/kotlin"))
}

// Child JVMs prove OS lock/crash behavior using the same resolved runtime as the tests.
tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    systemProperty(
        "neutrodyne.server.testClasspath",
        project.extensions
            .getByType<org.gradle.api.tasks.SourceSetContainer>()
            .getByName("test")
            .runtimeClasspath.asPath,
    )
}

// Files, not a production dependency edge, connect server export and client restore tests.
val serverTestRuntime = extensions.getByType<SourceSetContainer>().getByName("test").runtimeClasspath
val exportTestFixture =
    tasks.register<JavaExec>("exportTestFixture") {
        dependsOn(tasks.named("testClasses"))
        classpath = serverTestRuntime
        mainClass.set("ch.lkmc.neutrodyne.sync.server.ServerExportFixture")
        val output = layout.buildDirectory.file("interop/server-export.zip")
        args(output.get().asFile.absolutePath)
        outputs.file(output)
    }

tasks.register<JavaExec>("syncLoad") {
    dependsOn(tasks.named("testClasses"))
    classpath = serverTestRuntime
    mainClass.set("ch.lkmc.neutrodyne.sync.server.SyncLoadTool")
    args(providers.gradleProperty("syncLoadUrl").getOrElse("http://127.0.0.1:8787"))
    args(providers.gradleProperty("syncLoadRecords").getOrElse("50000"))
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    val start = providers.gradleProperty("syncSeedStart").getOrElse("0")
    inputs.property("syncSeedStart", start)
    systemProperty("neutrodyne.syncSeedStart", start)
}

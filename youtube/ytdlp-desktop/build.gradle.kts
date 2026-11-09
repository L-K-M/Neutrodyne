// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.library)
    id("org.jetbrains.kotlin.plugin.serialization")
}

val engineResources = layout.buildDirectory.dir("engine-resources")

/**
 * The dev-build copy of the packaged `common/engine/` resource tree: the pinned yt-dlp zip
 * and its manifest, the signing keys, and the shim sources the stdio child runs. Packaged
 * images ship the same layout under `common/engine/` (11 Desktop YouTube engine host).
 */
val syncEngineResources =
    tasks.register<Sync>("syncEngineResources") {
        into(engineResources)
        into("ytdlp") {
            from(rootProject.layout.projectDirectory.dir("youtube/ytdlp/engine")) {
                include("yt-dlp", "bundled.json")
            }
        }
        into("keys") {
            from(rootProject.layout.projectDirectory.dir("youtube/ytdlp/keys"))
        }
        into("shim") {
            from(rootProject.layout.projectDirectory.dir("youtube/engine/python")) {
                include("neutrodyne_ytx/**/*.py")
            }
        }
    }

dependencies {
    implementation(project(":youtube:engine"))
    implementation(project(":youtube:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network:okhttp"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncEngineResources)
    systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("neutrodyne.engineResources", engineResources.get().asFile.absolutePath)
}

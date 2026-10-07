// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.application)
    // On the build classpath via build-logic, so id-only apply.
    id("com.mikepenz.aboutlibraries.plugin")
}

// Offline and reproducible, same convention as :app (01 AboutLibraries).
aboutLibraries {
    offlineMode.set(true)
    collect {
        configPath.set(layout.projectDirectory.dir("config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
}

val youtubeEngine =
    providers
        .gradleProperty("neutrodyne.youtubeEngine")
        .orElse("true")
        .get()
        .toBoolean()

dependencies {
    for (feature in listOf(
        "feeds",
        "library",
        "groups",
        "podcast",
        "episode",
        "player",
        "queue",
        "downloads",
        "discover",
        "importexport",
        "settings",
        "sync",
    )) {
        implementation(project(":feature:$feature"))
    }
    for (module in listOf(
        ":core:model",
        ":core:common",
        ":core:domain",
        ":core:navigation",
        ":core:database",
        ":core:datastore",
        ":core:network",
        ":core:data",
        ":core:artwork",
        ":core:designsystem",
        ":core:ui",
        ":feeds",
        ":playback:api",
        ":playback:core",
        ":playback:desktop",
        ":playback:engine",
        ":playback:native",
        ":desktop:system",
        ":download:api",
        ":download:impl",
        ":youtube:api",
        ":youtube:impl",
        ":sync:protocol",
        ":sync:api",
        ":sync:impl",
    )) {
        implementation(project(module))
    }
    if (youtubeEngine) implementation(project(":youtube:ytdlp-desktop"))

    implementation(compose.desktop.currentOs)
    // Metro aggregation must read the features' @ViewModelKey/@ManualViewModelAssistedFactoryKey map keys.
    implementation(libs.metrox.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.collections.immutable)
    runtimeOnly(libs.kxml2)

    // The desktop graph test (01 Testing)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

/**
 * `build-info.properties` for `DesktopBuildInfo` — the desktop counterpart of `:app`'s BuildConfig
 * fields (the gradle.properties single source) until M0b's packaging pipeline stamps the release
 * facts (11). Reads the shared shipped-locales list from `:app/policy`.
 */
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
val writeBuildInfoProperties =
    tasks.register("writeBuildInfoProperties") {
        val repoUrl = providers.gradleProperty("neutrodyne.repoUrl")
        val engineManifestUrl = providers.gradleProperty("neutrodyne.engineManifestUrl")
        val podcastIndexKey = providers.gradleProperty("neutrodyne.podcastIndexKey").orElse("")
        val podcastIndexSecret = providers.gradleProperty("neutrodyne.podcastIndexSecret").orElse("")
        // Only the text provider is captured: the `FileContents` object itself is not
        // serialisable by the configuration cache.
        val shippedLocales =
            providers.fileContents(rootProject.layout.projectDirectory.file("app/policy/locales.txt")).asText
        val output = buildInfoDir.map { it.file("build-info.properties") }
        inputs.property("repoUrl", repoUrl)
        inputs.property("engineManifestUrl", engineManifestUrl)
        inputs.property("podcastIndexKey", podcastIndexKey)
        inputs.property("podcastIndexSecret", podcastIndexSecret)
        inputs.property("shippedLocales", shippedLocales)
        outputs.file(output)
        doLast {
            val locales =
                shippedLocales
                    .get()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .joinToString(",")
            output.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    buildString {
                        append("repoUrl=").append(repoUrl.get()).append('\n')
                        append("engineManifestUrl=").append(engineManifestUrl.get()).append('\n')
                        append("podcastIndexKey=").append(podcastIndexKey.get()).append('\n')
                        append("podcastIndexSecret=").append(podcastIndexSecret.get()).append('\n')
                        append("shippedLocales=").append(locales).append('\n')
                    },
                )
            }
        }
    }

// The generated properties travel as a classpath resource (like `install-kind`).
tasks.named<ProcessResources>("processResources") {
    dependsOn(writeBuildInfoProperties)
    from(buildInfoDir)
}

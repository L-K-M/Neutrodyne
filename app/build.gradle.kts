// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.android.application)
    // On the build classpath via build-logic (version pinned in the catalog there), so id-only apply.
    id("com.mikepenz.aboutlibraries.plugin.android")
}

// Offline and reproducible: no remote licence/funding fetches; the engine stack's manual definitions
// live in config/libraries + config/licenses (01 AboutLibraries).
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

// Shipped locales of both apps (09 Shipped locales and per-app language)
val shippedLocales =
    providers
        .fileContents(layout.projectDirectory.file("policy/locales.txt"))
        .asText
        .get()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

android {
    defaultConfig {
        fun prop(name: String) = providers.gradleProperty(name).orElse("").get()
        buildConfigField("String", "REPO_URL", "\"${prop("neutrodyne.repoUrl")}\"")
        buildConfigField("String", "ENGINE_MANIFEST_URL", "\"${prop("neutrodyne.engineManifestUrl")}\"")
        buildConfigField("boolean", "YOUTUBE_ENGINE", youtubeEngine.toString())
        buildConfigField("String", "ACRA_MAILTO", "\"${prop("neutrodyne.acraMailto")}\"")
        buildConfigField("String", "PODCASTINDEX_KEY", "\"${prop("neutrodyne.podcastIndexKey")}\"")
        buildConfigField("String", "PODCASTINDEX_SECRET", "\"${prop("neutrodyne.podcastIndexSecret")}\"")
        buildConfigField("String", "SHIPPED_LOCALES", "\"${shippedLocales.joinToString(",")}\"")
    }
    // BCP 47 `en-US` -> resource qualifier `en-rUS`
    androidResources { localeFilters += shippedLocales.map { it.replace(Regex("-([A-Z]{2})$"), "-r$1") } }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "ACRA_MAILTO", "\"\"") // ACRA off in debug builds (D62)
        }
    }
}

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
        ":playback:impl",
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
    if (youtubeEngine) implementation(project(":youtube:ytdlp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.acra.mail)
    implementation(libs.acra.dialog)

    debugImplementation(libs.leakcanary.android)
}

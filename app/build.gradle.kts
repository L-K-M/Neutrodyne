// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.android.application)
    // On the build classpath via build-logic (version pinned in the catalog there), so id-only apply.
    id("com.mikepenz.aboutlibraries.plugin.android")
}

val youtubeEngine =
    providers
        .gradleProperty("neutrodyne.youtubeEngine")
        .orElse("true")
        .get()
        .toBoolean()

// Offline and reproducible: no remote licence/funding fetches. The engine stack's manual
// definitions live in their own config root, config/engine (01: the emergency build leaves it
// out, so its Licences screen omits the engine components).
aboutLibraries {
    offlineMode.set(true)
    collect {
        configPath.set(layout.projectDirectory.dir(if (youtubeEngine) "config/engine" else "config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
}

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
    // The process-wide Coil singleton install (08 Coil ImageLoader) needs the API, not Compose.
    implementation(platform(libs.coil.bom))
    implementation(libs.coil.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.cmp.runtime)
    implementation(libs.cmp.foundation)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
    // Metro graph aggregation reads the features' map-key annotations, and the root provides
    // LocalMetroViewModelFactory for metroViewModel()/assistedMetroViewModel().
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.acra.mail)
    implementation(libs.acra.dialog)

    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // S5's debug-only test host (`SpikeRootActivity`, app/src/debug): an application module's
    // androidTest manifest cannot host activities, so the host and its UI deps live in debug.
    debugImplementation(libs.cmp.material3)
    debugImplementation(libs.lifecycle.viewmodel.compose)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.cmp.resources)
    // S5's test-only entries call these directly; they ride on the feature modules' `implementation`
    // edges, which never reach the androidTest compile classpath.
    androidTestImplementation(libs.navigation3.runtime)
    androidTestImplementation(libs.lifecycle.viewmodel.compose)
    androidTestImplementation(libs.cmp.material3)
    // RoomRuntimeServiceDeviceTest drives the rebuilt pool directly; sqlite-bundled is an
    // `implementation` edge of :core:database and never reaches this classpath.
    androidTestImplementation(libs.androidx.sqlite.bundled)

    // S11's worker-side getString test resolves Res strings on the JVM.
    testImplementation(libs.cmp.resources)
}

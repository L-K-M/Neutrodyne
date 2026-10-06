// SPDX-License-Identifier: Unlicense
import org.gradle.api.provider.MapProperty
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension

plugins {
    alias(libs.plugins.neutrodyne.desktop.application)
    id("org.jetbrains.kotlin.plugin.serialization")
}

val youtubeEngine = providers.gradleProperty("neutrodyne.youtubeEngine").orElse("true").get().toBoolean()

dependencies {
    for (feature in listOf(
        "feeds", "library", "groups", "podcast", "episode", "player", "queue", "downloads", "discover",
        "importexport", "settings", "sync",
    )) {
        implementation(project(":feature:$feature"))
    }
    for (module in listOf(
        ":core:model", ":core:common", ":core:domain", ":core:navigation", ":core:database", ":core:datastore",
        ":core:network", ":core:data", ":core:artwork", ":core:designsystem", ":core:ui",
        ":feeds", ":playback:api", ":playback:core", ":playback:desktop", ":playback:engine", ":playback:native",
        ":desktop:system", ":download:api", ":download:impl", ":youtube:api", ":youtube:impl",
        ":sync:protocol", ":sync:api", ":sync:impl",
    )) {
        implementation(project(module))
    }
    if (youtubeEngine) implementation(project(":youtube:ytdlp-desktop"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    // Metro re-processes :core:datastore's contributed bindings when the graph resolves the
    // DataStore types, so the DataStore API must be on this module's own compile classpath.
    implementation(libs.androidx.datastore.preferences.core)
    // AllowSetForegroundWindow (ASFW_ANY) before handing off to the owner (11 Single instance)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    runtimeOnly(libs.kxml2)

    // The desktop graph test (01 Testing)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

// ---------------------------------------------------------------------------
// build-info.properties: the build-time identity resource `BuildInfoLoader` reads
// (11 DesktopAppGraph). Written from gradle.properties only — no timestamps, so the
// file is byte-identical for the same inputs (reproducible). installKind is `dev`
// here (`:desktopApp:run`, tests); the packaging pipeline overrides it (and adds
// os/arch/runtime) when it writes the resource into a packaged image.
// ---------------------------------------------------------------------------
abstract class WriteBuildInfoProperties : DefaultTask() {
    @get:Input
    abstract val values: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        // Java properties parsing, but written ourselves: stable key order, no timestamp comment.
        file.writeText(
            values.get().toSortedMap().entries.joinToString(separator = "\n", postfix = "\n") {
                "${it.key}=${it.value}"
            },
        )
    }
}

val buildInfoDir = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfoProperties = tasks.register<WriteBuildInfoProperties>("generateBuildInfoProperties") {
    values.putAll(
        mapOf(
            "versionName" to providers.gradleProperty("neutrodyne.versionName").get(),
            "versionCode" to providers.gradleProperty("neutrodyne.versionCode").get(),
            "repoUrl" to providers.gradleProperty("neutrodyne.repoUrl").get(),
            "engineManifestUrl" to providers.gradleProperty("neutrodyne.engineManifestUrl").get(),
            "youtubeEngine" to youtubeEngine.toString(),
            "installKind" to "dev",
            "acraMailto" to providers.gradleProperty("neutrodyne.acraMailto").orElse("").get(),
        ),
    )
    outputFile.convention(buildInfoDir.map { it.file("build-info.properties") })
}

sourceSets.named("main") {
    // Deriving the resource dir from the task output wires processResources → generate task.
    resources.srcDir(generateBuildInfoProperties.map { it.outputFile.get().asFile.parentFile })
}

// `-Pneutrodyne.smoke` (=true) forwards -Dneutrodyne.smoke=true to the application JVM through
// compose.desktop.application.jvmArgs (11 Smoke mode); the recommended invocation is
// `./gradlew :desktopApp:run -Pneutrodyne.smoke=true`. Packaging (a later milestone) passes the
// switch on the launcher command line instead, so no packaged image ever carries it.
val smokeRun = providers.gradleProperty("neutrodyne.smoke")
    .map { it.isBlank() || it.toBoolean() }
    .orElse(false)

extensions.getByType<ComposeExtension>()
    .let { it as ExtensionAware }
    .let { it.extensions.getByType<DesktopExtension>() }
    .application {
        if (smokeRun.get()) jvmArgs("-Dneutrodyne.smoke=true")
    }

// SPDX-License-Identifier: Unlicense
import org.gradle.api.provider.MapProperty
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.resources.ResourcesExtension

plugins {
    alias(libs.plugins.neutrodyne.desktop.application)
    // On the build classpath via build-logic, so id-only apply.
    id("com.mikepenz.aboutlibraries.plugin")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Offline and reproducible, same convention as :app (01 AboutLibraries). The plain-JVM plugin
// shape (recorded in 01 M0 checklist step 33, 2026-10-06): `exportLibraryDefinitions` writes
// aboutlibraries.json into build/generated/aboutLibraries, wired into the main resources below;
// the manual definitions (the OpenJDK runtime entry) live in config/libraries + config/licenses.
aboutLibraries {
    offlineMode.set(true)
    collect {
        configPath.set(layout.projectDirectory.dir("config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
    export {
        outputFile.set(layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json"))
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
    // The generated Res class of the shell's own Compose resources (see below)
    implementation(libs.cmp.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    // Metro re-processes :core:datastore's contributed bindings when the graph resolves the
    // DataStore types, so the DataStore API must be on this module's own compile classpath.
    implementation(libs.androidx.datastore.preferences.core)
    // Licences screen data: the desktop's own aboutlibraries.json (01 AboutLibraries)
    implementation(libs.aboutlibraries.core)
    // AllowSetForegroundWindow (ASFW_ANY) before handing off to the owner (11 Single instance)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    runtimeOnly(libs.kxml2)

    // The desktop graph test and the window-content Compose UI test (01 Testing, 11 Testing)
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.cmp.ui.test)
    // Skiko natives so runComposeUiTest can render on this machine's OS
    testImplementation(compose.desktop.currentOs)
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

// The packaging pipeline selects the image's install kind per package (11 Resources layout):
// one `createDistributable` run per kind, e.g. `:desktopApp:packageDeb
// -Pneutrodyne.installKind=deb`; unset means `dev` (run, tests).
val packaging = extensions.getByType<NeutrodyneDesktopPackagingExtension>()
val packagingInstallKind = providers.gradleProperty("neutrodyne.installKind")
if (packagingInstallKind.isPresent) {
    val kind = packagingInstallKind.get()
    val allowed =
        packaging.installKinds
            .get()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    check(kind in allowed) {
        "neutrodyne.installKind='$kind' is not one of this target's install kinds $allowed " +
            "(packaging target '${packaging.targetId.get()}'; 11 Packaging pipeline)"
    }
}

val generateBuildInfoProperties =
    tasks.register<WriteBuildInfoProperties>("generateBuildInfoProperties") {
        val installKind = packagingInstallKind.getOrElse("dev")
        values.putAll(
            buildMap {
                put("versionName", providers.gradleProperty("neutrodyne.versionName").get())
                put("versionCode", providers.gradleProperty("neutrodyne.versionCode").get())
                put("repoUrl", providers.gradleProperty("neutrodyne.repoUrl").get())
                put("engineManifestUrl", providers.gradleProperty("neutrodyne.engineManifestUrl").get())
                put("youtubeEngine", youtubeEngine.toString())
                put("installKind", installKind)
                put("acraMailto", providers.gradleProperty("neutrodyne.acraMailto").orElse("").get())
                put("podcastIndexKey", providers.gradleProperty("neutrodyne.podcastIndexKey").orElse("").get())
                put("podcastIndexSecret", providers.gradleProperty("neutrodyne.podcastIndexSecret").orElse("").get())
                // The shared shipped-locales list of both apps (09 Shipped locales; :app reads
                // the same file for its BuildConfig field).
                put(
                    "shippedLocales",
                    providers
                        .fileContents(rootProject.layout.projectDirectory.file("app/policy/locales.txt"))
                        .asText
                        .get()
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .joinToString(","),
                )
                // The packaging target's identity: os/arch/runtime of the image, absent in dev builds
                // so BuildInfoLoader derives them from the running JVM (11 DesktopAppGraph).
                if (installKind != "dev") {
                    put("os", packaging.os.get())
                    put("arch", packaging.arch.get())
                    put("runtime", packaging.runtime.get())
                }
            },
        )
        outputFile.convention(buildInfoDir.map { it.file("build-info.properties") })
    }

sourceSets.named("main") {
    // Deriving the resource dir from the task output wires processResources → generate task.
    resources.srcDir(
        generateBuildInfoProperties.map {
            it.outputFile
                .get()
                .asFile.parentFile
        },
    )
    // The AboutLibraries export lands beside it; processResources depends on the export task.
    resources.srcDir(layout.buildDirectory.dir("generated/aboutLibraries"))
}

tasks.named("processResources") { dependsOn("exportLibraryDefinitions") }

// The shell's own strings (window title, menu and tray labels — 11 Desktop UX) come from
// Compose resources like every KMP module's; the JVM plugin picks src/main/composeResources up
// but generates no Res class unless asked to.
extensions
    .getByType<ComposeExtension>()
    .let { it as ExtensionAware }
    .let { it.extensions.getByType<ResourcesExtension>() }
    .apply {
        packageOfResClass = "ch.lkmc.neutrodyne.desktop.resources"
        generateResClass = ResourcesExtension.ResourceClassGeneration.Always
    }

// `-Pneutrodyne.smoke` (=true) forwards -Dneutrodyne.smoke=true to the application JVM through
// compose.desktop.application.jvmArgs (11 Smoke mode); the recommended invocation is
// `./gradlew :desktopApp:run -Pneutrodyne.smoke=true`. Packaged images get the switch through
// their launcher `.cfg` via `scripts/desktop/smoke-start.sh` — never shipped enabled.
val smokeRun =
    providers
        .gradleProperty("neutrodyne.smoke")
        .map { it.isBlank() || it.toBoolean() }
        .orElse(false)

extensions
    .getByType<ComposeExtension>()
    .let { it as ExtensionAware }
    .let { it.extensions.getByType<DesktopExtension>() }
    .application {
        if (smokeRun.get()) jvmArgs("-Dneutrodyne.smoke=true")
    }

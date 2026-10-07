// SPDX-License-Identifier: Unlicense
import app.cash.licensee.LicenseeTask
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.io.File

/**
 * `verifyDependencyPolicy` (01 Gradle-side policy tasks): declared coordinates of every configuration against the
 * banned list of [01 Toolchain and versions], and on the shells additionally the resolved runtime classpaths plus
 * the licence SPDX values of Licensee's JSON reports. Registered in every project by its convention plugin; `check`
 * depends on it.
 */
abstract class VerifyDependencyPolicyTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    /** configuration name -> declared external coordinates "group:name:version" (version "" when not declared). */
    @get:Input
    abstract val declaredCoordinates: MapProperty<String, List<String>>

    /** configuration name -> declared `project(":x")` paths (source-set edges, rules 2 and 15). */
    @get:Input
    abstract val declaredProjectEdges: MapProperty<String, List<String>>

    /** True when the project applies `org.jetbrains.kotlin.multiplatform`: rule 15 applies to it. */
    @get:Input
    abstract val kmpModule: Property<Boolean>

    /** configuration name -> resolved root component (shells' runtime classpaths and Android test classpaths). */
    @get:Input
    abstract val resolvedRoots: MapProperty<String, ResolvedComponentResult>

    /** Licensee `artifacts.json` reports of the checked variants (shells only; empty elsewhere). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseeReports: ConfigurableFileCollection

    /**
     * Names of enabled ProGuard tasks of `:desktopApp`, snapshot after evaluation. Compose resolves ProGuard
     * (GPL-2.0) through a detached configuration inside `AbstractProguardTask` actions, so the enforceable
     * gate is that they all stay disabled; must be empty. Empty for every other project.
     */
    @get:Input
    abstract val enabledProguardTasks: ListProperty<String>

    init {
        group = "verification"
        description = "Declared and resolved dependencies against the banned list and licence policy (01)."
    }

    @TaskAction
    fun verify() {
        val path = modulePath.get()
        val problems = mutableListOf<String>()

        for ((configuration, coords) in declaredCoordinates.get().toSortedMap()) {
            for (coord in coords) {
                val rule = BANNED_COORDINATES.firstOrNull { it.matches(coord) } ?: continue
                problems += "$path configuration $configuration declares banned $coord (${rule.reason})"
            }
        }

        // Rules 2/15 on the declared project edges: `commonMain { dependencies { } }`, `by getting`
        // and `getByName().apply` are valid KMP DSL forms a build-script regex cannot isolate, so the
        // configurations themselves are checked here (checkBannedApis stays the second line).
        for ((configuration, edges) in declaredProjectEdges.get().toSortedMap()) {
            for (dep in edges) {
                val module = dep.removePrefix(":")
                if (configuration.startsWith("commonMain") && module in JVM_ISLANDS) {
                    problems +=
                        "$path configuration $configuration adds JVM island :$module " +
                        "(rule 2/14: islands only from androidMain/desktopMain/platform modules)"
                }
                if (kmpModule.get() && module in PLATFORM_ONLY_MODULES) {
                    problems +=
                        "$path configuration $configuration adds platform-only :$module to a KMP module (rule 15)"
                }
            }
        }

        for ((configuration, root) in resolvedRoots.get().toSortedMap()) {
            for (coord in allCoordinates(root)) {
                val rules = BANNED_COORDINATES + extraRules(path, configuration)
                rules.firstOrNull { it.matches(coord) }?.let { rule ->
                    problems += "$path resolved $configuration contains banned $coord (${rule.reason})"
                }
            }
        }

        for (report in licenseeReports.files.filter { it.name.endsWith(".json") }.sortedBy { it.absolutePath }) {
            for (problem in checkLicenseeReport(path, report)) problems += problem
        }

        for (taskName in enabledProguardTasks.get()) {
            problems += "$path task $taskName is enabled: Compose desktop ProGuard resolves " +
                "com.guardsquare:proguard-gradle (GPL-2.0) and must stay disabled (01 Banned, D3)"
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Dependency policy violations:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("verifyDependencyPolicy: $path clean")
    }

    private fun checkLicenseeReport(
        path: String,
        report: File,
    ): List<String> {
        val artifacts = JsonSlurper().parse(report) as? List<*> ?: return emptyList()
        val problems = mutableListOf<String>()
        for (entry in artifacts) {
            val artifact = entry as? Map<*, *> ?: continue
            val group = artifact["groupId"] as? String ?: continue
            if (group == "net.java.dev.jna") continue // JNA's dual licence is the one Gradle exception (Apache-2.0, D3)
            val name = artifact["artifactId"] as? String ?: "?"
            val ids =
                (artifact["spdxLicenses"] as? List<*>)
                    ?.mapNotNull { (it as? Map<*, *>)?.get("identifier") as? String } ?: emptyList()
            for (id in ids) {
                if (RESTRICTED_SPDX.containsMatchIn(id)) {
                    problems += "$path $group:$name declares restricted licence $id " +
                        "(GPL/AGPL/LGPL/MPL code is never a Gradle dependency, D3)"
                }
            }
            // URL-only licences land in unknownLicenses (name + url); match on both texts.
            val unknown =
                (artifact["unknownLicenses"] as? List<*>)?.mapNotNull { lic ->
                    (lic as? Map<*, *>)?.let { "${it["name"]} ${it["url"]}" }
                } ?: emptyList()
            for (label in unknown) {
                if (RESTRICTED_SPDX.containsMatchIn(label)) {
                    problems += "$path $group:$name declares restricted licence '$label' " +
                        "(GPL/AGPL/LGPL/MPL code is never a Gradle dependency, D3)"
                }
            }
        }
        return problems
    }

    /** Per-configuration resolved rules on top of the global banned list (01 Gradle-side policy tasks). */
    private fun extraRules(
        path: String,
        configuration: String,
    ): List<BannedCoordinate> {
        val rules = mutableListOf<BannedCoordinate>()
        if (path == ":app" && configuration == "releaseRuntimeClasspath") {
            rules += BannedCoordinate("com.squareup.leakcanary", "*", "LeakCanary is debug-only")
            rules +=
                BannedCoordinate(
                    "androidx.compose.ui",
                    "ui-tooling",
                    "debug tooling; ui-tooling-preview is allowed",
                )
            rules +=
                BannedCoordinate(
                    "androidx.compose.ui",
                    "ui-test-manifest",
                    "test manifest is never on the release classpath",
                )
        }
        val lower = configuration.lowercase()
        if ((lower.contains("androidtest") || lower.contains("devicetest")) && lower.endsWith("runtimeclasspath")) {
            rules += BannedCoordinate("io.mockk", "*", "no MockK on devices (09 Test infrastructure)")
        }
        return rules
    }

    /** Every "group:name:version" of resolved external components plus attempted selectors that failed to resolve. */
    private fun allCoordinates(root: ResolvedComponentResult): Set<String> {
        val seen = LinkedHashSet<String>()
        val visited = LinkedHashSet<ResolvedComponentResult>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue += root
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component)) continue
            for (dependency in component.dependencies) {
                when (dependency) {
                    is ResolvedDependencyResult -> {
                        val id = dependency.selected.id
                        if (id is ModuleComponentIdentifier) {
                            seen += "${id.group}:${id.module}:${id.version}"
                            queue += dependency.selected
                        } else {
                            queue += dependency.selected // project/module-internal nodes may carry external children
                        }
                    }

                    is UnresolvedDependencyResult -> {
                        val attempted = dependency.attempted
                        if (attempted is ModuleComponentSelector) {
                            seen += "${attempted.group}:${attempted.module}:${attempted.version}"
                        } else {
                            seen += attempted.displayName
                        }
                    }
                }
            }
        }
        return seen
    }
}

/** A banned coordinate: `*` is a glob over `group:name` (a version is never part of the ban). */
internal class BannedCoordinate(
    groupPattern: String,
    namePattern: String,
    val reason: String,
) {
    private val group = Regex(glob(groupPattern))
    private val name = Regex(glob(namePattern))

    private fun glob(pattern: String) = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$"

    fun matches(coordinate: String): Boolean {
        val parts = coordinate.split(":")
        if (parts.size < 2) return false
        return group.matches(parts[0]) && name.matches(parts[1])
    }
}

/** The banned coordinates of [01 Toolchain and versions] "Banned". Version-free: any version is banned. */
internal val BANNED_COORDINATES: List<BannedCoordinate> =
    listOf(
        BannedCoordinate(
            "androidx.compose.material",
            "material-icons-extended",
            "icon set is oversized; compose-bom icons",
        ),
        BannedCoordinate("androidx.palette", "*", "material-color-utilities instead (D57)"),
        BannedCoordinate(
            "com.materialkolor",
            "material-kolor*",
            "the Compose artifact is com.materialkolor:material-color-utilities",
        ),
        BannedCoordinate("com.google.android.gms", "*", "no proprietary Google services SDK (D62)"),
        BannedCoordinate("com.google.android.gms.*", "*", "no proprietary Google services SDK (D62)"),
        BannedCoordinate("com.google.firebase", "*", "no proprietary Google services SDK (D62)"),
        BannedCoordinate("com.google.firebase.*", "*", "no proprietary Google services SDK (D62)"),
        BannedCoordinate("com.google.android.play", "*", "no Play Core (no in-app install/update, D78)"),
        BannedCoordinate("com.google.android.play.*", "*", "no Play Core (no in-app install/update, D78)"),
        BannedCoordinate("com.crashlytics*", "*", "no Crashlytics (D62)"),
        BannedCoordinate("io.sentry*", "*", "no Sentry SDK (D62)"),
        BannedCoordinate("androidx.security", "security-crypto", "deprecated; Keystore is used directly"),
        BannedCoordinate("com.google.dagger", "*", "Metro replaces Hilt/Dagger (D82)"),
        BannedCoordinate("androidx.hilt", "*", "Metro replaces Hilt (D82)"),
        BannedCoordinate("dev.dirs", "directories", "MPL-2.0 code; AppDirs is ours"),
        BannedCoordinate("com.github.teamnewpipe", "*", "NewPipe Extractor is banned; yt-dlp instead"),
        BannedCoordinate("com.github.TeamNewPipe", "*", "NewPipe Extractor is banned; yt-dlp instead"),
        BannedCoordinate("org.mozilla", "rhino*", "NewPipe-era JS engine; never used"),
        BannedCoordinate("*", "*youtubedl-android*", "yt-dlp is the only YouTube extractor"),
        BannedCoordinate("com.android.tools", "desugar_jdk_libs*", "java.time is native from API 26"),
        BannedCoordinate("com.guardsquare", "proguard*", "GPL-2.0 (D3)"),
        BannedCoordinate("org.openjdk", "jextract*", "GPL-2.0; FFM bindings are hand-written"),
        BannedCoordinate("org.openjfx", "javafx-media", "media comes from Media3/FFmpeg"),
        BannedCoordinate("org.bytedeco", "*-gpl", "GPL build of JavaCPP (D3)"),
        BannedCoordinate("uk.co.caprica", "vlcj*", "GPL-3.0 (D3)"),
        BannedCoordinate("org.freedesktop.gstreamer", "*", "GStreamer desktop media is not used"),
        BannedCoordinate("*", "gst1-java-core", "GStreamer desktop media is not used"),
        BannedCoordinate("ch.qos.logback", "*", "slf4j-simple instead"),
        BannedCoordinate("de.mkammerer", "argon2-jvm", "LGPL; Bouncy Castle instead"),
        BannedCoordinate("org.mariadb.jdbc", "*", "LGPL; SQLite is the store"),
    )

/**
 * Restricted licences in any form Licensee reports them: SPDX ids (`GPL-3.0`, `LGPL-2.1-or-later`,
 * `MPL-2.0`) and the spelled-out names URL-only POM licences land in `unknownLicenses` as
 * ("GNU Lesser General Public Licence").
 */
internal val RESTRICTED_SPDX =
    Regex("\\b(AGPL|LGPL|GPL|MPL)-|GNU (Lesser|General|Affero)|General Public Licen[cs]e|Affero|Mozilla Public")

/**
 * Registers `verifyDependencyPolicy` (idempotent — the kmp.compose/kmp.feature/desktop.native wrappers delegate
 * to the base convention plugins) and wires it into `check`.
 */
internal fun Project.registerDependencyPolicy() {
    if (tasks.names.contains("verifyDependencyPolicy")) return
    val policy =
        tasks.register<VerifyDependencyPolicyTask>("verifyDependencyPolicy") {
            modulePath.set(path)
            resolvedRoots.putAll(emptyMap())
            licenseeReports.setFrom(emptyList<Any>())
            enabledProguardTasks.convention(emptyList())
        }

    // Declared coordinates are read once the build script has finished; nothing is resolved at configuration time.
    afterEvaluate {
        val declared = mutableMapOf<String, MutableList<String>>()
        val projectEdges = mutableMapOf<String, MutableList<String>>()
        configurations.forEach { configuration ->
            val deps =
                configuration.dependencies
                    .filterIsInstance<ExternalDependency>()
                    .map { "${it.group}:${it.name}:${it.version.orEmpty()}" }
            val constraints =
                configuration.dependencyConstraints
                    .map { "${it.group}:${it.name}:${it.version.orEmpty()}" }
            val coords = deps + constraints
            if (coords.isNotEmpty()) declared.getOrPut(configuration.name) { mutableListOf() } += coords
            val edges =
                configuration.dependencies
                    .filterIsInstance<ProjectDependency>()
                    .map { it.path }
            if (edges.isNotEmpty()) projectEdges.getOrPut(configuration.name) { mutableListOf() } += edges
        }
        policy.configure {
            declaredCoordinates.set(declared.mapValues { it.value.sorted() })
            declaredProjectEdges.set(projectEdges.mapValues { it.value.sorted().distinct() })
            kmpModule.set(pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform"))
        }

        // Resolved runtime classpaths of the shells plus every *AndroidTestRuntimeClasspath (the MockK ban).
        configurations.forEach { configuration ->
            val name = configuration.name
            val isShellRuntime =
                when (path) {
                    ":app" -> name == "releaseRuntimeClasspath" || name == "benchmarkReleaseRuntimeClasspath"
                    ":desktopApp", ":sync:server" -> name == "runtimeClasspath"
                    else -> false
                }
            val lower = name.lowercase()
            val isDeviceTest =
                (lower.contains("androidtest") || lower.contains("devicetest")) &&
                    lower.endsWith("runtimeclasspath")
            if (isShellRuntime || isDeviceTest) {
                policy.configure {
                    resolvedRoots.put(name, configuration.incoming.resolutionResult.rootComponent)
                }
            }
        }

        if (path == ":desktopApp") {
            // See VerifyDependencyPolicyTask.enabledProguardTasks: the detached ProGuard resolution only
            // ever runs inside AbstractProguardTask actions, so keeping every one disabled (via
            // release.proguard.isEnabled = false) is the enforceable gate.
            val enabled = tasks.matching { it is AbstractProguardTask }.filter { it.enabled }.map { it.name }
            policy.configure { enabledProguardTasks.set(enabled.sorted()) }
        }
    }

    // Licensee's JSON reports become task inputs wherever the plugin is applied (the three shells). A lazily
    // filtered collection keeps this configuration-cache safe and avoids cross-task configuration.
    pluginManager.withPlugin("app.cash.licensee") {
        val licenseeTaskNames = setOf("licenseeAndroidRelease", "licenseeAndroidBenchmarkRelease", "licensee")
        val licenseeTasks = tasks.withType<LicenseeTask>().matching { it.name in licenseeTaskNames }
        policy.configure {
            dependsOn(licenseeTasks)
            licenseeReports.from(licenseeTasks)
        }
    }

    tasks.matching { it.name == "check" }.configureEach { dependsOn(policy) }
}

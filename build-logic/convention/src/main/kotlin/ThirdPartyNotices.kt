// SPDX-License-Identifier: Unlicense
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/** Where the server JAR carries its notices (README Licence section, ci.yml assemble checks). */
internal const val THIRD_PARTY_NOTICES_PATH = "META-INF/Third-Party-Notices.txt"

/**
 * Writes the notices a fat JAR must carry for the third-party code it bundles (01 Licensing):
 *
 * - `META-INF/Third-Party-Notices.txt`, rendered from the AboutLibraries definitions of the
 *   runtime classpath plus the module's `config/` overrides: one entry per component with its
 *   version, SPDX licences, source URL and component-specific texts (copyright-bearing licence
 *   files, NOTICE contents), then each licence text that several components share, once.
 * - `META-INF/licenses/<group>/<artifact>/...`: every LICENSE, NOTICE and COPYING file the
 *   dependency jars embed, verbatim, because their identical names would otherwise collide in
 *   the fat JAR and only the first would survive.
 *
 * Fails, rather than ship incomplete notices, when a component has no licence, a licence has no
 * text, or an MIT/BSD-style licence has only a template text without the component's own
 * copyright line (add the component's text to `config/licenses` and `config/libraries`).
 */
@CacheableTask
abstract class ThirdPartyNoticesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val definitions: RegularFileProperty

    /** Resolved external jars of the runtime classpath, keyed by file name: `group:artifact`. */
    @get:Input
    abstract val coordinatesByFileName: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "build"
        description = "Writes META-INF/Third-Party-Notices.txt and the dependencies' licence files (01)."
    }

    @TaskAction
    fun write() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        val notices = renderThirdPartyNotices(definitions.get().asFile)
        File(out, THIRD_PARTY_NOTICES_PATH).apply {
            parentFile.mkdirs()
            writeText(notices)
        }
        copyEmbeddedLicenceFiles(out)
    }

    private fun copyEmbeddedLicenceFiles(out: File) {
        val coordinates = coordinatesByFileName.get()
        for (jar in artifactFiles.files.filter { it.isFile && it.name.endsWith(".jar") }.sortedBy { it.name }) {
            val coordinate = coordinates[jar.name] ?: continue
            val (group, artifact) = coordinate.split(':')
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && EMBEDDED_LICENCE_FILE.matches(it.name) }
                    .forEach { entry ->
                        // The entry name comes from a third-party jar: it must not climb out of
                        // the output directory (java.io.File also splits on backslashes on Windows).
                        if (entry.name.split('/', '\\').any { it == ".." }) {
                            throw GradleException("${jar.name}: licence entry '${entry.name}' leaves META-INF")
                        }
                        val target =
                            File(out, "META-INF/licenses/$group/$artifact/${entry.name.removePrefix("META-INF/")}")
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
            }
        }
    }

    private companion object {
        val EMBEDDED_LICENCE_FILE = Regex("""(?i)META-INF/(?:[^/]+/)*(?:LICEN[CS]E|NOTICE|COPYING)[^/]*""")
    }
}

/**
 * Wires the resolved external jars of [configuration] (`runtimeClasspath` by default) into a task:
 * [files] gets the jars, [coordinates] maps each file name to its `group:artifact`. Project
 * dependencies are our own code and get no coordinate.
 */
fun Project.wireExternalArtifacts(
    files: ConfigurableFileCollection,
    coordinates: MapProperty<String, String>,
    configuration: String = "runtimeClasspath",
) {
    val artifacts = configurations.named(configuration).map { it.incoming.artifacts }
    files.from(artifacts.map { it.artifactFiles })
    coordinates.set(
        artifacts.flatMap { it.resolvedArtifacts }.map { results ->
            results
                .mapNotNull { result ->
                    val id = result.id.componentIdentifier as? ModuleComponentIdentifier
                    id?.let { result.file.name to "${it.group}:${it.module}" }
                }.toMap()
        },
    )
}

/** MIT- and BSD-style families: binary redistribution needs the component's own copyright line. */
private val COPYRIGHT_FAMILIES = setOf("MIT", "X11", "ISC", "BSD-2-Clause", "BSD-3-Clause", "Unicode-3.0")
private val TEMPLATE_PLACEHOLDERS = listOf("<year>", "<copyright holders>", "<owner>")
private val COPYRIGHT_LINE = Regex("""(?im)^\s*(copyright|\(c\)|©)\s""")
private const val RULE = "========================================================================"

/** Renders the AboutLibraries export [definitionsFile] as plain, sorted, deterministic text. */
internal fun renderThirdPartyNotices(definitionsFile: File): String {
    val json = JsonSlurper().parse(definitionsFile) as Map<*, *>
    val licences = (json["licenses"] as? Map<*, *>).orEmpty().mapKeys { it.key.toString() }
    val libraries = (json["libraries"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    val problems = mutableListOf<String>()

    fun licence(key: String) = licences[key] as? Map<*, *>

    fun content(key: String) =
        licence(key)
            ?.get("content")
            ?.toString()
            .orEmpty()
            .trim()

    fun family(key: String) = licence(key)?.get("spdxId")?.toString()?.takeIf { it.isNotBlank() } ?: key

    fun isTemplate(text: String) = TEMPLATE_PLACEHOLDERS.any { text.contains(it) }

    val usage =
        libraries
            .flatMap { lib ->
                (lib["licenses"] as? List<*>).orEmpty().map {
                    it.toString()
                }
            }.groupingBy { it }
            .eachCount()
    val shared = sortedSetOf<String>()
    val entries =
        libraries
            .sortedWith(compareBy({ it["name"]?.toString()?.lowercase() }, { it["uniqueId"]?.toString() }))
            .map { lib ->
                val id = lib["uniqueId"].toString()
                val keys = (lib["licenses"] as? List<*>).orEmpty().map { it.toString() }.sorted()
                if (keys.isEmpty()) problems += "$id: no licence"
                // Per licence family, the texts worth shipping: non-blank and not an SPDX template.
                val texts =
                    keys.groupBy(::family).toSortedMap().mapValues { (fam, famKeys) ->
                        val usable = famKeys.filter { content(it).isNotEmpty() && !isTemplate(content(it)) }
                        when {
                            usable.isEmpty() -> {
                                problems +=
                                    "$id: no $fam licence text without template placeholders (${famKeys.joinToString()})"
                            }

                            fam in COPYRIGHT_FAMILIES &&
                                usable.none {
                                    COPYRIGHT_LINE.containsMatchIn(
                                        content(it),
                                    )
                                }
                            -> {
                                problems +=
                                    "$id: no $fam text with the component's copyright line (${famKeys.joinToString()})"
                            }
                        }
                        usable
                    }
                buildString {
                    appendLine(RULE)
                    appendLine(
                        listOfNotNull(
                            lib["name"]?.toString() ?: id,
                            lib["artifactVersion"]?.toString(),
                        ).joinToString(" "),
                    )
                    appendLine(id)
                    appendLine("License: ${texts.keys.joinToString(" AND ")}")
                    lib["website"]?.toString()?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    for (key in texts.values.flatten()) {
                        if (usage.getValue(key) > 1) {
                            shared += key
                            appendLine(
                                "Licence text: ${licence(key)?.get("name") ?: key}, under \"Licence texts\" below",
                            )
                        } else {
                            appendLine()
                            appendLine(content(key))
                        }
                    }
                    appendLine()
                }
            }
    if (problems.isNotEmpty()) {
        throw GradleException(
            "Third-party notices are incomplete (${definitionsFile.name}):\n" +
                problems.joinToString("\n") { "  - $it" },
        )
    }
    return buildString {
        appendLine("Third-party notices")
        appendLine()
        appendLine("This file lists the third-party components bundled in this artifact, with their licences.")
        appendLine("Neutrodyne's own code is released under the Unlicense; the components keep their own licences.")
        appendLine()
        entries.forEach(::append)
        appendLine(RULE)
        appendLine("Licence texts")
        for (key in shared) {
            appendLine(RULE)
            appendLine(licence(key)?.get("name") ?: key)
            appendLine()
            appendLine(content(key))
            appendLine()
        }
    }
}

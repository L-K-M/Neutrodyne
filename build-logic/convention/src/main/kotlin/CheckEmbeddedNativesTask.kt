// SPDX-License-Identifier: Unlicense
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * `checkEmbeddedNatives`, the embedded-native inventory of 01 Licensing: native libraries inside
 * runtime-classpath JARs (Skiko's Skia build, JNA's libjnidispatch, sqlite-bundled's JNI library)
 * carry third-party code their POM licences do not describe. Every JAR that ships one needs a
 * rule naming the AboutLibraries entries for what it links, and each entry must be in the
 * generated definitions with licence text. A new native-carrying dependency fails until its
 * notices are added.
 */
abstract class CheckEmbeddedNativesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val definitions: RegularFileProperty

    /** Resolved external jars of the runtime classpath, keyed by file name: `group:artifact`. */
    @get:Input
    abstract val coordinatesByFileName: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifactFiles: ConfigurableFileCollection

    /** `group:artifact` pattern (`*` matches any run of characters) to the uniqueIds it needs. */
    @get:Input
    abstract val rules: MapProperty<String, List<String>>

    init {
        group = "verification"
        description = "Every runtime JAR with native libraries has notice entries for what they link (01)."
    }

    @TaskAction
    fun check() {
        val json = JsonSlurper().parse(definitions.get().asFile) as Map<*, *>
        val licences = (json["licenses"] as? Map<*, *>).orEmpty()
        val covered =
            (json["libraries"] as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
                .filter { lib ->
                    val keys = (lib["licenses"] as? List<*>).orEmpty()
                    keys.isNotEmpty() &&
                        keys.all { (licences[it] as? Map<*, *>)?.get("content")?.toString()?.isNotBlank() == true }
                }.map { it["uniqueId"].toString() }
                .toSet()
        val patterns =
            rules.get().mapKeys { (pattern, _) ->
                Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
            }
        val coordinates = coordinatesByFileName.get()
        val problems = mutableListOf<String>()
        var checked = 0

        for (jar in artifactFiles.files.filter { it.isFile && it.name.endsWith(".jar") }.sortedBy { it.name }) {
            val coordinate = coordinates[jar.name] ?: continue
            val natives =
                ZipFile(jar).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .filter(NATIVE_LIBRARY::matches)
                        .toList()
                }
            if (natives.isEmpty()) continue
            checked++
            val required = patterns.entries.firstOrNull { it.key.matches(coordinate) }?.value
            if (required == null) {
                problems +=
                    "$coordinate ships native libraries (${natives.take(
                        3,
                    ).joinToString()}) but has no checkEmbeddedNatives rule"
                continue
            }
            required.filter { it !in covered }.forEach {
                problems +=
                    "$coordinate needs the notice entry '$it' (missing from the definitions or without licence text)"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Embedded native libraries without notices:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("checkEmbeddedNatives: $checked runtime JAR(s) with native libraries, all covered")
    }

    private companion object {
        val NATIVE_LIBRARY = Regex("""(?i).*\.(so|dll|dylib|jnilib|a)""")
    }
}

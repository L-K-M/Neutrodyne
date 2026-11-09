// SPDX-License-Identifier: Unlicense
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

/**
 * One committed profile capture: the merged `baseline-prof.txt` or `startup-prof.txt`, held as a
 * deterministic gzip blob next to `capture-receipt.properties` (09 Macrobenchmark and profiles).
 * The receipt is the integrity authority — the task below fails closed when any field disagrees.
 */
internal data class ProfileCapture(
    val name: String,
    val bytes: Long,
    val lines: Long,
    val sha256: String,
    val gzipBytes: Long,
    val gzipSha256: String,
)

/**
 * Reads `capture-receipt.properties` (strictly: only the expected keys, the two fixed capture
 * names, positive numbers and lowercase hex SHA-256s) and inflates a capture, verifying the
 * compressed blob and the raw profile text against every receipt field.
 */
internal object ProfileCaptureReceipt {
    /** The fixed merged-profile file names the release build consumes (AGP's canonical pair). */
    val EXPECTED_NAMES = listOf("baseline-prof.txt", "startup-prof.txt")

    private const val SOURCE_COMMIT = "source_commit"
    private const val GENERATION_RUN = "generation_run"
    private val NUMERIC_FIELDS = listOf("bytes", "lines", "gzip_bytes")
    private val HASH_FIELDS = listOf("sha256", "gzip_sha256")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun read(receipt: File): List<ProfileCapture> {
        val properties =
            try {
                receipt.inputStream().use { Properties().apply { load(it) } }
            } catch (e: IOException) {
                throw GradleException("cannot read profile capture receipt $receipt: ${e.message}", e)
            }
        val expectedKeys =
            setOf(SOURCE_COMMIT, GENERATION_RUN) +
                EXPECTED_NAMES.flatMap { name -> (NUMERIC_FIELDS + HASH_FIELDS).map { "$name.$it" } }
        val problems = mutableListOf<String>()
        for (key in properties.stringPropertyNames().sorted()) {
            if (key !in expectedKeys) problems += "unexpected key '$key'"
        }
        for (key in expectedKeys.sorted()) {
            if (properties.getProperty(key) == null) problems += "missing key '$key'"
        }
        if (problems.isNotEmpty()) {
            throw GradleException("profile capture receipt $receipt: " + problems.joinToString("; "))
        }
        if (properties.getProperty(SOURCE_COMMIT).isNullOrBlank()) {
            problems += "'$SOURCE_COMMIT' is blank"
        }
        if (properties.getProperty(GENERATION_RUN)?.toLongOrNull() == null) {
            problems += "'$GENERATION_RUN' is not a number"
        }
        val captures =
            EXPECTED_NAMES.map { name ->
                fun number(field: String): Long =
                    properties.getProperty("$name.$field")?.toLongOrNull()?.takeIf { it > 0 }
                        ?: -1L.also { problems += "'$name.$field' is not a positive number" }

                fun hash(field: String): String =
                    properties.getProperty("$name.$field")?.takeIf { SHA256_HEX.matches(it) }
                        ?: "".also { problems += "'$name.$field' is not a lowercase sha256 hex" }

                ProfileCapture(
                    name = name,
                    bytes = number("bytes"),
                    lines = number("lines"),
                    sha256 = hash("sha256"),
                    gzipBytes = number("gzip_bytes"),
                    gzipSha256 = hash("gzip_sha256"),
                )
            }
        if (problems.isNotEmpty()) {
            throw GradleException("profile capture receipt $receipt: " + problems.joinToString("; "))
        }
        return captures
    }

    /** Inflates `capture`'s `.gz`, verifying compressed bytes, raw bytes and the line count. */
    fun inflate(
        gzip: File,
        capture: ProfileCapture,
    ): ByteArray {
        val compressed = gzip.readBytes()
        checkField(gzip.name, "gzip_bytes", "${compressed.size}", "${capture.gzipBytes}")
        checkField(gzip.name, "gzip_sha256", sha256(compressed), capture.gzipSha256)
        val raw =
            try {
                GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
            } catch (e: IOException) {
                throw GradleException("corrupt gzip capture ${gzip.name}: ${e.message}", e)
            }
        checkField(gzip.name, "bytes", "${raw.size}", "${capture.bytes}")
        checkField(gzip.name, "sha256", sha256(raw), capture.sha256)
        checkField(gzip.name, "lines", "${lineCount(raw)}", "${capture.lines}")
        return raw
    }

    /** A profile's last line need not be newline-terminated; count it as a line either way. */
    fun lineCount(raw: ByteArray): Long =
        raw.count { it == '\n'.code.toByte() }.toLong() +
            if (raw.isNotEmpty() && raw.last() != '\n'.code.toByte()) 1 else 0

    private fun checkField(
        file: String,
        field: String,
        actual: String,
        expected: String,
    ) {
        if (actual != expected) {
            throw GradleException(
                "profile capture $file fails the receipt's $field check (expected $expected, got $actual)",
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Inflates the committed gzip captures under `build/generated/` and exposes the directory as a
 * `baselineProfiles` source to `release`/`benchmarkRelease` via AGP's
 * `Sources.baselineProfiles.addGeneratedSourceDirectory` — the same public source-set API the
 * consumer plugin itself uses. Never writes into `app/src/release/generated/baselineProfiles/`:
 * that directory stays the producer's (`saveInSrc`) regeneration output.
 */
@CacheableTask
abstract class InflateBaselineProfileCapturesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val receipt: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val captures: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "baseline profile"
        description =
            "Inflates the committed baseline/startup profile captures, verifying them " +
            "against capture-receipt.properties (09 Macrobenchmark and profiles)"
    }

    @TaskAction
    fun inflate() {
        val specs = ProfileCaptureReceipt.read(receipt.get().asFile)
        val byName = captures.files.associateBy { it.name }
        val expectedGz = specs.map { "${it.name}.gz" }.toSet()
        if (byName.keys != expectedGz) {
            throw GradleException(
                "profile capture inputs ${byName.keys.sorted()} do not match the receipt's " +
                    "expected set ${expectedGz.sorted()}",
            )
        }

        // Verify every input before writing anything: a failed check leaves no partial output.
        val raw = specs.associate { it.name to ProfileCaptureReceipt.inflate(byName.getValue("${it.name}.gz"), it) }
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        specs.forEach { dir.resolve(it.name).writeBytes(raw.getValue(it.name)) }
        logger.lifecycle(
            "inflated ${specs.size} profile captures into $dir " +
                "(${specs.joinToString { "${it.name}: ${it.bytes} bytes" }})",
        )
    }
}

/** Wires `:app`'s committed captures into AGP's baseline-profile pipeline (09). */
internal fun Project.registerBaselineProfileCaptures() {
    val captureDir = layout.projectDirectory.dir("profiles")
    if (!captureDir.file("capture-receipt.properties").asFile.isFile) return
    val inflate =
        tasks.register<InflateBaselineProfileCapturesTask>("inflateBaselineProfileCaptures") {
            receipt.set(captureDir.file("capture-receipt.properties"))
            captures.from(captureDir.asFileTree.matching { include("*.txt.gz") })
            outputDir.set(layout.buildDirectory.dir("generated/profileCaptures"))
        }

    extensions.configure<ApplicationAndroidComponentsExtension> {
        fun wire(variant: ApplicationVariant) {
            @Suppress("UnstableApiUsage")
            val baselineProfiles =
                checkNotNull(variant.sources.baselineProfiles) {
                    "AGP exposes no baselineProfiles source set on ${variant.name}; " +
                        "the committed profile captures cannot be wired into the build"
                }
            baselineProfiles.addGeneratedSourceDirectory(inflate, InflateBaselineProfileCapturesTask::outputDir)
        }

        // The consumer variant ships the profile and its benchmark variant asserts it
        // (BaselineProfileMode.Require) — the same pair saveInSrc registers for its src dir.
        onVariants(selector().withBuildType("release")) { wire(it) }
        onVariants(selector().withBuildType("benchmarkRelease")) { wire(it) }
    }
}

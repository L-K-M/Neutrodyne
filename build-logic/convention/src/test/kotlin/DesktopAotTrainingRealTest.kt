// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The real producer boundary of the PO-42 AOT training fix (11 AOT cache): a minimal
 * generated jpackage app image — no GUI, no user data — whose runtime carries no `bin/java`
 * (jlink's `--strip-native-commands`). The image launcher alone exits 0 but writes no
 * archive (the defect); [TrainAotCacheTask] provisions the pinned runtime's own launcher,
 * launches the image, leaves the archive, proves it under strict mode and removes the copy
 * again on success and on a failed launch.
 *
 * The test needs a JDK 25 with `jdk.jpackage` (the CI static job and the desktop matrix
 * install one); it fails loudly when none is found rather than skipping the boundary.
 */
class DesktopAotTrainingRealTest {
    private val jdk25: File =
        requireNotNull(jdk25Home()) {
            "no JDK 25 with bin/jpackage and bin/javac found for the real AOT probe"
        }

    private val osWire: String = hostOsWire()

    private val installKind: String =
        when (osWire) {
            "windows" -> "zip"
            "macos" -> "dmg"
            else -> "tar.gz"
        }

    private val expectedInstallKinds: List<String> =
        when (osWire) {
            "windows" -> listOf("msi", "zip")
            "macos" -> listOf("dmg", "mac-zip")
            else -> listOf("deb", "rpm", "tar.gz")
        }

    private val launcherRelative: String =
        when (osWire) {
            "windows" -> "Neutrodyne.exe"
            "macos" -> "Contents/MacOS/Neutrodyne"
            else -> "bin/Neutrodyne"
        }

    private val cfgRelative: String =
        when (osWire) {
            "windows" -> "app/Neutrodyne.cfg"
            "macos" -> "Contents/app/Neutrodyne.cfg"
            else -> "lib/app/Neutrodyne.cfg"
        }

    private val appDirRelative: String =
        when (osWire) {
            "windows" -> "app"
            "macos" -> "Contents/app"
            else -> "lib/app"
        }

    private val runtimeReleaseRelative: String =
        when (osWire) {
            "windows" -> "runtime/release"
            "macos" -> "Contents/runtime/Contents/Home/release"
            else -> "lib/runtime/release"
        }

    private val runtimeJavaRelative: String =
        when (osWire) {
            "windows" -> "runtime/bin/java.exe"
            "macos" -> "Contents/runtime/Contents/Home/bin/java"
            else -> "lib/runtime/bin/java"
        }

    private val runtimeBinRelative: String =
        runtimeJavaRelative.substringBeforeLast('/')

    /**
     * RED: the image launcher alone cannot write the archive. The image runtime has no
     * `bin/java`, so the JVM's assembly-phase fork fails at shutdown — the launcher still
     * exits 0, which is exactly why the task must provision the pinned launcher.
     */
    @Test
    fun `the image launcher alone writes no archive`() {
        val image = tinyImage(MAIN_BODY)
        val cfg = File(image, cfgRelative)
        val archive = File(image, "$appDirRelative/neutrodyne.aot")
        assertFalse(File(image, runtimeJavaRelative).exists(), "the image runtime must start without java")
        val original = cfg.readBytes()
        cfg.writeBytes(aotTrainingCfg(String(original, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
        try {
            val result = launchImage(image)
            assertEquals(0, result.exit, "the launcher exits 0 even though the archive cannot be written")
            assertFalse(archive.exists(), "no archive can be written without a runtime java launcher")
        } finally {
            cfg.writeBytes(original)
        }
    }

    /**
     * GREEN: the task provisions the pinned launcher, trains the archive, proves it with a
     * strict load and removes the temporary launcher and the `bin` directory it created.
     */
    @Test
    fun `the task trains the archive and removes the temporary java`() {
        val image = tinyImage(MAIN_BODY)
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()

        trainTask(image).train()

        val archive = File(image, "$appDirRelative/neutrodyne.aot")
        assertTrue(archive.isFile && archive.length() > 0L, "the training launch must leave the archive")
        assertFalse(File(image, runtimeJavaRelative).exists(), "the copied launcher must be removed")
        assertFalse(
            File(image, runtimeBinRelative).exists(),
            "the bin directory the provisioning created must be removed",
        )
        assertEquals(original.toList(), cfg.readBytes().toList(), "the .cfg is restored byte-exact")
    }

    /** The temporary launcher comes out again when the training launch fails. */
    @Test
    fun `a failed training launch still removes the temporary java`() {
        val image = tinyImage("System.exit(3);")
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()

        assertFailsWith<GradleException> { trainTask(image).train() }

        assertFalse(File(image, runtimeJavaRelative).exists())
        assertFalse(File(image, runtimeBinRelative).exists())
        assertEquals(original.toList(), cfg.readBytes().toList(), "the .cfg is restored on failure")
    }

    /**
     * RED: the real launcher with a highest-precedence `_JAVA_OPTIONS` runs ordinary main
     * even though the swapped .cfg carries the training mode. The marker proves main ran;
     * this is the defect the prelaunch admission fixes.
     */
    @Test
    fun `the hostile underscore option reached main before the admission fix`() {
        val image = tinyImage(MARKER_MAIN)
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()
        val marker = File(image, "ordinary-main.marker")
        cfg.writeBytes(aotTrainingCfg(String(original, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
        try {
            val result =
                launchImage(
                    image,
                    mapOf(
                        "_JAVA_OPTIONS" to
                            "-D$AOT_TRAINING_PROPERTY=false -Dneutrodyne.probe.marker=${marker.absolutePath}",
                    ),
                )
            assertEquals(0, result.exit, result.output)
            assertTrue(marker.isFile, "ordinary main ran despite the hostile _JAVA_OPTIONS (the defect)")
            assertTrue(result.output.contains("ORDINARY MAIN"), result.output)
        } finally {
            cfg.writeBytes(original)
        }
    }

    /** NEW: the task refuses to launch when `_JAVA_OPTIONS` would override the training mode. */
    @Test
    fun `the task rejects a hostile underscore option before launching main`() {
        val image = tinyImage(MARKER_MAIN)
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()
        val marker = File(image, "ordinary-main.marker")
        val environment =
            System.getenv() +
                mapOf(
                    "_JAVA_OPTIONS" to
                        "-D$AOT_TRAINING_PROPERTY=false -Dneutrodyne.probe.marker=${marker.absolutePath}",
                )
        assertFailsWith<GradleException> { trainTask(image).train(environment) }
        assertFalse(marker.exists(), "the launcher must not run ordinary main")
        assertEquals(original.toList(), cfg.readBytes().toList(), "the .cfg is untouched")
    }

    /** NEW: the strict smoke launch is refused when `_JAVA_OPTIONS` would override smoke mode. */
    @Test
    fun `the task rejects a hostile smoke override before the strict launch`() {
        val image = tinyImage(MARKER_MAIN)
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()
        val marker = File(image, "ordinary-main.marker")
        val environment =
            System.getenv() +
                mapOf(
                    "_JAVA_OPTIONS" to
                        "-D$SMOKE_PROPERTY=false -Dneutrodyne.probe.marker=${marker.absolutePath}",
                )
        assertFailsWith<GradleException> { trainTask(image).train(environment) }
        assertFalse(marker.exists(), "the strict launch must not run ordinary main")
        assertEquals(original.toList(), cfg.readBytes().toList(), "the .cfg is restored")
    }

    /** GREEN: unrelated `_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS` survive and both modes run. */
    @Test
    fun `harmless underscore and tool options let the task train and verify`() {
        val image = tinyImage(MARKER_MAIN)
        val cfg = File(image, cfgRelative)
        val original = cfg.readBytes()
        val marker = File(image, "ordinary-main.marker")
        val environment =
            System.getenv() +
                mapOf(
                    "_JAVA_OPTIONS" to "-Xmx128m -Dneutrodyne.probe.marker=${marker.absolutePath}",
                    "JAVA_TOOL_OPTIONS" to "-D$SMOKE_PROPERTY=false",
                )
        trainTask(image).train(environment)
        assertFalse(marker.exists(), "both launches must run their required modes")
        assertEquals(original.toList(), cfg.readBytes().toList(), "the .cfg is restored")
    }

    /**
     * The timestamp round trip: the normalized image is packaged the way `packageZip` does
     * after `trainAotCache` (`isPreserveFileTimestamps = true`), extracted, and the strict
     * cache load must still prove the archive was used.
     */
    @Test
    fun `the packaged and extracted image keeps the strict cache usable`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val archive = File(image, "$appDirRelative/neutrodyne.aot")
        assertTrue(archive.isFile && archive.length() > 0L, "the training launch must leave the archive")

        val packaged = packageImage(image)
        try {
            val extracted = extractImage(packaged)
            try {
                val result =
                    launchImage(
                        extracted,
                        mapOf("JAVA_TOOL_OPTIONS" to "-XX:AOTMode=on -D$SMOKE_PROPERTY=true"),
                    )
                assertEquals(0, result.exit, result.output)
                assertTrue(result.output.contains(SMOKE_REPORT_PREFIX), result.output)
                assertTrue(result.output.contains("\"aotCache\":\"in-use\""), result.output)
            } finally {
                extracted.deleteRecursively()
            }
        } finally {
            packaged.delete()
        }
    }

    /** The timestamp-drift negative: a one-second drift on a classpath JAR breaks the cache. */
    @Test
    fun `a drifted classpath timestamp breaks the strict cache`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val packaged = packageImage(image)
        try {
            val extracted = extractImage(packaged)
            try {
                val jar = extracted.walkTopDown().first { it.isFile && it.name.endsWith(".jar") }
                assertTrue(jar.setLastModified(jar.lastModified() + 1000L), "the drift was applied")
                val result =
                    launchImage(
                        extracted,
                        mapOf("JAVA_TOOL_OPTIONS" to "-XX:AOTMode=on -D$SMOKE_PROPERTY=true"),
                    )
                assertNotEquals(0, result.exit, result.output)
                assertTrue(result.output.contains("timestamp has changed"), result.output)
            } finally {
                extracted.deleteRecursively()
            }
        } finally {
            packaged.delete()
        }
    }

    /** Packages the image as `packageZip` does after the normalization. */
    private fun packageImage(image: File): File {
        val zip = File.createTempFile("aot-packaged", ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            image.walkTopDown().forEach { file ->
                val relative = file.relativeTo(image).path.replace(File.separatorChar, '/')
                if (relative.isEmpty()) return@forEach
                val entry = ZipEntry(if (file.isDirectory) "$relative/" else relative)
                entry.time = AOT_IMAGE_TIMESTAMP_MS
                out.putNextEntry(entry)
                if (file.isFile) {
                    file.inputStream().use { it.copyTo(out) }
                }
                out.closeEntry()
            }
        }
        return zip
    }

    /** Extracts a packaged image, restoring each entry's recorded modification time. */
    private fun extractImage(zip: File): File {
        val root = Files.createTempDirectory("aot-extracted").toFile()
        ZipFile(zip).use { zipFile ->
            zipFile.entries().asSequence().forEach { entry ->
                val target = File(root, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    zipFile.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setLastModified(entry.time)
                }
            }
        }
        // java.util.zip carries no Unix mode; the real archive and installers restore it,
        // and the launcher needs its executable bit to start.
        File(root, launcherRelative).setExecutable(true, false)
        return root
    }

    /**
     * The real JDK 25 behavior that makes [SmokeMode]'s `aotCache` state honest: an auto
     * launch with an unusable archive silently falls back, while `-XX:AOTMode=on` fails
     * before main. The packaged acceptance relies on the strict failure; the auto run must
     * not claim more than the file's existence proves.
     */
    @Test
    fun `an auto launch falls back silently on a bad archive`() {
        val result =
            runJava(listOf("-XX:AOTCache=${badArchive().absolutePath}", "-version"))

        assertEquals(0, result.exit)
        assertTrue(result.output.contains("Unable to map shared spaces"), result.output)
        assertTrue(result.output.contains("openjdk version"), result.output)
    }

    @Test
    fun `a strict launch fails before main on a bad archive`() {
        val result =
            runJava(
                listOf("-XX:AOTMode=on", "-XX:AOTCache=${badArchive().absolutePath}", "-version"),
            )

        assertEquals(1, result.exit)
        assertTrue(result.output.contains("Unable to map shared spaces"), result.output)
        assertFalse(result.output.contains("openjdk version"), result.output)
    }

    @Test
    fun `a strict launch succeeds with a trained archive`() {
        val result =
            runJava(
                listOf("-XX:AOTMode=on", "-XX:AOTCache=${goodArchive().absolutePath}", "-version"),
            )

        assertEquals(0, result.exit)
        assertTrue(result.output.contains("openjdk version"), result.output)
    }

    @Test
    fun `a strict launch rejects a conflicting share option`() {
        val good = goodArchive()
        val result =
            runJava(
                listOf(
                    "-XX:AOTMode=on",
                    "-XX:AOTCache=${good.absolutePath}",
                    "-Xshare:off",
                    "-version",
                ),
            )

        assertEquals(1, result.exit)
        assertTrue(result.output.contains("cannot be used at the same time"), result.output)
    }

    private fun goodArchive(): File {
        val archive = File.createTempFile("aot-strict-good", ".aot")
        val result =
            runJava(listOf("-XX:AOTCacheOutput=${archive.absolutePath}", "-version"))
        assertEquals(0, result.exit, result.output)
        assertTrue(archive.isFile && archive.length() > 0L, "the trained archive is missing")
        return archive
    }

    private fun badArchive(): File =
        File.createTempFile("aot-strict-bad", ".aot").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

    private fun runJava(arguments: List<String>): ProbeResult {
        val process =
            ProcessBuilder(listOf(toolPath(jdk25, "java").absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return ProbeResult(process.waitFor(), output)
    }

    private data class ProbeResult(
        val exit: Int,
        val output: String,
    )

    private fun tinyImage(mainBody: String): File {
        val work = Files.createTempDirectory("aot-real-probe").toFile()
        val classes = File(work, "classes").apply { mkdirs() }
        File(work, "TinyAotProbeMain.java").writeText(
            """
            public class TinyAotProbeMain {
                public static void main(String[] args) throws Exception {
                    $mainBody
                }
            }
            """.trimIndent(),
        )
        runCommand(
            listOf(
                toolPath(jdk25, "javac").absolutePath,
                "-d",
                classes.absolutePath,
                File(work, "TinyAotProbeMain.java").absolutePath,
            ),
        )
        val jar = File(work, "tiny.jar")
        runCommand(
            listOf(
                toolPath(jdk25, "jar").absolutePath,
                "--create",
                "--file",
                jar.absolutePath,
                "--main-class",
                "TinyAotProbeMain",
                "-C",
                classes.absolutePath,
                ".",
            ),
        )
        // A clean input dir carries only the jar, so the image's app dir stays minimal.
        val input = File(work, "input").apply { mkdirs() }
        jar.copyTo(File(input, "tiny.jar"), overwrite = true)
        val dest = Files.createTempDirectory("aot-real-image").toFile()
        runCommand(
            listOf(
                toolPath(jdk25, "jpackage").absolutePath,
                "--type",
                "app-image",
                "--name",
                "Neutrodyne",
                "--input",
                input.absolutePath,
                "--main-jar",
                "tiny.jar",
                "--main-class",
                "TinyAotProbeMain",
                "--java-options",
                "-XX:AOTCache=\$APPDIR/neutrodyne.aot",
                "--dest",
                dest.absolutePath,
            ),
        )
        val image =
            if (osWire == "macos") {
                File(dest, "Neutrodyne.app")
            } else {
                File(dest, "Neutrodyne")
            }
        assertTrue(image.isDirectory, "jpackage produced no image at $image")
        return image
    }

    private fun trainTask(image: File): TrainAotCacheTask {
        val projectDir = Files.createTempDirectory("aot-real-project").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task =
            project.tasks
                .register("trainAotCache", TrainAotCacheTask::class.java)
                .get()
        task.appImage.set(image)
        task.launcher.set(File(image, launcherRelative))
        task.launcherCfg.set(File(image, cfgRelative))
        task.runtimeRelease.set(File(image, runtimeReleaseRelative))
        task.aotArchive.set(File(image, "$appDirRelative/neutrodyne.aot"))
        task.runtimeHome.set(jdk25)
        task.runtimeJavaSource.set(File(jdk25, "bin/" + toolName("java")))
        // The manifest and native inputs are declared for Gradle's up-to-date checking; the
        // direct task call does not read them, but the properties must be bound.
        task.runtimeClasspathManifest.set(File(projectDir, "manifest.txt").apply { writeText("x") })
        task.nativeLibraries.from(File(projectDir, "natives").apply { mkdirs() })
        task.targetOs.set(osWire)
        task.installKind.set(installKind)
        task.expectedInstallKinds.set(expectedInstallKinds)
        return task
    }

    private fun launchImage(
        image: File,
        environment: Map<String, String> = emptyMap(),
    ): ProbeResult {
        val builder =
            ProcessBuilder(File(image, launcherRelative).absolutePath)
                .directory(image)
                .redirectErrorStream(true)
        builder.environment().apply { environment.forEach { (key, value) -> put(key, value) } }
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        loggerLifecycle(output)
        return ProbeResult(exit, output)
    }

    private fun runCommand(argv: List<String>) {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
    }

    private fun loggerLifecycle(output: String) {
        output.lineSequence().forEach { println(it) }
    }

    private fun toolName(name: String): String = name + if (osWire == "windows") ".exe" else ""

    private fun toolPath(
        home: File,
        name: String,
    ): File = File(home, "bin/" + toolName(name))

    private fun hostOsWire(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.startsWith("windows") -> "windows"
            os == "mac os x" -> "macos"
            os == "linux" -> "linux"
            else -> error("this host ($os) serves no packaging target")
        }
    }

    private fun jdk25Home(): File? {
        val home = System.getProperty("user.home")
        val candidates =
            listOfNotNull(
                System.getenv("JAVA_HOME_25_ARM64")?.let { File(it) },
                System.getenv("JAVA_HOME_25_X64")?.let { File(it) },
                File(File(home, ".local/jdks/jdk25"), "Contents/Home"),
                File(home, ".local/jdks/jdk25"),
                File("/usr/lib/jvm/temurin-25-jdk-amd64"),
                File("/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"),
            )
        return candidates
            .firstOrNull { candidate ->
                toolPath(candidate, "jpackage").isFile &&
                    toolPath(candidate, "javac").isFile &&
                    runsJdk25(candidate)
            }
    }

    private fun runsJdk25(home: File): Boolean {
        val java = toolPath(home, "java")
        if (!java.isFile) return false
        val process = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return process.waitFor() == 0 && Regex("version \"25[^\"]*\"").containsMatchIn(output)
    }

    private companion object {
        /**
         * The minimal probe main: it reports the training line and, when the strict
         * verification launches it with the smoke property, the smoke line the task parses.
         */
        const val MAIN_BODY =
            "if (Boolean.getBoolean(\"neutrodyne.smoke\")) { " +
                "System.out.println(\"SMOKE {\\\"aotCache\\\":\\\"in-use\\\"}\"); " +
                "} else { " +
                "System.out.println(\"AOT-TRAINING {\\\"steps\\\":{}}\"); " +
                "}"

        /**
         * The marker probe: it writes the marker only when neither mode property is true,
         * so a marker file proves ordinary main ran with the real AppDirs.
         */
        const val MARKER_MAIN =
            "if (Boolean.getBoolean(\"neutrodyne.smoke\")) { " +
                "System.out.println(\"SMOKE {\\\"aotCache\\\":\\\"in-use\\\"}\"); " +
                "} else if (Boolean.getBoolean(\"compose.aot.training-run\")) { " +
                "System.out.println(\"AOT-TRAINING {\\\"steps\\\":{}}\"); " +
                "} else { " +
                "String marker = System.getProperty(\"neutrodyne.probe.marker\"); " +
                "if (marker != null) java.nio.file.Files.writeString(java.nio.file.Path.of(marker), \"ran\"); " +
                "System.out.println(\"ORDINARY MAIN\"); " +
                "}"
    }
}

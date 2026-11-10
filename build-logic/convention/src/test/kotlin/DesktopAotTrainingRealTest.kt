// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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
    @get:Rule
    val scratch = TemporaryFolder.builder().assureDeletion().build()

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

    /** Hosted tool-cache hints may be aliases; resolve the trusted fixture home first. */
    @Test
    fun `a trusted fixture JDK alias is resolved before training`() {
        org.junit.Assume.assumeFalse("the hosted Linux JDK alias fixture", osWire == "windows")
        val directory = scratchDirectory("aot-real-jdk-alias").toPath()
        val alias = directory.resolve("jdk25")
        val ownedHome = directory.resolve("real-jdk")
        try {
            // The link targets only a test-owned copy, never the installed toolchain.
            Files.walk(jdk25.toPath()).use { paths ->
                paths.forEach { source ->
                    val target = ownedHome.resolve(jdk25.toPath().relativize(source))
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else {
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            Files.createSymbolicLink(alias, ownedHome)
            val fixtureJdk = requireNotNull(jdk25Home(listOf(alias.toFile())))
            val image = tinyImage(MAIN_BODY)

            trainTask(image, fixtureJdk).train()

            assertEquals(ownedHome.toFile().canonicalFile, fixtureJdk)
            assertTrue(File(image, "$appDirRelative/neutrodyne.aot").isFile)
            assertFalse(File(image, runtimeJavaRelative).exists())
        } finally {
            Files.deleteIfExists(alias)
            // Files.walk does not follow links, so cleanup stays inside the owned directory.
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        assertTrue(File(jdk25, "bin/" + toolName("java")).isFile, "the source JDK must remain intact")
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

        val extracted = extractImage(packageImage(image))
        val result =
            launchImage(
                extracted,
                mapOf("JAVA_TOOL_OPTIONS" to "-XX:AOTMode=on -D$SMOKE_PROPERTY=true"),
            )
        assertEquals(0, result.exit, result.output)
        assertTrue(result.output.contains(SMOKE_REPORT_PREFIX), result.output)
        assertTrue(result.output.contains("\"aotCache\":\"in-use\""), result.output)
    }

    /**
     * RED→GREEN for jpackage's own installers (11 AOT cache, DEB/RPM staging): JDK 25's
     * `PackagingPipeline.copyAppImage` copies the trained image into jpackage's private
     * staging tree through `PathGroup.copy` — `Files.copy` with `NOFOLLOW_LINKS` and no
     * `COPY_ATTRIBUTES` — so the staged classpath JARs pick up the packaging time and a
     * strict `-XX:AOTMode=on` launch fails before main (the defect the real DMG probe
     * showed). The shipped `Neutrodyne-post-image.sh` hook — the supported
     * `RUN_POST_IMAGE_USER_SCRIPT` point — restores the fixed epoch on the staged tree,
     * and the strict load proves the archive usable again. The staging tree is
     * fabricated in the Linux install layout (`opt/neutrodyne/…`) that this host's
     * trained image maps to; the strict JVM check on the staged JARs is the mechanism an
     * installed package relies on. The hook writes inside `$JpAppImageDir` only, so the
     * trained image keeps its content and timestamps.
     */
    @Test
    fun `the staging copy loses the strict cache and the post-image hook restores it`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val archive = File(image, "$appDirRelative/neutrodyne.aot")
        assertTrue(archive.isFile && archive.length() > 0L, "the training launch must leave the archive")
        val originalState = image.stateByPath()
        val originalArchiveBytes = archive.readBytes()

        // Emulate copyAppImage: the app image is remapped into the package layout's
        // install tree (opt/neutrodyne) through the attribute-losing copy.
        val staging = scratchDirectory("aot-staging")
        val stagedApp = File(staging, "opt/neutrodyne")
        copyAppImageForStaging(image, stagedApp)

        val stagedCfg = File(stagedApp, "lib/app/Neutrodyne.cfg")
        val stagedJars = aotClasspathJars(stagedCfg.readText(), stagedApp, "linux")
        assertTrue(stagedJars.isNotEmpty(), "the staging layout must carry the classpath JARs")
        assertTrue(
            stagedJars.any { it.lastModified() != AOT_IMAGE_TIMESTAMP_MS },
            "the attribute-losing copy must reset the JAR timestamps (the defect)",
        )

        // RED: the staged copy fails the strict load — what an installed DEB or RPM
        // would do without the hook. The probe runs the staged image's own runtime
        // (the pinned java provisioned the way trainAotCache provisions it): the
        // cache records java.home's modules too, so a foreign JDK's java is not a
        // substitute.
        val stagedJava = provisionStagedJava(stagedApp)
        val before = strictCacheProbe(stagedJava, stagedApp, stagedJars)
        assertNotEquals(0, before.exit, before.output)
        assertTrue(before.output.contains("timestamp has changed"), before.output)

        // The shipped hook runs the way jpackage's ScriptRunner runs it: `sh` on
        // the file. A cleared environment plus a non-UTC TZ proves the hook
        // carries the epoch itself instead of inheriting a PATH or timezone.
        runPostImageHook(staging)
        stagedApp.walkTopDown().forEach { entry ->
            if (!Files.isSymbolicLink(entry.toPath())) {
                assertEquals(
                    AOT_IMAGE_TIMESTAMP_MS,
                    entry.lastModified(),
                    "the hook left a staged entry off the fixed epoch: $entry",
                )
            }
        }

        // GREEN: the strict load proves the archive usable on the staged copy again.
        val after = strictCacheProbe(stagedJava, stagedApp, stagedJars)
        assertEquals(0, after.exit, after.output)
        assertTrue(after.output.contains("\"aotCache\":\"in-use\""), after.output)

        // The hook wrote only inside $JpAppImageDir: the trained image is untouched.
        assertEquals(originalArchiveBytes.toList(), archive.readBytes().toList())
        assertEquals(originalState, image.stateByPath(), "the post-image hook touched the trained image")
    }

    /**
     * The DMG producer boundary (11 AOT cache, macOS DMG): jpackage's own DMG build
     * stages the trained image through the attribute-losing copyAppImage, and the
     * supported <app name>-post-image.sh hook — the same RUN_POST_IMAGE_USER_SCRIPT
     * point the DEB/RPM packages use — restores the fixed epoch on the staged copy
     * before hdiutil reads it. The test builds a real DMG with `jpackage --temp` (the
     * temp build root is kept, so the staged copy can be inspected and strict-launched
     * without mounting the DMG), first without the hook (RED) and then with it (GREEN).
     */
    @Test
    fun `the DMG staging copy loses the strict cache and the post-image hook restores it`() {
        org.junit.Assume.assumeTrue("the DMG hook needs macOS jpackage", osWire == "macos")
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val archive = File(image, "$appDirRelative/neutrodyne.aot")
        assertTrue(archive.isFile && archive.length() > 0L, "the training launch must leave the archive")

        val hook = File(repoRoot(), "desktopApp/packaging/dmg/Neutrodyne-post-image.sh")
        assertTrue(hook.isFile, "the shipped DMG post-image hook is missing: $hook")
        val hookDir = scratchDirectory("aot-dmg-hook")
        hook.copyTo(File(hookDir, hook.name), overwrite = true)
        val emptyDir = scratchDirectory("aot-dmg-empty")

        // RED: without the hook the staged classpath JARs carry the packaging time.
        val redTemp = scratchDirectory("aot-dmg-red")
        buildDmg(image, emptyDir, redTemp)
        val redApp = File(redTemp, "image/Neutrodyne.app")
        assertTrue(redApp.isDirectory, "jpackage produced no staged app at $redApp")
        val redJars = stagedClasspathJars(redApp)
        assertTrue(redJars.isNotEmpty(), "the staged DMG copy carries no classpath JARs")
        assertTrue(
            redJars.any { it.lastModified() != AOT_IMAGE_TIMESTAMP_MS },
            "the attribute-losing copy must reset the JAR timestamps (the defect)",
        )
        val red = strictLaunch(redApp)
        assertNotEquals(0, red.exit, red.output)
        assertTrue(red.output.contains("timestamp has changed"), red.output)

        // GREEN: the shipped hook restores the epoch on the staged copy.
        val greenTemp = scratchDirectory("aot-dmg-green")
        buildDmg(image, hookDir, greenTemp)
        val greenApp = File(greenTemp, "image/Neutrodyne.app")
        assertTrue(greenApp.isDirectory, "jpackage produced no staged app at $greenApp")
        greenApp.walkTopDown().forEach { entry ->
            if (!Files.isSymbolicLink(entry.toPath())) {
                assertEquals(
                    AOT_IMAGE_TIMESTAMP_MS,
                    entry.lastModified(),
                    "the hook left a staged entry off the fixed epoch: $entry",
                )
            }
        }
        val green = strictLaunch(greenApp)
        assertEquals(0, green.exit, green.output)
        assertTrue(green.output.contains("\"aotCache\":\"in-use\""), green.output)
    }

    /** Builds a real DMG with jpackage, keeping the temp build root for inspection. */
    private fun buildDmg(
        image: File,
        resourceDir: File,
        tempDir: File,
    ) {
        val dest = scratchDirectory("aot-dmg-dest")
        runCommand(
            listOf(
                toolPath(jdk25, "jpackage").absolutePath,
                "--type",
                "dmg",
                "--app-image",
                image.absolutePath,
                "--name",
                "Neutrodyne",
                "--app-version",
                "1.0.0",
                "--dest",
                dest.absolutePath,
                "--resource-dir",
                resourceDir.absolutePath,
                "--temp",
                tempDir.absolutePath,
            ),
        )
    }

    /** The staged .app's classpath JARs, read from its .cfg the way the cache does. */
    private fun stagedClasspathJars(app: File): List<File> =
        aotClasspathJars(File(app, "Contents/app/Neutrodyne.cfg").readText(), app, "macos")

    /** Launches the staged app's own launcher under the strict cache check. */
    private fun strictLaunch(app: File): ProbeResult {
        val builder =
            ProcessBuilder(File(app, "Contents/MacOS/Neutrodyne").absolutePath)
                .directory(app)
                .redirectErrorStream(true)
        builder.environment()["JAVA_TOOL_OPTIONS"] = "-XX:AOTMode=on -D$SMOKE_PROPERTY=true"
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return ProbeResult(process.waitFor(), output)
    }

    /** The timestamp-drift negative: a one-second drift on a classpath JAR breaks the cache. */
    @Test
    fun `a drifted classpath timestamp breaks the strict cache`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val extracted = extractImage(packageImage(image))
        val jar =
            aotClasspathJars(File(extracted, cfgRelative).readText(), extracted, osWire)
                .first { it.isFile }
        assertTrue(jar.setLastModified(jar.lastModified() + 1000L), "the drift was applied")
        val result =
            launchImage(
                extracted,
                mapOf("JAVA_TOOL_OPTIONS" to "-XX:AOTMode=on -D$SMOKE_PROPERTY=true"),
            )
        assertNotEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("timestamp has changed"), result.output)
    }

    /**
     * The installed-payload acceptance (11 AOT cache): the shipped
     * `scripts/desktop/smoke-start.sh` gate runs the trained image's own launcher the
     * way the release flow does, applies the strict request itself and must report
     * `aotCache":"in-use"` — the SMOKE line is the shipped helper's stdout contract.
     */
    @Test
    fun `the shipped smoke gate proves the trained archive in use`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val result = smokeStart(image)
        assertEquals(0, result.exit, result.output)
        assertTrue(result.output.contains("\"aotCache\":\"in-use\""), result.output)
    }

    /**
     * The negative of the shipped gate: a corrupted classpath timestamp is the
     * post-image-hook defect at install time, and the strict launch it requests
     * must surface as a failed smoke run, never an unverified pass.
     */
    @Test
    fun `the shipped smoke gate fails on a drifted classpath jar`() {
        val image = tinyImage(MAIN_BODY)
        trainTask(image).train(System.getenv())
        val jar =
            aotClasspathJars(File(image, cfgRelative).readText(), image, osWire)
                .first { it.isFile }
        assertTrue(jar.setLastModified(jar.lastModified() + 1000L), "the drift was applied")
        val result = smokeStart(image)
        assertNotEquals(0, result.exit, result.output)
        assertFalse(
            result.output.lines().any { it.startsWith(SMOKE_REPORT_PREFIX) },
            "a drifted classpath jar must not produce a SMOKE verdict",
        )
    }

    /** Runs the shipped smoke gate the way the release flow does. */
    private fun smokeStart(image: File): ProbeResult {
        val script = File(repoRoot(), "scripts/desktop/smoke-start.sh")
        assertTrue(script.isFile, "the shipped smoke gate is missing: $script")
        val process =
            ProcessBuilder("bash", script.absolutePath, image.absolutePath, "--timeout=60")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return ProbeResult(process.waitFor(), output)
    }

    /** Packages the image as `packageZip` does after the normalization. */
    private fun packageImage(image: File): File {
        val zip = File.createTempFile("aot-packaged", ".zip", scratch.root)
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
        val root = scratchDirectory("aot-extracted")
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
     * Maps this host's image into the Linux package staging layout (`opt/neutrodyne/…`)
     * the post-image hook expects: the app dir becomes `lib/app`, the launcher's
     * directory `bin`, the bundled runtime home `lib/runtime` — the same remap
     * `copyAppImage` performs with `Package::packageLayout` on a Linux runner.
     */
    private fun copyAppImageForStaging(
        image: File,
        stagedApp: File,
    ) {
        copyEntryNoAttributes(File(image, appDirRelative), File(stagedApp, "lib/app"))
        val launcher = File(image, launcherRelative)
        copyEntryNoAttributes(launcher, File(stagedApp, "bin/Neutrodyne"))
        copyEntryNoAttributes(
            File(image, runtimeReleaseRelative).parentFile,
            File(stagedApp, "lib/runtime"),
        )
    }

    /**
     * `PathGroup.copy`'s `TransformHandler.Copy`: `Files.copy` with `NOFOLLOW_LINKS`
     * only, so a staged file's modification time is the copy time — the defect.
     */
    private fun copyEntryNoAttributes(
        source: File,
        destination: File,
    ) {
        if (source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
            destination.mkdirs()
            source.listFiles()?.forEach { copyEntryNoAttributes(it, File(destination, it.name)) }
        } else {
            destination.parentFile?.mkdirs()
            Files.copy(source.toPath(), destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    /**
     * The strict-mode proof on the staged copy's own artifacts: the staged runtime's
     * `bin/java` runs `-XX:AOTMode=on` with the staged archive and the staged
     * classpath JARs the .cfg names — the same size-and-mtime check an installed
     * package's launcher hits.
     */
    private fun strictCacheProbe(
        java: File,
        stagedApp: File,
        stagedJars: List<File>,
    ): ProbeResult =
        runJava(
            java,
            listOf(
                AOT_MODE_STRICT_OPTION,
                "-XX:AOTCache=${File(stagedApp, "lib/app/neutrodyne.aot").absolutePath}",
                "-cp",
                stagedJars.joinToString(File.pathSeparator) { it.absolutePath },
                SMOKE_PROPERTY_OPTION,
                "TinyAotProbeMain",
            ),
        )

    /**
     * The pinned runtime's java launcher dropped into the staged runtime's `bin`
     * for the probe — `trainAotCache`'s own provisioning for the training launch;
     * the real package ships no `bin/java`, so the fixture adds and ignores it.
     */
    private fun provisionStagedJava(stagedApp: File): File {
        val java = File(stagedApp, "lib/runtime/bin/" + toolName("java"))
        java.parentFile.mkdirs()
        Files.copy(toolPath(jdk25, "java").toPath(), java.toPath())
        java.setExecutable(true, false)
        return java
    }

    /** Runs a shipped post-image hook under `sh` with the ScriptRunner-style env. */
    private fun runPostImageHook(staging: File) {
        val hook =
            File(repoRoot(), "desktopApp/packaging/deb/Neutrodyne-post-image.sh")
        assertTrue(hook.isFile, "the shipped post-image hook is missing: $hook")
        val builder =
            ProcessBuilder("sh", hook.absolutePath)
                .directory(staging)
                .redirectErrorStream(true)
        builder.environment().clear()
        builder.environment()["JpAppImageDir"] = staging.absolutePath
        builder.environment()["TZ"] = "Pacific/Kiritimati"
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), "the post-image hook failed:\n$output")
    }

    /** Each entry's modification time keyed by its path — the image's timestamp state. */
    private fun File.stateByPath(): Map<String, Long> =
        walkTopDown()
            .associate { it.relativeTo(this).path to it.lastModified() }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "desktopApp/packaging/deb/Neutrodyne-post-image.sh").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repository root not found from ${System.getProperty("user.dir")}")
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
        val archive = File.createTempFile("aot-strict-good", ".aot", scratch.root)
        val result =
            runJava(listOf("-XX:AOTCacheOutput=${archive.absolutePath}", "-version"))
        assertEquals(0, result.exit, result.output)
        assertTrue(archive.isFile && archive.length() > 0L, "the trained archive is missing")
        return archive
    }

    private fun badArchive(): File =
        File.createTempFile("aot-strict-bad", ".aot", scratch.root).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

    private fun runJava(arguments: List<String>): ProbeResult = runJava(toolPath(jdk25, "java"), arguments)

    private fun runJava(
        java: File,
        arguments: List<String>,
    ): ProbeResult {
        val process =
            ProcessBuilder(listOf(java.absolutePath) + arguments)
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
        val work = scratchDirectory("aot-real-probe")
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
        val dest = scratchDirectory("aot-real-image")
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

    private fun trainTask(
        image: File,
        fixtureJdk: File = jdk25,
    ): TrainAotCacheTask {
        val projectDir = scratchDirectory("aot-real-project")
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
        task.runtimeHome.set(fixtureJdk)
        task.runtimeJavaSource.set(File(fixtureJdk, "bin/" + toolName("java")))
        // The manifest and native inputs are declared for Gradle's up-to-date checking; the
        // direct task call does not read them, but the properties must be bound.
        task.runtimeClasspathManifest.set(File(projectDir, "manifest.txt").apply { writeText("x") })
        task.nativeLibraries.from(File(projectDir, "natives").apply { mkdirs() })
        task.targetOs.set(osWire)
        task.installKind.set(installKind)
        task.expectedInstallKinds.set(expectedInstallKinds)
        return task
    }

    private fun scratchDirectory(prefix: String): File =
        Files.createTempDirectory(scratch.root.toPath(), prefix).toFile()

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

    private fun jdk25Home(explicitCandidates: List<File>? = null): File? {
        val home = System.getProperty("user.home")
        val candidates =
            explicitCandidates ?: listOfNotNull(
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
            }?.canonicalFile
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
         * The honest cache state, computed the way SmokeMode does from the real JVM
         * input arguments: `in-use` only when the configured archive exists and the
         * exact strict mode is on the command line — the shipped gate's verdict key.
         */
        const val SMOKE_REPORT =
            "java.util.List<String> opts = " +
                "java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments(); " +
                "String cache = opts.stream()" +
                ".filter(a -> a.startsWith(\"-XX:AOTCache=\"))" +
                ".map(a -> a.substring(13))" +
                ".reduce((x, y) -> y).orElse(null); " +
                "String state = cache == null ? \"not-configured\" : " +
                "!java.nio.file.Files.exists(java.nio.file.Path.of(cache)) ? \"missing\" : " +
                "opts.contains(\"-XX:AOTMode=on\") ? \"in-use\" : \"unverified\"; " +
                "System.out.println(\"SMOKE {\\\"aotCache\\\":\\\"\" + state + \"\\\"}\"); "

        /**
         * The minimal probe main: it reports the training line and, when the strict
         * verification launches it with the smoke property, the smoke line the task parses.
         */
        const val MAIN_BODY =
            "if (Boolean.getBoolean(\"neutrodyne.smoke\")) { " +
                SMOKE_REPORT +
                "} else { " +
                "System.out.println(\"AOT-TRAINING {\\\"steps\\\":{}}\"); " +
                "}"

        /**
         * The marker probe: it writes the marker only when neither mode property is true,
         * so a marker file proves ordinary main ran with the real AppDirs.
         */
        const val MARKER_MAIN =
            "if (Boolean.getBoolean(\"neutrodyne.smoke\")) { " +
                SMOKE_REPORT +
                "} else if (Boolean.getBoolean(\"compose.aot.training-run\")) { " +
                "System.out.println(\"AOT-TRAINING {\\\"steps\\\":{}}\"); " +
                "} else { " +
                "String marker = System.getProperty(\"neutrodyne.probe.marker\"); " +
                "if (marker != null) java.nio.file.Files.writeString(java.nio.file.Path.of(marker), \"ran\"); " +
                "System.out.println(\"ORDINARY MAIN\"); " +
                "}"
    }
}

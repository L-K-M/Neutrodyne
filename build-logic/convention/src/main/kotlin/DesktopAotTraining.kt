// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/*
 * The PO-42 AOT cache wiring (11 AOT cache): the packaged launcher reads the assembly-phase
 * archive through `-XX:AOTCache=$APPDIR/neutrodyne.aot`, and one training launch of the
 * packaged image — its own launcher, so the bundled runtime, classpath and JVM options are
 * exactly the shipped ones — writes it through `-XX:AOTCacheOutput`. The flag ships only on
 * packaging invocations (`-Pneutrodyne.installKind=…`); `run`, tests and the assemble image
 * carry neither flag nor archive.
 */

/** The archive's file name inside the image's app dir ($APPDIR). */
internal const val AOT_CACHE_FILE = "neutrodyne.aot"

/** The production read flag in every packaged .cfg (PO-42). */
internal const val AOT_CACHE_OPTION = "-XX:AOTCache=\$APPDIR/$AOT_CACHE_FILE"

/** The two options that replace the read flag inside the training .cfg. */
internal const val AOT_CACHE_OUTPUT_OPTION = "-XX:AOTCacheOutput=\$APPDIR/$AOT_CACHE_FILE"

/** The training mode's system property name (PO-42). */
internal const val AOT_TRAINING_PROPERTY = "compose.aot.training-run"

/** The training .cfg's mode option. */
internal const val AOT_TRAINING_PROPERTY_OPTION = "-D$AOT_TRAINING_PROPERTY=true"

/** The training run's one-line report (desktopApp's `AotTrainingMode`). */
internal const val AOT_TRAINING_REPORT_PREFIX = "AOT-TRAINING "

/** The smoke run's one-line report (desktopApp's `SmokeMode`). */
internal const val SMOKE_REPORT_PREFIX = "SMOKE "

/** The strict-mode option's name and its strict value. */
internal const val AOT_MODE_OPTION = "-XX:AOTMode"
internal const val AOT_MODE_STRICT_OPTION = "$AOT_MODE_OPTION=on"

/** The smoke mode's system property name and its option. */
internal const val SMOKE_PROPERTY = "neutrodyne.smoke"
internal const val SMOKE_PROPERTY_OPTION = "-D$SMOKE_PROPERTY=true"

/** The per-OS paths of the jpackage app image the training launch needs (11 Resources layout). */
internal data class AotImageLayout(
    /** The packaged launcher, relative to the image root. */
    val launcher: String,
    /** The launcher's configuration file (its java-options list), relative to the image root. */
    val cfg: String,
    /** $APPDIR — the .cfg's directory, where the archive is written and read. */
    val appDir: String,
    /** The bundled runtime's `release` file: the input binding the pinned runtime's identity. */
    val runtimeRelease: String,
    /**
     * The image runtime's java launcher, relative to the image root. jlink's
     * `--strip-native-commands` leaves no `bin/java`, so the training task provisions the
     * pinned runtime's own launcher here for the duration of the launch (11 jlink modules).
     */
    val runtimeJava: String,
)

internal fun aotImageLayout(osWire: String): AotImageLayout =
    when (osWire) {
        "windows" -> {
            AotImageLayout(
                launcher = "Neutrodyne.exe",
                cfg = "app/Neutrodyne.cfg",
                appDir = "app",
                runtimeRelease = "runtime/release",
                runtimeJava = "runtime/bin/java.exe",
            )
        }

        "macos" -> {
            AotImageLayout(
                launcher = "Contents/MacOS/Neutrodyne",
                cfg = "Contents/app/Neutrodyne.cfg",
                appDir = "Contents/app",
                runtimeRelease = "Contents/runtime/Contents/Home/release",
                runtimeJava = "Contents/runtime/Contents/Home/bin/java",
            )
        }

        else -> {
            AotImageLayout(
                launcher = "bin/Neutrodyne",
                cfg = "lib/app/Neutrodyne.cfg",
                appDir = "lib/app",
                runtimeRelease = "lib/runtime/release",
                runtimeJava = "lib/runtime/bin/java",
            )
        }
    }

/** The image runtime's java launcher path, relative to the image root (11 jlink modules). */
internal fun imageRuntimeJava(osWire: String): String = aotImageLayout(osWire).runtimeJava

/**
 * The training .cfg: the production read flag must not reach the training JVM — it names a
 * file the launch is about to write — so it is dropped and the write flag plus the
 * training-mode property are appended inside the `[JavaOptions]` section. Every other line
 * survives verbatim and in order. A .cfg without the read flag fails closed: the image's
 * launcher would ignore the trained archive.
 */
internal fun aotTrainingCfg(cfg: String): String {
    val readLine = "java-options=$AOT_CACHE_OPTION"
    if (readLine !in cfg.lines()) {
        throw GradleException(
            "the packaged .cfg carries no $AOT_CACHE_OPTION option; " +
                "package with -Pneutrodyne.installKind=… so the launcher reads the cache",
        )
    }
    val out = mutableListOf<String>()
    var inJavaOptions = false
    var injected = false

    fun injectTrainingOptions() {
        if (injected) return
        out += "java-options=$AOT_CACHE_OUTPUT_OPTION"
        out += "java-options=$AOT_TRAINING_PROPERTY_OPTION"
        injected = true
    }
    for (line in cfg.lines()) {
        if (line.startsWith("[")) {
            if (inJavaOptions) injectTrainingOptions()
            // The real launcher recognizes the section by the text between `[` and the
            // last `]`; matching that keeps the swap faithful to the launcher's parser.
            inJavaOptions = jpackageSectionName(line) == "JavaOptions"
        }
        if (line != readLine) out += line
    }
    injectTrainingOptions()
    // The swapped file keeps the image's own line endings — a Windows image's .cfg uses CRLF.
    val newline = if ("\r\n" in cfg) "\r\n" else "\n"
    return out.joinToString(newline, postfix = newline)
}

/**
 * The training launcher's argv (no shell, so the path needs no quoting): the image's own
 * launcher, wrapped under `xvfb-run`/`dbus-run-session` on a headless Linux runner so the
 * windowed journey can run there too. A headless Linux without xvfb-run still launches
 * bare — the app's training mode then reports the window skip as a failure instead of
 * masquerading as a completed run.
 */
internal fun aotLauncherCommand(
    osWire: String,
    launcherPath: String,
    hasDisplay: Boolean,
    xvfbAvailable: Boolean,
    dbusAvailable: Boolean,
): List<String> {
    if (osWire != "linux" || hasDisplay || !xvfbAvailable) return listOf(launcherPath)
    return if (dbusAvailable) {
        listOf("xvfb-run", "-a", "dbus-run-session", "--", launcherPath)
    } else {
        listOf("xvfb-run", "-a", launcherPath)
    }
}

/** The mode a launch requires: the system property that must equal [requiredValue] and the
 * JVM options that must carry the given values. */
internal data class AotModeRequirement(
    val property: String,
    val requiredValue: String,
    val options: List<Pair<String, String>> = emptyList(),
)

/** One effective option setting and the source it came from. */
private data class AotOptionSetting(
    val value: String,
    val source: String,
)

/** The whitespace the JVM's environment-variable tokenizer treats as a separator. */
private const val JVM_OPTION_WHITESPACE = " \t\r\n"

/**
 * Fails closed before a launch when the JVM's actual option precedence would keep the
 * required mode from taking effect.
 *
 * The jpackage launcher creates the JVM through JNI, so the precedence is the JVM's:
 * `_JAVA_OPTIONS` overrides the command line, the command line (the .cfg's active
 * `java-options`, with `JDK_JAVA_OPTIONS` prepended when the java launcher is used)
 * overrides `JAVA_TOOL_OPTIONS`. Quotes in the environment variables group and are
 * removed by the JVM; quotes in the .cfg are passed through literally. A malformed or
 * unbalanced quote in any of the three variables fails closed: the JVM would refuse to
 * start, and a silent fallback to ordinary main is not acceptable.
 *
 * [cfg] is the exact .cfg the launcher will read (the swapped training .cfg or the
 * restored production .cfg); [environment] is the exact environment the launcher will
 * inherit. Nothing is modified.
 */
internal fun checkAotModeAdmission(
    cfg: String,
    environment: Map<String, String>,
    osWire: String,
    requirement: AotModeRequirement,
) {
    val underscore = jvmOptionTokens(environment["_JAVA_OPTIONS"].orEmpty(), "_JAVA_OPTIONS")
    val jdkJava = jvmOptionTokens(environment["JDK_JAVA_OPTIONS"].orEmpty(), "JDK_JAVA_OPTIONS")
    val toolOptions = jvmOptionTokens(environment["JAVA_TOOL_OPTIONS"].orEmpty(), "JAVA_TOOL_OPTIONS")
    val cfgOptions = jpackageCfgJavaOptions(cfg, osWire)

    val property =
        effectiveAotSetting(
            "-D${requirement.property}",
            underscore,
            cfgOptions,
            jdkJava,
            toolOptions,
        )
    if (property == null || property.value != requirement.requiredValue) {
        throw GradleException(
            "the required mode would not run: " +
                "${property?.source ?: "no option"} sets " +
                "-D${requirement.property}=${property?.value ?: "unset"}, " +
                "not ${requirement.requiredValue} — refusing to launch ordinary main",
        )
    }
    for ((name, value) in requirement.options) {
        val option = effectiveAotSetting(name, underscore, cfgOptions, jdkJava, toolOptions)
        if (option == null || option.value != value) {
            throw GradleException(
                "the required mode would not run: " +
                    "${option?.source ?: "no option"} sets $name=${option?.value ?: "unset"}, " +
                    "not $value — refusing to launch ordinary main",
            )
        }
    }
}

/**
 * The effective setting of [name] under the JVM's precedence: `_JAVA_OPTIONS` first
 * (it overrides the command line), then the command line's own order (the .cfg after
 * `JDK_JAVA_OPTIONS`, so a later .cfg option wins), then `JAVA_TOOL_OPTIONS`. The last
 * setting in each source wins.
 */
private fun effectiveAotSetting(
    name: String,
    underscore: List<String>,
    cfgOptions: List<String>,
    jdkJava: List<String>,
    toolOptions: List<String>,
): AotOptionSetting? {
    underscore.lastAotSetting(name, "_JAVA_OPTIONS")?.let { return it }
    cfgOptions.lastAotSetting(name, "the .cfg")?.let { return it }
    jdkJava.lastAotSetting(name, "JDK_JAVA_OPTIONS")?.let { return it }
    return toolOptions.lastAotSetting(name, "JAVA_TOOL_OPTIONS")
}

/** The last token that sets [name], or null when none does. */
private fun List<String>.lastAotSetting(
    name: String,
    source: String,
): AotOptionSetting? {
    var result: AotOptionSetting? = null
    for (token in this) {
        val value = token.aotSettingValue(name) ?: continue
        result = AotOptionSetting(value, source)
    }
    return result
}

/** The value a `-Dproperty` / `-XX:Option` token sets, or null when it sets another name. */
private fun String.aotSettingValue(name: String): String? {
    if (this == name) return ""
    if (startsWith("$name=")) return substring(name.length + 1)
    return null
}

/**
 * Splits a JVM environment variable into option tokens the way the JVM does: single and
 * double quotes group text and are removed, backslash is literal, and an unmatched quote
 * is an error. The JVM refuses to start on an unmatched quote, so failing closed here
 * beats launching a process that cannot run the required mode.
 */
private fun jvmOptionTokens(
    value: String,
    source: String,
): List<String> {
    val tokens = mutableListOf<String>()
    var index = 0
    val length = value.length
    while (index < length) {
        while (index < length && value[index] in JVM_OPTION_WHITESPACE) index++
        if (index >= length) break
        val token = StringBuilder()
        var quote: Char? = null
        while (index < length) {
            val char = value[index]
            if (quote != null) {
                if (char == quote) quote = null else token.append(char)
                index++
            } else if (char == '\'' || char == '"') {
                quote = char
                index++
            } else if (char in JVM_OPTION_WHITESPACE) {
                break
            } else {
                token.append(char)
                index++
            }
        }
        if (quote != null) {
            throw GradleException("$source has an unbalanced quote: $value")
        }
        tokens += token.toString()
    }
    return tokens
}

/**
 * The active `java-options` values of a jpackage .cfg, in file order. The launcher reads
 * only the `[JavaOptions]` section (the section name runs from `[` to the last `]` of
 * the line), the key must be exactly `java-options`, and the value is everything after
 * the first `=`, whitespace and all. On POSIX a trailing CR stays part of the value,
 * which is how a CRLF line disables an intended property; Windows reads the file in
 * text mode and translates the CRLF away.
 */
internal fun jpackageCfgJavaOptions(
    cfg: String,
    osWire: String,
): List<String> {
    val options = mutableListOf<String>()
    var inJavaOptions = false
    for (rawLine in cfg.split('\n')) {
        val line = if (osWire == "windows") rawLine.removeSuffix("\r") else rawLine
        if (line.startsWith("[")) {
            inJavaOptions = jpackageSectionName(line) == "JavaOptions"
            continue
        }
        if (!inJavaOptions) continue
        val separator = line.indexOf('=')
        if (separator < 0) continue
        if (line.substring(0, separator) != "java-options") continue
        options += line.substring(separator + 1)
    }
    return options
}

/** The section name of a `[...]` line: the text between `[` and the last `]`. */
private fun jpackageSectionName(line: String): String? {
    if (!line.startsWith("[")) return null
    val end = line.lastIndexOf(']')
    if (end < 0) return null
    return line.substring(1, end)
}

/** The active values of a .cfg key in [section], in file order. */
private fun jpackageCfgValues(
    cfg: String,
    section: String,
    key: String,
    osWire: String,
): List<String> {
    val values = mutableListOf<String>()
    var inSection = false
    for (rawLine in cfg.split('\n')) {
        val line = if (osWire == "windows") rawLine.removeSuffix("\r") else rawLine
        if (line.startsWith("[")) {
            inSection = jpackageSectionName(line) == section
            continue
        }
        if (!inSection) continue
        val separator = line.indexOf('=')
        if (separator < 0) continue
        if (line.substring(0, separator) != key) continue
        values += line.substring(separator + 1)
    }
    return values
}

/**
 * The fixed timestamp the packaged image's files carry (1980-02-01T00:00:00Z, the ZIP
 * format's minimum). The AOT cache records the classpath JARs' modification times, and
 * the Gradle ZIP/tar archives preserve them only when the files carry a fixed timestamp.
 */
internal const val AOT_IMAGE_TIMESTAMP_MS = 318211200000L

/**
 * The classpath JARs the AOT cache records: the .cfg's active `app.classpath` entries
 * with `$APPDIR` expanded. Only these files' size and modification time are checked when
 * the cache is loaded (probed on the real JDK 25), so only these timestamps must survive
 * the archive round trip.
 */
internal fun aotClasspathJars(
    cfg: String,
    image: File,
    osWire: String,
): List<File> {
    val appDir = File(image, aotImageLayout(osWire).appDir)
    return jpackageCfgValues(cfg, "Application", "app.classpath", osWire)
        .map { value -> File(value.replace("\$APPDIR", appDir.absolutePath)) }
        .filter { it.isFile }
        .distinct()
}

/**
 * Sets every regular file and directory under [image] to the fixed [timestampMs], except
 * the [exclude] files. The whole image is normalized so the archive tasks can preserve the
 * timestamps (`isPreserveFileTimestamps = true`) while staying reproducible; the training
 * excludes the classpath JARs because the cache recorded their pre-training timestamps.
 * Symbolic links are skipped: the archive tasks dereference them, so the target's
 * timestamp is what ships.
 */
internal fun normalizeAotImageTimestamps(
    image: File,
    timestampMs: Long,
    exclude: Set<File> = emptySet(),
) {
    val time = FileTime.fromMillis(timestampMs)
    val excluded = exclude.map { it.absoluteFile.normalize() }.toSet()
    image.walkTopDown().forEach { file ->
        if (Files.isSymbolicLink(file.toPath())) return@forEach
        if (file.absoluteFile.normalize() in excluded) return@forEach
        try {
            Files.setLastModifiedTime(file.toPath(), time)
        } catch (t: Throwable) {
            throw GradleException("cannot normalize the image timestamp for $file: ${t.message}", t)
        }
    }
}

/** The Mach-O magics `codesign` cares about: fat and thin, both endians. */
private val MACHO_MAGICS =
    setOf(
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
        byteArrayOf(0xBE.toByte(), 0xBA.toByte(), 0xFE.toByte(), 0xCA.toByte()),
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()),
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()),
        byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),
        byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),
    )

/**
 * The ad-hoc reseal order after the archive lands in `Contents/app/` (11 macOS DMG, ad-hoc
 * signing): every Mach-O inside the bundle, deepest first, then the bundle itself last —
 * `codesign` seals code inside out. Data (JARs, the new archive, resources) is not signed.
 */
internal fun macAdHocResignTargets(app: File): List<File> {
    val machos =
        app
            .walkTopDown()
            .filter { it.isFile && it.isMacho() }
            .sortedByDescending { it.relativeTo(app).path.count { c -> c == '/' } }
            .toList()
    return machos + app
}

private fun File.isMacho(): Boolean {
    if (!isFile || length() < 4) return false
    val head = inputStream().use { it.readNBytes(4) }
    return MACHO_MAGICS.any { head.contentEquals(it) }
}

/**
 * Re-seals a macOS image ad hoc after a task rewrote payload inside the bundle (the AOT
 * archive, the trimmed skiko stub): every Mach-O inside out, the bundle last — the order
 * [macAdHocResignTargets] computes — then `codesign --verify --deep --strict`; the old
 * signature is never presumed valid.
 */
internal fun resignMacImageAdHoc(image: File) {
    for (target in macAdHocResignTargets(image)) {
        runChecked("codesign", "--force", "--sign", "-", target.absolutePath)
    }
    runChecked("codesign", "--verify", "--deep", "--strict", image.absolutePath)
}

private fun runChecked(vararg argv: String) {
    val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    if (process.waitFor() != 0) {
        throw GradleException("${argv.joinToString(" ")} failed:\n$output")
    }
}

/**
 * The image runtime's missing `bin/java`: `-XX:AOTCacheOutput` makes the JVM fork
 * `java.home/bin/java` at shutdown, but the jlink'd image runtime carries no native
 * commands (11 jlink modules). [provisionImageJava] copies the pinned runtime's own
 * launcher into the image for the duration of the training launch; [close] removes it
 * again on success, failure and cancellation, together with the `bin` directory the
 * provisioning created — only when that directory is empty, so a pre-existing directory
 * and anything the JVM wrote into it are preserved.
 */
internal class ProvisionedImageJava(
    private val java: File,
    private val binDir: File,
    private val binDirCreated: Boolean,
) : AutoCloseable {
    override fun close() {
        if (java.exists() && !java.delete()) {
            throw GradleException("cannot remove the temporary image java launcher $java")
        }
        if (binDirCreated && binDir.isDirectory && binDir.list().isNullOrEmpty()) {
            if (!binDir.delete() && binDir.exists()) {
                throw GradleException("cannot remove the temporary image bin directory $binDir")
            }
        }
    }
}

/**
 * Copies [sourceJava] (the pinned runtime's launcher) to [imageJava] inside the packaged
 * image, creating the image runtime's `bin` directory when absent. Fails closed rather
 * than overwriting or writing through a symbolic link: the destination must not exist, and
 * neither the source nor the destination path may carry a symbolic link below its trusted
 * root. The caller owns the returned handle and must [ProvisionedImageJava.close] it on
 * every path, including cancellation.
 */
internal fun provisionImageJava(
    imageRoot: File,
    imageJava: File,
    sourceJava: File,
    sourceRoot: File,
): ProvisionedImageJava {
    requireNoSymlink(imageJava, imageRoot, "image")
    requireNoSymlink(sourceJava, sourceRoot, "pinned runtime")
    if (!sourceJava.isFile) {
        throw GradleException("the pinned runtime carries no java launcher at $sourceJava")
    }
    if (imageJava.exists() || Files.isSymbolicLink(imageJava.toPath())) {
        throw GradleException("refusing to overwrite the image's existing java launcher $imageJava")
    }
    val binDir =
        imageJava.parentFile
            ?: throw GradleException("the image java launcher has no parent directory: $imageJava")
    val binDirCreated = !binDir.isDirectory
    if (binDirCreated && !binDir.mkdirs()) {
        throw GradleException("cannot create the image runtime bin directory $binDir")
    }
    try {
        Files.copy(sourceJava.toPath(), imageJava.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    } catch (t: Throwable) {
        if (binDirCreated) binDir.delete()
        throw GradleException("cannot copy the pinned java launcher to $imageJava: ${t.message}", t)
    }
    return ProvisionedImageJava(imageJava, binDir, binDirCreated)
}

/**
 * Fails closed when [target] or one of its ancestors below [root] is a symbolic link: the
 * copy must neither follow a link out of the image nor write through one.
 */
private fun requireNoSymlink(
    target: File,
    root: File,
    what: String,
) {
    val rootPath = root.absoluteFile.normalize()
    var current: File? = target.absoluteFile.normalize()
    while (current != null) {
        if (Files.isSymbolicLink(current.toPath())) {
            throw GradleException(
                "refusing to provision the $what java through a symbolic link: $current",
            )
        }
        if (current == rootPath) return
        current = current.parentFile
    }
    throw GradleException("the $what java path $target escapes its root $root")
}

/**
 * One training launch of the packaged app image (PO-42, 11 AOT cache). Registered against
 * `createDistributable`'s image — the same image every `package*` task consumes — so the
 * archive is part of every installer's payload. Runs only when `neutrodyne.installKind`
 * is set; a dev/assemble image stays flag-free and untrained.
 *
 * Declared inputs are the files that identify the training configuration — the launcher
 * (classpath + main class), its .cfg (the JVM options), the bundled runtime's `release`
 * file and java launcher (the pinned JDK), the runtime-classpath manifest (the image's
 * JARs) and the staged native libraries; the declared output is the archive itself. The
 * .cfg is swapped for the training options for the duration of the launch and restored
 * byte-exact afterwards — it is an input, not an output.
 */
abstract class TrainAotCacheTask : DefaultTask() {
    /** The app image root (`Neutrodyne[.app]`) — internal: the launch inputs below identify it. */
    @get:Internal
    abstract val appImage: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcher: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcherCfg: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeRelease: RegularFileProperty

    /**
     * The unpacked pinned runtime tree (11 Lockfiles) — internal: the trusted root the
     * source launcher is checked against, so the provisioning cannot follow a symlink out
     * of the verified tree. The launcher itself is the declared input.
     */
    @get:Internal
    abstract val runtimeHome: DirectoryProperty

    /** The pinned runtime's java launcher, copied into the image for the training launch. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeJavaSource: RegularFileProperty

    /** The runtime-classpath manifest `writeDesktopRuntimeClasspath` writes (the image's JARs). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspathManifest: RegularFileProperty

    /** The staged FFmpeg and ndmedia libraries the image ships and the training loads. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeLibraries: ConfigurableFileCollection

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val installKind: Property<String>

    @get:Input
    abstract val expectedInstallKinds: ListProperty<String>

    @get:OutputFile
    abstract val aotArchive: RegularFileProperty

    @TaskAction
    fun train() {
        train(System.getenv())
    }

    /**
     * The task action with an explicit environment, so the real-probe tests can exercise
     * the prelaunch admission and the launches without mutating the test JVM's own
     * environment.
     */
    internal fun train(environment: Map<String, String>) {
        val kind = installKind.get()
        val expected = expectedInstallKinds.get()
        if (kind !in expected) {
            throw GradleException(
                "trainAotCache: -Pneutrodyne.installKind=$kind is not one of ${expected.joinToString("/")} " +
                    "(11 install kinds)",
            )
        }
        val image = appImage.get().asFile
        val launcherFile = launcher.get().asFile
        if (!launcherFile.isFile) {
            throw GradleException("packaged launcher missing: $launcherFile")
        }
        val cfgFile = launcherCfg.get().asFile
        if (!cfgFile.isFile) {
            throw GradleException("packaged launcher config missing: $cfgFile")
        }
        if (!runtimeRelease.get().asFile.isFile) {
            throw GradleException("bundled runtime missing in the image: ${runtimeRelease.get().asFile}")
        }

        val archive = aotArchive.get().asFile
        archive.delete()

        val originalBytes = cfgFile.readBytes()
        val productionCfg = String(originalBytes, Charsets.UTF_8)
        val trainingCfg = aotTrainingCfg(productionCfg)

        // The training launch's prelaunch admission: a highest-precedence _JAVA_OPTIONS
        // (or a malformed variable) must never let ordinary main run with the real
        // AppDirs before the missing report is noticed.
        checkAotModeAdmission(
            cfg = trainingCfg,
            environment = environment,
            osWire = targetOs.get(),
            requirement = AotModeRequirement(AOT_TRAINING_PROPERTY, "true"),
        )

        // The cache records the classpath JARs' timestamps; normalize the image before
        // the training launch so the archive round trip can preserve them.
        normalizeAotImageTimestamps(image, AOT_IMAGE_TIMESTAMP_MS)

        // The training JVM's `-XX:AOTCacheOutput` forks `java.home/bin/java` at shutdown,
        // which the jlink'd image runtime lacks; the pinned runtime's own launcher stands
        // in for the launch and comes out again on every path (success, failure, cancel).
        val provisioned =
            provisionImageJava(
                imageRoot = image,
                imageJava = File(image, imageRuntimeJava(targetOs.get())),
                sourceJava = runtimeJavaSource.get().asFile,
                sourceRoot = runtimeHome.get().asFile,
            )
        try {
            // The training launch needs the write flag where the read flag sits; the file's
            // original bytes go back on every path — the .cfg is a declared input of this task.
            cfgFile.writeBytes(trainingCfg.toByteArray(Charsets.UTF_8))
            try {
                runTrainingLaunch(launcherFile, image, environment)
            } finally {
                cfgFile.writeBytes(originalBytes)
            }
        } finally {
            provisioned.close()
        }
        if (!archive.isFile || archive.length() == 0L) {
            throw GradleException("the training run left no usable AOT archive at $archive")
        }
        logger.lifecycle("trainAotCache: ${archive.length()} bytes at $archive")

        // The packaged acceptance: the archive is only proven once the image's own launcher
        // loads it under `-XX:AOTMode=on`, which fails before main on an unusable archive.
        // The strict smoke launch's prelaunch admission runs before that launch, with the
        // JAVA_TOOL_OPTIONS this launch will actually carry.
        val smokeJavaToolOptions =
            buildString {
                val existing = environment["JAVA_TOOL_OPTIONS"]
                if (!existing.isNullOrBlank()) {
                    append(existing)
                    append(' ')
                }
                append(AOT_MODE_STRICT_OPTION)
                append(' ')
                append(SMOKE_PROPERTY_OPTION)
            }
        val smokeEnvironment = environment + mapOf("JAVA_TOOL_OPTIONS" to smokeJavaToolOptions)
        checkAotModeAdmission(
            cfg = productionCfg,
            environment = smokeEnvironment,
            osWire = targetOs.get(),
            requirement = AotModeRequirement(SMOKE_PROPERTY, "true", listOf(AOT_MODE_OPTION to "on")),
        )
        verifyStrictCacheLoad(launcherFile, image, smokeEnvironment)

        if (targetOs.get() == "macos") {
            resignAdHoc(image)
        }

        // The training created the archive, touched the .cfg and the provisioned bin
        // directory, and the reseal rewrote the Mach-O files; normalize those too, but
        // never the classpath JARs whose timestamps the cache just recorded. Doing this
        // last keeps every shipped file at the fixed timestamp, so the archives stay
        // reproducible while the cache's recorded times survive the round trip.
        normalizeAotImageTimestamps(
            image,
            AOT_IMAGE_TIMESTAMP_MS,
            exclude = aotClasspathJars(productionCfg, image, targetOs.get()).toSet(),
        )
    }

    /**
     * Launches the image's own launcher with the restored production .cfg and
     * `-XX:AOTMode=on` in `JAVA_TOOL_OPTIONS`, so the JVM fails before main when the
     * trained archive cannot be mapped (JDK 25). The app's smoke mode then reports the
     * cache state; a strict run that reaches the report proves the archive was used, so
     * `aotCache` must be `in-use`. A pre-existing `JAVA_TOOL_OPTIONS` is preserved.
     */
    private fun verifyStrictCacheLoad(
        launcherFile: File,
        image: File,
        environment: Map<String, String>,
    ) {
        val argv =
            aotLauncherCommand(
                targetOs.get(),
                launcherFile.absolutePath,
                hasDisplay(),
                onPath("xvfb-run"),
                onPath("dbus-run-session"),
            )
        logger.lifecycle("trainAotCache: strict cache load ${argv.joinToString(" ")}")
        val logFile = File.createTempFile("aot-strict", ".log")
        val builder =
            ProcessBuilder(argv)
                .directory(image)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
        builder.environment().apply { environment.forEach { (key, value) -> put(key, value) } }
        val process =
            try {
                builder.start()
            } catch (t: Throwable) {
                logFile.delete()
                throw t
            }
        val exit =
            try {
                if (!process.waitFor(TRAINING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    throw GradleException(
                        "the strict AOT cache load did not finish within $TRAINING_TIMEOUT_MINUTES min",
                    )
                }
                process.exitValue()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        val output = logFile.readText().also { logFile.delete() }
        if (exit != 0) {
            throw GradleException(
                "the strict AOT cache load exited $exit:\n${output.takeLast(LOG_TAIL_CHARS)}",
            )
        }
        val report =
            output.lineSequence().firstOrNull { it.startsWith(SMOKE_REPORT_PREFIX) }
                ?: throw GradleException(
                    "the strict AOT cache load produced no $SMOKE_REPORT_PREFIX line:\n" +
                        output.takeLast(LOG_TAIL_CHARS),
                )
        if ("\"failed\"" in report) {
            throw GradleException("the strict cache load's smoke report failed: $report")
        }
        val json =
            runCatching {
                groovy.json
                    .JsonSlurper()
                    .parseText(report.removePrefix(SMOKE_REPORT_PREFIX)) as? Map<*, *>
            }.getOrNull()
        val aotCache = json?.get("aotCache")?.toString()
        if (aotCache != "in-use") {
            throw GradleException(
                "the strict cache load did not prove the archive was used (aotCache=$aotCache): $report",
            )
        }
        logger.lifecycle(report)
    }

    private fun runTrainingLaunch(
        launcherFile: File,
        image: File,
        environment: Map<String, String>,
    ) {
        val argv =
            aotLauncherCommand(
                targetOs.get(),
                launcherFile.absolutePath,
                hasDisplay(),
                onPath("xvfb-run"),
                onPath("dbus-run-session"),
            )
        logger.lifecycle("trainAotCache: ${argv.joinToString(" ")}")
        // ProcessBuilder inherits this process's environment; the explicit environment
        // overlays it so a pre-existing JAVA_TOOL_OPTIONS reaches the training JVM
        // unchanged and the real-probe tests can supply one. The training options travel
        // through the swapped .cfg like every other launcher option. Output goes to a
        // file, not a pipe: a stalled child cannot deadlock the waitFor.
        val logFile = File.createTempFile("aot-training", ".log")
        val builder =
            ProcessBuilder(argv)
                .directory(image)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
        builder.environment().apply { environment.forEach { (key, value) -> put(key, value) } }
        val process =
            try {
                builder.start()
            } catch (t: Throwable) {
                logFile.delete()
                throw t
            }
        val exit =
            try {
                if (!process.waitFor(TRAINING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    throw GradleException(
                        "the AOT training run did not finish within $TRAINING_TIMEOUT_MINUTES min",
                    )
                }
                process.exitValue()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        val output = logFile.readText().also { logFile.delete() }
        output
            .lineSequence()
            .filter { it.startsWith(AOT_TRAINING_REPORT_PREFIX) }
            .forEach { logger.lifecycle(it) }
        if (exit != 0) {
            throw GradleException(
                "the AOT training run exited $exit:\n${output.takeLast(LOG_TAIL_CHARS)}",
            )
        }
        val report =
            output.lineSequence().firstOrNull { it.startsWith(AOT_TRAINING_REPORT_PREFIX) }
                ?: throw GradleException(
                    "the AOT training run produced no $AOT_TRAINING_REPORT_PREFIX line:\n" +
                        output.takeLast(LOG_TAIL_CHARS),
                )
        if ("\"failed\"" in report) {
            throw GradleException("the AOT training journey failed: $report")
        }
    }

    /** A display is always reachable on macOS/Windows runners; Linux needs DISPLAY or Wayland. */
    private fun hasDisplay(): Boolean =
        targetOs.get() != "linux" ||
            !System.getenv("DISPLAY").isNullOrBlank() ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

    private fun onPath(executable: String): Boolean =
        System
            .getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.any { dir -> File(dir, executable).canExecute() } == true

    /**
     * The archive inside `Contents/app/` breaks jpackage's ad-hoc seal (11 macOS DMG, ad-hoc
     * signing): re-sign every Mach-O inside out and the bundle itself, then verify before
     * `packageDmg`/`mac-zip.sh` archives it — the old signature is never presumed valid.
     */
    private fun resignAdHoc(image: File) {
        resignMacImageAdHoc(image)
    }

    private companion object {
        /** The app's own watchdog halts a stuck run after 60 s; this is the outer bound. */
        private const val TRAINING_TIMEOUT_MINUTES = 3L
        private const val LOG_TAIL_CHARS = 4000
    }
}

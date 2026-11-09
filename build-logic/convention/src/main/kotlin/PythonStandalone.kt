// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.inject.Inject

/**
 * The desktop engine's python-build-standalone runtime (11 CPython selection and pins, Trim list
 * and checks): `fetchPythonStandalone` downloads the current target's `install_only_stripped`
 * archive from the exact release URL, bounds and checks its SHA-256 against
 * `python-components.lock`; `trimPythonStandalone` runs `scripts/engine/trim-python.sh` over it.
 * Both are registered for `:youtube:ytdlp-desktop` by [registerPythonStandalone]. The staging
 * into the desktop image's resources (`desktopApp/build/desktop-resources`) is `:desktopApp`'s
 * `syncDesktopIntegrationResources`.
 */
internal fun Project.registerPythonStandalone() {
    val target = desktopPackagingTargetOf(System.getProperty("os.name"), System.getProperty("os.arch"))
    if (target == null) {
        // Off the packaging matrix (a developer's Intel Mac, a Windows arm64 laptop) the tasks would
        // fetch a target this machine cannot run; the native tasks refuse the same way (11 Platform
        // matrix). Local development uses the PATH seam instead.
        for (name in listOf("fetchPythonStandalone", "trimPythonStandalone")) {
            tasks.register(name) {
                group = "build"
                description = "Refuses off 11's packaging matrix (the PBS runtime fetches per-target)."
                doLast {
                    throw GradleException(
                        "$name: this host is outside 11's packaging matrix; fetch and trim the " +
                            "PBS runtime on the matching runner (or use the PATH seam for tests)",
                    )
                }
            }
        }
        return
    }

    val lockFile = layout.projectDirectory.file("python-components.lock")
    val pythonStandalone = layout.buildDirectory.dir("python-standalone/${target.id}")

    val fetch =
        tasks.register<FetchPythonStandalone>("fetchPythonStandalone") {
            group = "build"
            description =
                "Downloads the pinned python-build-standalone archive for ${target.id} and checks " +
                "its SHA-256 against python-components.lock (11 CPython selection and pins)."
            this.lockFile.set(lockFile)
            targetId.set(target.id)
            archive.set(pythonStandalone.map { it.file("archive") })
        }

    val trimScript = rootProject.layout.projectDirectory.file("scripts/engine/trim-python.sh")
    tasks.register<TrimPythonStandalone>("trimPythonStandalone") {
        group = "build"
        description =
            "Trims the fetched python-build-standalone archive with scripts/engine/trim-python.sh " +
            "(11 Trim list and checks)."
        dependsOn(fetch)
        archive.set(fetch.flatMap { it.archive })
        this.trimScript.set(trimScript)
        // A subdirectory, so trimming never deletes the fetch task's archive output.
        output.set(pythonStandalone.map { it.dir("trimmed") })
    }
}

/** Downloads the lock's archive for one target and verifies its SHA-256 (11 CPython selection). */
abstract class FetchPythonStandalone : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:Input
    abstract val targetId: Property<String>

    /** The verified archive, kept without an extension (every target ships gzip'd tar). */
    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun fetch() {
        val lock = PythonLicencePolicy.parse(lockFile.get().asFile.readText())
        val target =
            lock.targets.singleOrNull { it.id == targetId.get() }
                ?: throw GradleException(
                    "python-components.lock: no [[target]] entry for '${targetId.get()}'",
                )
        if (lock.pbs.isNullOrBlank()) {
            throw GradleException("python-components.lock: missing `pbs` release tag")
        }
        val url =
            "https://github.com/astral-sh/python-build-standalone/releases/download/" +
                "${lock.pbs}/${target.archive}"
        downloadIfChanged(url, archive.get().asFile, target.sha256)
        verifyChecksum(archive.get().asFile, target.sha256)
    }

    /** Downloads only when the local file is absent or its checksum no longer matches the lock. */
    private fun downloadIfChanged(
        url: String,
        target: File,
        expectedSha256: String,
    ) {
        if (target.isFile && sha256Of(target) == expectedSha256) return
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".download")
        logger.lifecycle("fetchPythonStandalone: downloading $url")
        try {
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build()
                .use { client ->
                    val request =
                        HttpRequest
                            .newBuilder(URI.create(url))
                            .timeout(java.time.Duration.ofMinutes(10))
                            .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299) {
                        throw GradleException(
                            "downloading the pinned PBS archive failed: HTTP ${response.statusCode()}",
                        )
                    }
                    // The archive is a bounded asset: a response that grows past MAX_BYTES is
                    // rejected before its bytes ever reach the output (11 CPython selection).
                    response.body().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) {
                                    throw GradleException(
                                        "the pinned PBS archive exceeded $MAX_BYTES bytes; refusing unbounded content",
                                    )
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
        } catch (failure: Exception) {
            tmp.delete()
            throw failure
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw GradleException("cannot move the downloaded PBS archive to ${target.absolutePath}")
        }
    }

    private fun verifyChecksum(
        archive: File,
        expected: String,
    ) {
        val actual = sha256Of(archive)
        if (actual != expected) {
            throw GradleException(
                "python-components.lock: SHA-256 mismatch for ${archive.name}: expected $expected, got $actual",
            )
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val BUFFER_BYTES = 1 shl 16

        /** 256 MiB, far above today's ~36 MiB archives; the pin fails rather than grow. */
        private const val MAX_BYTES = 256L * 1024 * 1024
    }
}

/**
 * The argv [TrimPythonStandalone.trim] execs: bare `bash` on Unix; on Windows the located Git
 * Bash (MSYS2 as fallback) plus the script, archive and output as MSYS paths. A `D:\` argument
 * would reach tar as `host:file` remote syntax. Resolving at execution time keeps the lookup
 * out of the configuration cache's inputs.
 */
internal fun trimShellCommand(
    osName: String,
    env: Map<String, String>,
    isFile: (String) -> Boolean,
    script: String,
    archive: String,
    out: String,
): List<String> {
    if (!isWindowsHost(osName)) return listOf("bash", script, archive, out)
    val candidates = windowsBashCandidates(env, BashPreference.GIT_BASH, isFile)
    val bash =
        candidates.firstOrNull(isFile)
            ?: throw GradleException(
                "trimPythonStandalone: no usable bash on this Windows host; checked " +
                    candidates.joinToString(", ") +
                    ". Install Git for Windows or MSYS2, or put git on PATH. " +
                    "C:\\Windows\\System32\\bash.exe is WSL's launcher and cannot run " +
                    "scripts/engine/trim-python.sh (11 Trim list and checks)",
            )
    return listOf(bash, msysPath(script), msysPath(archive), msysPath(out))
}

/** Runs `scripts/engine/trim-python.sh` over the fetched archive (11 Trim list and checks). */
abstract class TrimPythonStandalone : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val trimScript: RegularFileProperty

    /** The trimmed tree under `python/` plus `cacert.pem` beside it. */
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun trim() {
        val out = output.get().asFile
        fileSystemOperations.delete { delete(out) }
        out.mkdirs()
        val script = trimScript.get().asFile
        if (!script.isFile) {
            throw GradleException("missing trim script $script (11 Trim list and checks)")
        }
        val archiveFile = archive.get().asFile
        val argv =
            trimShellCommand(
                System.getProperty("os.name"),
                System.getenv(),
                { File(it).isFile },
                script.absolutePath,
                archiveFile.absolutePath,
                out.absolutePath,
            )
        execOperations
            .exec { commandLine(*argv.toTypedArray()) }
            .assertNormalExitValue()
        val python = File(out, "python")
        if (!python.isDirectory) {
            throw GradleException("trim-python.sh produced no python/ tree under ${out.absolutePath}")
        }
        if (!File(out, "cacert.pem").isFile) {
            throw GradleException("trim-python.sh produced no cacert.pem under ${out.absolutePath}")
        }
    }
}

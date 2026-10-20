// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Main-process bundled-only store. loadStatus reads small records, never opens the ZIP or Python. */
class BundledEngineStore(
    private val paths: EngineStorePaths,
    private val appVersionCode: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EngineStore {
    private val mutablePreparation = MutableStateFlow(EnginePreparation.UNPREPARED)
    override val preparation = mutablePreparation.asStateFlow()

    @Volatile private var prepared: PreparedEngine? = null
    private val root get() = paths.root.toFile()

    override fun hostLibDir(): Path? = prepared?.libDir

    override suspend fun loadStatus(): Unit =
        withContext(io) {
            processLock.withLock {
                val metadata = metadata()
                val expected = identity(metadata.version)
                val lib = library(metadata.version)
                val active = readRecord<ActiveBundle>(File(root, "active.json"))
                val compiled =
                    active?.matches(expected) == true &&
                        EngineCompileMarkers.matches(lib.path.toPath(), expected)
                if (compiled) {
                    prepared = PreparedEngine(lib.path.toPath(), expected)
                    mutablePreparation.value = EnginePreparation.COMPILED
                } else if (
                    mutablePreparation.value == EnginePreparation.COMPILED ||
                    EngineCompileMarkers.exists(lib.path.toPath(), expected.pythonMinor) ||
                    prepared?.marker != expected || prepared?.libDir?.toFile()?.isDirectory != true
                ) {
                    prepared = null
                    mutablePreparation.value = EnginePreparation.UNPREPARED
                }
            }
        }

    override suspend fun prepareBundled(): PreparedEngine =
        withContext(io) {
            processLock.withLock {
                val metadata = metadata()
                val expected = identity(metadata.version)
                val lib = library(metadata.version)
                prepared?.takeIf { it.marker == expected && lib.isDirectory }?.let { return@withLock it }
                prepared = null
                mutablePreparation.value = EnginePreparation.PREPARING
                try {
                    directory(root)
                    directory(File(root, "versions"))
                    directory(File(root, "staging"))
                    directory(paths.ytDlpCacheDir.toFile())
                    val active = readRecord<ActiveBundle>(File(root, "active.json"))
                    if (
                        active?.matches(expected) == true &&
                        EngineCompileMarkers.matches(lib.path.toPath(), expected)
                    ) {
                        return@withLock publish(lib, expected, EnginePreparation.COMPILED)
                    }
                    // A process which died during compile may have removed only some .py files. Rebuild
                    // from the trusted APK, rather than importing a mixed Python-minor or partial tree.
                    val staging = File(root, "staging/bundled")
                    removeTree(staging)
                    directory(staging)
                    val archive = File(staging, "yt-dlp")
                    copyBundle(archive, metadata.sha256)
                    BundledZip.extract(archive, File(staging, "lib"), metadata.version, metadata.ejsVersion)
                    require(archive.setReadOnly())
                    val versionDir = lib.parentFile
                    removeTree(versionDir)
                    Files.move(staging.toPath(), versionDir.toPath(), ATOMIC_MOVE)
                    val record = ActiveBundle(metadata.version, metadata.version, StoredMarker.of(expected))
                    writeRecord(File(root, "active.json"), record)
                    File(root, "versions").listFiles()?.filter { it.name != metadata.version }?.forEach(::removeTree)
                    publish(lib, expected, EnginePreparation.PREPARING)
                } catch (cancelled: CancellationException) {
                    prepared = null
                    mutablePreparation.value = EnginePreparation.UNPREPARED
                    throw cancelled
                } catch (failure: Exception) {
                    prepared = null
                    mutablePreparation.value = EnginePreparation.FAILED
                    throw failure
                }
            }
        }

    private fun publish(
        lib: File,
        identity: CompileMarker,
        state: EnginePreparation,
    ): PreparedEngine =
        PreparedEngine(lib.path.toPath(), identity).also {
            prepared = it
            mutablePreparation.value = state
        }

    private fun identity(version: String): CompileMarker {
        require(appVersionCode > 0 && Regex("[0-9]+\\.[0-9]+").matches(paths.pythonMinor))
        return CompileMarker(appVersionCode, version, paths.pythonMinor)
    }

    private fun library(version: String): File = File(root, "versions/$version/lib")

    private fun metadata(): BundleMetadata {
        val text = paths.bundledJson()
        require(text.length <= RECORD_LIMIT)
        return Json.decodeFromString<BundleMetadata>(text).also {
            require(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}").matches(it.version) && it.tag == it.version)
            require(Regex("[0-9a-f]{64}").matches(it.sha256))
            require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it.ejsVersion))
        }
    }

    private fun copyBundle(
        destination: File,
        sha256: String,
    ) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        paths.openBundledZip().buffer().use { source ->
            FileOutputStream(destination).use { output ->
                val bytes = ByteArray(8192)
                while (true) {
                    val count = source.read(bytes)
                    if (count < 0) break
                    size += count
                    require(size <= BundledZip.MAX_ZIP_BYTES) { "Bundle size limit" }
                    digest.update(bytes, 0, count)
                    output.write(bytes, 0, count)
                }
                output.fd.sync()
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actualHash == sha256) { "Bundle hash mismatch" }
    }

    private companion object {
        // Exactly one main-process writer, including separately constructed stores in tests.
        val processLock = Mutex()
    }
}

/** W4 calls write only after successful worker-thread compile/import, with legacy .pyc layout.
 * No Python is started here. The marker is last, so an interrupted compile is never treated ready.
 */
object EngineCompileMarkers {
    fun write(prepared: PreparedEngine) {
        val lib = prepared.libDir.toFile()
        require(noSymlinkPath(lib))
        require(compiledFiles.all { File(lib, it).isFile && !Files.isSymbolicLink(File(lib, it).toPath()) })
        require(lib.walkTopDown().none { it.extension == "py" || Files.isSymbolicLink(it.toPath()) }) {
            "Compile must remove source files"
        }
        lib.walkBottomUp().filter { it != lib }.forEach {
            require(it.setReadOnly()) { "Cannot protect compiled engine" }
        }
        writeRecord(marker(lib, prepared.marker.pythonMinor), StoredMarker.of(prepared.marker), RecordMode.READ_ONLY)
        require(lib.setReadOnly()) { "Cannot protect compiled directory" }
    }

    internal fun exists(
        lib: Path,
        minor: String,
    ): Boolean = Files.exists(marker(lib.toFile(), minor).toPath(), NOFOLLOW_LINKS)

    internal fun matches(
        lib: Path,
        identity: CompileMarker,
    ): Boolean {
        val directory = lib.toFile()
        return readRecord<StoredMarker>(marker(directory, identity.pythonMinor)) == StoredMarker.of(identity) &&
            compiledFiles.all { Files.isRegularFile(File(directory, it).toPath(), NOFOLLOW_LINKS) }
    }

    private fun marker(
        lib: File,
        minor: String,
    ): File {
        require(Regex("[0-9]+\\.[0-9]+").matches(minor))
        return File(lib, ".compiled-$minor")
    }

    private val compiledFiles =
        listOf("yt_dlp/__init__.pyc", "yt_dlp/version.pyc", "yt_dlp_ejs/__init__.pyc", "yt_dlp_ejs/_version.pyc")
}

@Serializable
private data class BundleMetadata(
    val version: String,
    val tag: String,
    val sha256: String,
    val ejsVersion: String,
)

@Serializable
private data class ActiveBundle(
    val current: String,
    val bundled: String,
    val identity: StoredMarker,
) {
    fun matches(expected: CompileMarker): Boolean =
        current == expected.engineVersion &&
            bundled == expected.engineVersion && identity == StoredMarker.of(expected)
}

@Serializable
private data class StoredMarker(
    val appVersionCode: Int,
    val engineVersion: String,
    val pythonMinor: String,
) {
    companion object {
        fun of(marker: CompileMarker) = StoredMarker(marker.appVersionCode, marker.engineVersion, marker.pythonMinor)
    }
}

private const val RECORD_LIMIT = 4096L

private inline fun <reified T> readRecord(file: File): T? {
    if (!noSymlinkPath(file) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return null
    if (file.length() > RECORD_LIMIT) return null
    return try {
        Json.decodeFromString<T>(file.readText())
    } catch (_: Exception) {
        null
    }
}

private enum class RecordMode { MUTABLE, READ_ONLY }

private inline fun <reified T> writeRecord(
    file: File,
    value: T,
    mode: RecordMode = RecordMode.MUTABLE,
) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    Files.deleteIfExists(temporary.toPath())
    FileOutputStream(temporary).use { output ->
        output.write(Json.encodeToString(value).toByteArray())
        output.fd.sync()
    }
    if (mode == RecordMode.READ_ONLY) require(temporary.setReadOnly())
    Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}

private fun noSymlinkPath(file: File): Boolean =
    generateSequence(file.toPath().toAbsolutePath()) { it.parent }.none { Files.isSymbolicLink(it) }

private fun directory(file: File) {
    require(noSymlinkPath(file)) { "Engine path contains a symlink" }
    Files.createDirectories(file.toPath())
}

private fun removeTree(file: File) {
    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
    require(!Files.isSymbolicLink(file.toPath())) { "Engine path contains a symlink" }
    file
        .walkTopDown()
        .onEnter {
            require(!Files.isSymbolicLink(it.toPath())) { "Engine path contains a symlink" }
            require(it.setWritable(true, true))
            true
        }.forEach {
            require(!Files.isSymbolicLink(it.toPath())) { "Engine path contains a symlink" }
            require(it.setWritable(true, true))
        }
    file.walkBottomUp().forEach { Files.delete(it.toPath()) }
}

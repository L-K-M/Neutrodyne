// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
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

/**
 * Main-process engine store (04 Engine updates): `active.json` names `current`/`previous`, the
 * manifest sequence and `rejected[]`; `versions/` holds bundled plus up to two downloaded trees;
 * `staging/` is scratch emptied by every update run. Atomic record + directory writes keep a
 * crash anywhere mid-flow resumable. The self-test trial is in-memory only: a process that dies
 * mid-trial leaves the candidate staged and the record untouched.
 */
class BundledEngineStore(
    private val paths: EngineStorePaths,
    private val appVersionCode: Int,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EngineStore {
    private val mutablePreparation = MutableStateFlow(EnginePreparation.UNPREPARED)
    override val preparation = mutablePreparation.asStateFlow()

    private val mutableActive = MutableStateFlow(EngineActive("", "", null, 0))
    override val active = mutableActive.asStateFlow()

    @Volatile private var prepared: PreparedEngine? = null

    @Volatile private var trial: PreparedEngine? = null
    private var trialPreparation = EnginePreparation.UNPREPARED
    private val root get() = paths.root.toFile()

    override fun hostPrepared(): PreparedEngine? = trial ?: prepared

    override suspend fun loadStatus(): Unit =
        withContext(io) {
            processLock.withLock {
                val metadata = metadata()
                val expected = identity(metadata.version)
                var record = readRecord<ActiveRecord>(File(root, "active.json"))
                var changed = record == null
                var current = record?.current?.takeIf { versionPattern.matches(it) } ?: metadata.version
                var previous = record?.previous?.takeIf { versionPattern.matches(it) }
                val bundledReady =
                    record?.identity == StoredMarker.of(expected) &&
                        EngineCompileMarkers.matches(library(metadata.version).path.toPath(), expected)
                if (current != metadata.version && !downloadedUsable(current)) {
                    removeTree(versionDir(current))
                    current = previous?.takeIf { it == metadata.version || downloadedUsable(it) } ?: metadata.version
                    previous = null
                    changed = true
                }
                if (previous != null && previous != metadata.version && !downloadedUsable(previous)) {
                    removeTree(versionDir(previous))
                    previous = null
                    changed = true
                }
                if (record == null || changed || record.bundled != metadata.version ||
                    record.identity != StoredMarker.of(expected)
                ) {
                    record =
                        (record ?: ActiveRecord(metadata.version, metadata.version, StoredMarker.of(expected)))
                            .copy(
                                current = current,
                                bundled = metadata.version,
                                identity = StoredMarker.of(expected),
                                previous = previous,
                                activation = null,
                                rejected = record?.rejected?.filter { unexpired(it.atMs) } ?: emptyList(),
                            )
                    // loadStatus never creates the engine tree; only an existing record dir is
                    // repaired in place, so a first boot still leaves no on-disk footprint.
                    if (File(root, "active.json").parentFile.isDirectory) {
                        writeRecord(File(root, "active.json"), record)
                    }
                }
                val next = preparedFor(current, metadata, expected, bundledReady)
                if (next != null) {
                    prepared = next
                    mutablePreparation.value = EnginePreparation.COMPILED
                } else {
                    // A tree extracted but not yet compiled by the host stays servable while it
                    // still names the record's current; a torn compile marker never does.
                    val keep =
                        prepared?.let {
                            it.marker.engineVersion == current && it.libDir.toFile().isDirectory &&
                                !EngineCompileMarkers.exists(it.libDir, it.marker.pythonMinor)
                        } ?: false
                    if (!keep) prepared = null
                    if (prepared == null && mutablePreparation.value == EnginePreparation.COMPILED) {
                        mutablePreparation.value = EnginePreparation.UNPREPARED
                    }
                }
                publishActive(record)
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
                    val bundledReady = EngineCompileMarkers.matches(lib.path.toPath(), expected)
                    if (!bundledReady) {
                        // A process which died during compile may have removed only some .py files.
                        // Rebuild from the trusted APK, never importing a mixed or partial tree.
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
                    }
                    val record = repairRecord(readRecord(File(root, "active.json")), metadata, expected)
                    writeRecord(File(root, "active.json"), record)
                    pruneVersions(setOf(record.bundled, record.current) + listOfNotNull(record.previous))
                    publishActive(record)
                    val preparedForRecord = preparedFor(record.current, metadata, expected, bundledReady)
                    if (preparedForRecord != null) {
                        publish(
                            preparedForRecord.libDir.toFile(),
                            preparedForRecord.marker,
                            EnginePreparation.COMPILED,
                        )
                    } else {
                        // Fresh bundled tree without a compile marker; the host compiles on bind.
                        publish(lib, expected, EnginePreparation.PREPARING)
                    }
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

    /**
     * M9b: merge the record with what is actually usable after the bundled prepare. A newer app
     * whose bundled version is at or above the current one takes over and drops downloaded trees
     * below it; a torn downloaded current/previous falls back and is removed.
     */
    private fun repairRecord(
        existing: ActiveRecord?,
        metadata: BundleMetadata,
        expected: CompileMarker,
    ): ActiveRecord {
        val record =
            existing?.copy(
                identity = StoredMarker.of(expected),
                bundled = metadata.version,
                rejected = existing.rejected.filter { unexpired(it.atMs) },
            ) ?: ActiveRecord(metadata.version, metadata.version, StoredMarker.of(expected))
        var current = record.current.takeIf { versionPattern.matches(it) } ?: metadata.version
        var previous = record.previous?.takeIf { versionPattern.matches(it) }
        var activation = record.activation
        if (compareVersions(metadata.version, current) >= 0) {
            current = metadata.version
            previous = null
            activation = null
        } else {
            if (!downloadedUsable(current)) {
                removeTree(versionDir(current))
                current = previous?.takeIf { it == metadata.version || downloadedUsable(it) } ?: metadata.version
                previous = null
                activation = null
            }
            if (previous != null && previous != metadata.version && !downloadedUsable(previous)) {
                removeTree(versionDir(previous))
                previous = null
            }
        }
        if (current == metadata.version) activation = null
        return record.copy(current = current, previous = previous, activation = activation)
    }

    override suspend fun cleanStaging() {
        withContext(io) {
            processLock.withLock {
                removeTree(File(root, "staging"))
            }
        }
    }

    override suspend fun stageVersion(
        version: String,
        archive: ByteArray,
        ejsVersion: String?,
        sequence: Long,
    ): PreparedEngine =
        withContext(io) {
            processLock.withLock {
                val metadata = metadata()
                require(versionPattern.matches(version) && version != metadata.version) {
                    "Bad staged engine version"
                }
                directory(root)
                directory(File(root, "versions"))
                directory(File(root, "staging"))
                val versionDir = versionDir(version)
                val candidate = PreparedEngine(File(versionDir, "lib").path.toPath(), markerFor(version))
                if (!versionDir.isDirectory) {
                    val staging = File(root, "staging/$version")
                    removeTree(staging)
                    directory(staging)
                    val archiveFile = File(staging, "yt-dlp")
                    archiveFile.writeBytes(archive)
                    require(archiveFile.setReadOnly())
                    BundledZip.extract(archiveFile, File(staging, "lib"), version, ejsVersion)
                    Files.move(staging.toPath(), versionDir.toPath(), ATOMIC_MOVE)
                }
                updateRecord { it.copy(lastManifestSequence = maxOf(it.lastManifestSequence, sequence)) }
                publishActive(readRecord<ActiveRecord>(File(root, "active.json"))!!)
                candidate
            }
        }

    override suspend fun recordManifestSequence(sequence: Long) {
        withContext(io) {
            processLock.withLock {
                updateRecord { it.copy(lastManifestSequence = maxOf(it.lastManifestSequence, sequence)) }
                publishActive(readRecord<ActiveRecord>(File(root, "active.json"))!!)
            }
        }
    }

    override suspend fun beginTrial(version: String): PreparedEngine =
        withContext(io) {
            processLock.withLock {
                val dir = File(versionDir(version), "lib")
                require(dir.isDirectory && noSymlinkPath(dir)) { "Staged engine missing" }
                val candidate = PreparedEngine(dir.path.toPath(), markerFor(version))
                trialPreparation = mutablePreparation.value
                trial = candidate
                // The trial binds a fresh host over this tree; only a tree that already
                // carries its compile marker is COMPILED. Anything else must first be
                // compiled by that host, so the bind owes the first-compile budget, not
                // the start budget (04 host contract). Cancel/commit restore from here.
                mutablePreparation.value =
                    if (downloadedUsable(version)) EnginePreparation.COMPILED else EnginePreparation.PREPARING
                publishActive(mutableActive.value.copy(trial = candidate))
                candidate
            }
        }

    override suspend fun abortTrial(
        version: String,
        reason: EngineRejectReason,
        sequence: Long,
        atMs: Long,
    ) {
        withContext(io) {
            processLock.withLock {
                trial = null
                mutablePreparation.value = trialPreparation
                removeTree(versionDir(version))
                updateRecord { record ->
                    record.copy(rejected = record.rejected.withRejection(version, atMs, sequence, reason))
                }
                publishActive(readRecord<ActiveRecord>(File(root, "active.json"))!!)
            }
        }
    }

    override suspend fun commitTrial(
        atMs: Long,
        previousLastSuccessAtMs: Long?,
        breakerWasOpen: Boolean,
    ) {
        withContext(io) {
            processLock.withLock {
                val candidate = trial ?: error("No trial to commit")
                val version = candidate.marker.engineVersion
                updateRecord { record ->
                    record.copy(
                        current = version,
                        previous = record.current,
                        activation = StoredActivation(atMs, previousLastSuccessAtMs, breakerWasOpen),
                    )
                }
                trial = null
                prepared = candidate
                mutablePreparation.value = EnginePreparation.COMPILED
                val record = readRecord<ActiveRecord>(File(root, "active.json"))!!
                pruneVersions(setOf(record.bundled, record.current) + listOfNotNull(record.previous))
                publishActive(record)
            }
        }
    }

    override suspend fun clearTrial() {
        withContext(io) {
            processLock.withLock {
                if (trial == null) return@withLock
                trial = null
                mutablePreparation.value = trialPreparation
                val record = readRecord<ActiveRecord>(File(root, "active.json"))
                if (record != null) {
                    publishActive(record)
                } else {
                    publishActive(mutableActive.value.copy(trial = null))
                }
            }
        }
    }

    override suspend fun rollback(
        reason: EngineRejectReason,
        atMs: Long,
    ): EngineActive =
        withContext(io) {
            processLock.withLock {
                trial = null
                val metadata = metadata()
                val expected = identity(metadata.version)
                val updated =
                    updateRecord { record ->
                        if (record.current == record.bundled) return@updateRecord record
                        val fallback =
                            record.previous?.takeIf { it == metadata.version || downloadedUsable(it) }
                                ?: metadata.version
                        if (record.previous != fallback) record.previous?.let { removeTree(versionDir(it)) }
                        removeTree(versionDir(record.current))
                        record.copy(
                            current = fallback,
                            previous = null,
                            activation = null,
                            rejected =
                                record.rejected.withRejection(
                                    record.current,
                                    atMs,
                                    record.lastManifestSequence,
                                    reason,
                                ),
                        )
                    }
                val bundledReady =
                    EngineCompileMarkers.matches(
                        library(metadata.version).path.toPath(),
                        expected,
                    )
                prepared = preparedFor(updated.current, metadata, expected, bundledReady)
                mutablePreparation.value =
                    if (prepared != null) EnginePreparation.COMPILED else EnginePreparation.UNPREPARED
                publishActive(updated)
                mutableActive.value
            }
        }

    override suspend fun resetToBundled() {
        withContext(io) {
            processLock.withLock {
                trial = null
                val metadata = metadata()
                val expected = identity(metadata.version)
                val updated = updateRecord { it.copy(current = it.bundled, activation = null) }
                val bundledReady =
                    EngineCompileMarkers.matches(library(metadata.version).path.toPath(), expected)
                prepared = preparedFor(updated.current, metadata, expected, bundledReady)
                mutablePreparation.value =
                    if (prepared != null) EnginePreparation.COMPILED else EnginePreparation.UNPREPARED
                publishActive(updated)
            }
        }
    }

    private fun updateRecord(transform: (ActiveRecord) -> ActiveRecord): ActiveRecord {
        val metadata = metadata()
        val expected = identity(metadata.version)
        val record =
            readRecord<ActiveRecord>(File(root, "active.json"))
                ?: ActiveRecord(metadata.version, metadata.version, StoredMarker.of(expected))
        val updated = transform(record).copy(bundled = metadata.version)
        writeRecord(File(root, "active.json"), updated)
        return updated
    }

    private fun publishActive(record: ActiveRecord) {
        mutableActive.value =
            EngineActive(
                current = record.current,
                bundled = record.bundled,
                previous = record.previous,
                lastManifestSequence = record.lastManifestSequence,
                rejected = record.rejected.map { RejectedEngine(it.version, it.atMs, it.sequence, it.reasonEnum()) },
                activation =
                    record.activation?.let {
                        EngineActivation(
                            it.atMs,
                            it.previousLastSuccessAtMs,
                            it.breakerWasOpen,
                        )
                    },
                trial = trial,
                staged = stagedVersion(record),
            )
    }

    private fun publishActive(active: EngineActive) {
        mutableActive.value = active.copy(staged = stagedVersion(active.bundled, active.current, active.previous))
    }

    private fun stagedVersion(record: ActiveRecord): String? =
        stagedVersion(record.bundled, record.current, record.previous)

    private fun stagedVersion(
        bundled: String,
        current: String,
        previous: String?,
    ): String? =
        File(root, "versions")
            .listFiles()
            ?.filter {
                it.isDirectory && versionPattern.matches(it.name) &&
                    it.name != bundled && it.name != current && it.name != previous
            }?.maxByOrNull { it.name }
            ?.name

    private fun preparedFor(
        current: String,
        metadata: BundleMetadata,
        expected: CompileMarker,
        bundledReady: Boolean,
    ): PreparedEngine? {
        if (current == metadata.version) {
            if (!bundledReady) return null
            return PreparedEngine(library(metadata.version).path.toPath(), expected)
        }
        val lib = library(current)
        if (!downloadedUsable(current)) return null
        return PreparedEngine(lib.path.toPath(), markerFor(current))
    }

    private fun downloadedUsable(version: String): Boolean {
        if (!versionPattern.matches(version)) return false
        val lib = library(version)
        val marker = EngineCompileMarkers.stored(lib.path.toPath(), paths.pythonMinor) ?: return false
        return marker.engineVersion == version && marker.pythonMinor == paths.pythonMinor && noSymlinkPath(lib)
    }

    private fun unexpired(atMs: Long): Boolean = clock.now() - atMs < REJECT_MS

    private fun List<StoredRejection>.withRejection(
        version: String,
        atMs: Long,
        sequence: Long,
        reason: EngineRejectReason,
    ): List<StoredRejection> =
        (
            filter { it.version != version && unexpired(it.atMs) } +
                StoredRejection(version, atMs, sequence, reason.name)
        ).takeLast(MAX_REJECTED)

    private fun pruneVersions(keep: Set<String>) {
        File(root, "versions").listFiles()?.forEach {
            if (it.isDirectory && versionPattern.matches(it.name) && it.name !in keep) removeTree(it)
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

    private fun markerFor(version: String): CompileMarker = identity(version)

    private fun library(version: String): File = File(root, "versions/$version/lib")

    private fun versionDir(version: String): File {
        require(versionPattern.matches(version)) { "Bad engine version" }
        return File(root, "versions/$version")
    }

    private fun metadata(): BundleMetadata {
        val text = paths.bundledJson()
        require(text.length <= RECORD_LIMIT)
        return Json.decodeFromString<BundleMetadata>(text).also {
            require(versionPattern.matches(it.version) && it.tag == it.version)
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
        const val REJECT_MS = 72L * 60 * 60 * 1000
        const val MAX_REJECTED = 16
        val versionPattern = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")
    }
}

/** Dotted numeric engine versions (upstream `yyyy.mm.dd`). */
fun compareVersions(
    a: String,
    b: String,
): Int {
    val pa = a.split('.').map { it.toLongOrNull() ?: -1 }
    val pb = b.split('.').map { it.toLongOrNull() ?: -1 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrElse(i) { -1 }).compareTo(pb.getOrElse(i) { -1 })
        if (d != 0) return d
    }
    return 0
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

    /** The recorded marker when compiled outputs exist, regardless of the app version that wrote it. */
    fun stored(
        lib: Path,
        minor: String,
    ): CompileMarker? {
        val directory = lib.toFile()
        if (!compiledFiles.all { Files.isRegularFile(File(directory, it).toPath(), NOFOLLOW_LINKS) }) return null
        return readRecord<StoredMarker>(marker(directory, minor))?.let {
            CompileMarker(it.appVersionCode, it.engineVersion, it.pythonMinor)
        }
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
private data class ActiveRecord(
    val current: String,
    val bundled: String,
    val identity: StoredMarker,
    val previous: String? = null,
    val lastManifestSequence: Long = 0,
    val rejected: List<StoredRejection> = emptyList(),
    val activation: StoredActivation? = null,
)

@Serializable
private data class StoredRejection(
    val version: String,
    val atMs: Long,
    val sequence: Long,
    val reason: String,
) {
    fun reasonEnum(): EngineRejectReason =
        runCatching { EngineRejectReason.valueOf(reason) }.getOrDefault(EngineRejectReason.ROLLED_BACK)
}

@Serializable
private data class StoredActivation(
    val atMs: Long,
    val previousLastSuccessAtMs: Long?,
    val breakerWasOpen: Boolean,
)

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

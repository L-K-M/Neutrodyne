// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopStorageRoots(
    private val dirs: AppDirs,
    private val settings: SettingStore,
    private val rows: suspend () -> List<DownloadEntity>,
    private val installation: Path? = null,
) : StorageRoots {
    private val registryMutex = Mutex()

    override fun observeCurrent(settings: SettingStore) =
        this.settings.observe(DesktopSettingKeys.DOWNLOADS_DIR).map { current() }

    override suspend fun current(): String {
        val path = settings.get(DesktopSettingKeys.DOWNLOADS_DIR)
        if (path.isEmpty()) return DEFAULT_ROOT
        return registry().entries.firstOrNull { it.value == path }?.key
            ?: error("Current download folder is not registered")
    }

    override suspend fun setCurrent(
        rootId: String,
        settings: SettingStore,
    ) {
        val path = if (rootId == DEFAULT_ROOT) "" else checkNotNull(registry()[rootId])
        this.settings.set(DesktopSettingKeys.DOWNLOADS_DIR, path)
    }

    private suspend fun registry(): Map<String, String> =
        settings
            .get(DownloadSettingKeys.DESKTOP_ROOTS)
            .associate { it.substringBefore('|') to it.substringAfter('|') }

    private suspend fun path(rootId: String): Path? =
        if (rootId == DEFAULT_ROOT) dirs.downloadsDefault else registry()[rootId]?.let(Path::of)

    override suspend fun maximumFileBytes(rootId: String): Long? =
        withContext(Dispatchers.IO) {
            val root = resolve(rootId) ?: return@withContext null
            val type = Files.getFileStore(Path.of(root.absolutePath)).type().lowercase()
            if (type in setOf("fat", "fat32", "vfat", "msdos")) MAX_FAT_FILE_BYTES else null
        }

    override suspend fun list(): List<StorageRootInfo> =
        withContext(Dispatchers.IO) {
            val included = rows().map { it.rootId }.toSet() + current() + DEFAULT_ROOT
            included.mapNotNull { id -> path(id)?.let { info(id, it) } }
        }

    private suspend fun info(
        rootId: String,
        path: Path,
    ): StorageRootInfo {
        val resolved = resolve(rootId)
        val free =
            try {
                if (resolved != null) Files.getFileStore(path).usableSpace else null
            } catch (
                _: IOException,
            ) {
                null
            }
        return StorageRootInfo(
            rootId,
            if (rootId ==
                DEFAULT_ROOT
            ) {
                RootKind.DESKTOP_DEFAULT
            } else {
                RootKind.DESKTOP_FOLDER
            },
            null,
            free,
            resolved != null,
            path.toString(),
        )
    }

    override suspend fun resolve(rootId: String): ResolvedRoot? =
        withContext(Dispatchers.IO) {
            val path = path(rootId) ?: return@withContext null
            try {
                // Recreate only the app-owned default; an absent removable folder stays unavailable.
                if (rootId == DEFAULT_ROOT) Files.createDirectories(path)
                if (!Files.isDirectory(path) || !Files.isWritable(path)) return@withContext null
                ResolvedRoot(rootId, path.toRealPath().toString())
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    override suspend fun registerFolderRoot(absolutePath: String): FolderRootResult =
        withContext(Dispatchers.IO) {
            registryMutex.withLock {
                val candidate =
                    try {
                        Path.of(absolutePath)
                    } catch (_: java.nio.file.InvalidPathException) {
                        return@withLock rejected(FolderRejectReason.NOT_A_DIRECTORY)
                    }
                if (!candidate.isAbsolute) return@withLock rejected(FolderRejectReason.NOT_A_DIRECTORY)
                val real =
                    try {
                        // Resolve existing ancestors before creating anything, including symlink aliases.
                        val canonical = canonical(candidate)
                        if (listOfNotNull(
                                installation ?: installationRoot(),
                                dirs.cache,
                                dirs.state,
                            ).any { canonical.startsWith(canonical(it)) }
                        ) {
                            return@withLock rejected(FolderRejectReason.INSIDE_APP_DIRECTORIES)
                        }
                        if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
                            return@withLock rejected(FolderRejectReason.NOT_A_DIRECTORY)
                        }
                        Files.createDirectories(candidate)
                        candidate.toRealPath()
                    } catch (
                        _: IOException,
                    ) {
                        return@withLock rejected(FolderRejectReason.NOT_WRITABLE)
                    } catch (
                        _: SecurityException,
                    ) {
                        return@withLock rejected(FolderRejectReason.NOT_WRITABLE)
                    }
                try {
                    // A unique probe never truncates an existing user file.
                    val probe = Files.createTempFile(real, ".neutrodyne-probe-", "")
                    try {
                        Files.write(probe, byteArrayOf(1))
                    } finally {
                        Files.delete(probe)
                    }
                } catch (
                    _: IOException,
                ) {
                    return@withLock rejected(FolderRejectReason.NOT_WRITABLE)
                } catch (
                    _: SecurityException,
                ) {
                    return@withLock rejected(FolderRejectReason.NOT_WRITABLE)
                }
                val normalized = real.toString()
                val known = registry()
                val existing = known.entries.firstOrNull { it.value == normalized }?.key
                val id =
                    when {
                        real == canonical(dirs.downloadsDefault) -> DEFAULT_ROOT
                        existing != null -> existing
                        else -> repoint(real, known) ?: newId(known.keys)
                    }
                if (id != DEFAULT_ROOT) {
                    val oldPath = known[id]
                    settings.update(DownloadSettingKeys.DESKTOP_ROOTS) { entries ->
                        (entries.filterNot { it.substringBefore('|') == id } + "$id|$normalized").toPersistentSet()
                    }
                    if (oldPath != null && settings.get(DesktopSettingKeys.DOWNLOADS_DIR) == oldPath) {
                        settings.set(DesktopSettingKeys.DOWNLOADS_DIR, normalized)
                    }
                }
                FolderRootResult.Ok(info(id, real))
            }
        }

    private fun rejected(reason: FolderRejectReason) = FolderRootResult.Rejected(reason)

    private fun installationRoot(): Path? {
        val executable = System.getProperty("jpackage.app-path")?.let { Path.of(it).toRealPath() } ?: return null
        val parent = executable.parent ?: return null
        // jpackage launchers live below bin/ on Linux and Contents/MacOS/ on macOS.
        if (parent.fileName.toString() == "bin") return parent.parent
        return generateSequence(parent) { it.parent }.firstOrNull { it.fileName?.toString()?.endsWith(".app") == true }
            ?: parent
    }

    private fun canonical(path: Path): Path {
        val absolute = path.toAbsolutePath()
        if (Files.exists(absolute)) return absolute.toRealPath()
        val parent = absolute.parent ?: return absolute
        return canonical(parent).resolve(absolute.fileName).normalize()
    }

    private suspend fun repoint(
        candidate: Path,
        known: Map<String, String>,
    ): String? {
        val all = rows()
        for (id in known.keys) {
            if (resolve(id) != null) continue
            val owned = all.filter { it.rootId == id && it.relativePath != null && it.totalBytes != null }
            if (owned.isEmpty()) continue
            val matches =
                owned.count { row ->
                    val file = candidate.resolve(checkNotNull(row.relativePath)).normalize()
                    file.startsWith(candidate) && Files.isRegularFile(file) && Files.size(file) == row.totalBytes
                }
            if (matches >= minOf(REPOINT_MIN_FILES, owned.size)) return id
        }
        return null
    }

    private fun newId(known: Set<String>): String {
        while (true) {
            val id =
                "dir:" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(ROOT_KEY_LENGTH)
            if (id !in known) return id
        }
    }

    override suspend fun prune(usedRoots: Set<String>) =
        registryMutex.withLock {
            val retained = usedRoots + current()
            settings.update(DownloadSettingKeys.DESKTOP_ROOTS) { entries ->
                entries.filter { it.substringBefore('|') in retained }.toPersistentSet()
            }
        }

    override suspend fun ensureSpace(
        rootId: String,
        requiredBytes: Long,
    ): StorageFailure? =
        withContext(Dispatchers.IO) {
            require(requiredBytes >= 0)
            val root = resolve(rootId) ?: return@withContext StorageFailure.UNAVAILABLE
            try {
                if (Files.getFileStore(Path.of(root.absolutePath)).usableSpace <
                    requiredBytes
                ) {
                    StorageFailure.FULL
                } else {
                    null
                }
            } catch (_: IOException) {
                StorageFailure.UNAVAILABLE
            }
        }

    override suspend fun classify(
        error: Throwable,
        rootId: String,
    ): StorageFailure =
        withContext(Dispatchers.IO) {
            val root = resolve(rootId) ?: return@withContext StorageFailure.UNAVAILABLE
            try {
                if (Files.getFileStore(Path.of(root.absolutePath)).usableSpace <
                    LOW_SPACE_BYTES
                ) {
                    StorageFailure.FULL
                } else {
                    StorageFailure.IO
                }
            } catch (_: IOException) {
                StorageFailure.UNAVAILABLE
            }
        }

    companion object {
        const val DEFAULT_ROOT = "dir:default"
        private const val LOW_SPACE_BYTES = 1024 * 1024
        private const val MAX_FAT_FILE_BYTES = 0xffff_ffffL
        private const val REPOINT_MIN_FILES = 3
        private const val ROOT_KEY_LENGTH = 8
    }
}

internal actual class PlatformLocalUris actual constructor() : LocalUris {
    actual override fun fileUri(absolutePath: String): String = Path.of(absolutePath).toUri().toASCIIString()
}

internal actual fun platformPartialDirectory(path: String) {
    val directory = Path.of(path)
    if (directory.fileName.toString() != ".partial") return
    if (Files
            .getFileStore(
                directory,
            ).supportsFileAttributeView("dos")
    ) {
        Files.setAttribute(directory, "dos:hidden", true)
    }
}

internal actual fun pathPlatform(): PathPlatform =
    if (System
            .getProperty(
                "os.name",
            ).startsWith("Windows", ignoreCase = true)
    ) {
        PathPlatform.WINDOWS
    } else {
        PathPlatform.PORTABLE
    }

internal actual fun platformSameStore(
    source: String,
    target: String,
): Boolean = Files.getFileStore(Path.of(source)) == Files.getFileStore(Path.of(target))

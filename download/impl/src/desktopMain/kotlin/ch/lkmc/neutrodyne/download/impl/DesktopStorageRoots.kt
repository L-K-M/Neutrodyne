// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopStorageRoots(
    private val dirs: AppDirs,
) : StorageRoots {
    override suspend fun current(): String = DEFAULT_ROOT

    override suspend fun maximumFileBytes(rootId: String): Long? =
        withContext(Dispatchers.IO) {
            if (resolve(rootId) == null) return@withContext null
            val type = Files.getFileStore(dirs.downloadsDefault).type().lowercase()
            if (type in setOf("fat", "fat32", "vfat", "msdos")) MAX_FAT_FILE_BYTES else null
        }

    override suspend fun list(): List<StorageRootInfo> =
        withContext(Dispatchers.IO) {
            val resolved = resolve(DEFAULT_ROOT)
            listOf(
                StorageRootInfo(
                    DEFAULT_ROOT,
                    RootKind.DESKTOP_DEFAULT,
                    null,
                    resolved?.let {
                        Files.getFileStore(dirs.downloadsDefault).usableSpace
                    },
                    resolved != null,
                    dirs.downloadsDefault.toString(),
                ),
            )
        }

    override suspend fun resolve(rootId: String): ResolvedRoot? =
        withContext(Dispatchers.IO) {
            if (rootId != DEFAULT_ROOT) return@withContext null
            try {
                Files.createDirectories(dirs.downloadsDefault)
                if (!Files.isWritable(dirs.downloadsDefault)) return@withContext null
                ResolvedRoot(rootId, dirs.downloadsDefault.toRealPath().toString())
            } catch (_: IOException) {
                null
            }
        }

    override suspend fun ensureSpace(
        rootId: String,
        requiredBytes: Long,
    ): StorageFailure? =
        withContext(Dispatchers.IO) {
            require(requiredBytes >= 0)
            if (resolve(rootId) == null) return@withContext StorageFailure.UNAVAILABLE
            try {
                if (Files.getFileStore(dirs.downloadsDefault).usableSpace < requiredBytes) StorageFailure.FULL else null
            } catch (_: IOException) {
                StorageFailure.UNAVAILABLE
            }
        }

    override suspend fun classify(
        error: Throwable,
        rootId: String,
    ): StorageFailure =
        withContext(Dispatchers.IO) {
            if (rootId != DEFAULT_ROOT || !Files.isDirectory(dirs.downloadsDefault) ||
                !Files.isWritable(dirs.downloadsDefault)
            ) {
                return@withContext StorageFailure.UNAVAILABLE
            }
            try {
                if (Files.getFileStore(dirs.downloadsDefault).usableSpace <
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

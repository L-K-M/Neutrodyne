// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

internal const val TRANSFER_BUFFER_BYTES = 256 * 1024

internal class OkioDownloadFileSystem(
    private val roots: StorageRoots,
    private val files: FileSystem = FileSystem.SYSTEM,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadFileSystem {
    override suspend fun openPart(
        rootId: String,
        path: String,
    ): PartFile {
        require(path.isNotEmpty())
        segments(path)
        // Prepare must be able to represent an unavailable old root without opening it.
        return OkioPartFile(rootId, path, files, io) { checked(rootId, path) }
    }

    override suspend fun stat(
        rootId: String,
        path: String,
    ): DownloadFileInfo? =
        withContext(io) {
            val absolute = checked(rootId, path)
            info(path, absolute)
        }

    override suspend fun list(
        rootId: String,
        directory: String,
    ): List<DownloadFileInfo> =
        withContext(io) {
            val absolute = checked(rootId, directory)
            if (files.metadataOrNull(absolute) == null) return@withContext emptyList()
            files.list(absolute).mapNotNull { child ->
                val relative = if (directory.isEmpty()) child.name else "$directory/${child.name}"
                // Never expose symlinks to adoption, deletion or reconciliation.
                if (files.metadata(child).symlinkTarget != null) null else info(relative, child)
            }
        }

    override suspend fun createDirectories(
        rootId: String,
        path: String,
    ) = withContext(io) {
        files.createDirectories(checked(rootId, path))
    }

    override suspend fun delete(
        rootId: String,
        path: String,
    ) = withContext(io) {
        require(path.isNotEmpty())
        files.delete(checked(rootId, path))
    }

    override suspend fun atomicMove(
        rootId: String,
        from: String,
        to: String,
    ) = withContext(io) {
        files.atomicMove(checked(rootId, from), checked(rootId, to))
    }

    override suspend fun sameStore(
        sourceRoot: String,
        targetRoot: String,
    ): Boolean =
        withContext(io) {
            if (files != FileSystem.SYSTEM) return@withContext false
            platformSameStore(checked(sourceRoot, "").toString(), checked(targetRoot, "").toString())
        }

    override suspend fun moveBetweenRoots(
        sourceRoot: String,
        from: String,
        targetRoot: String,
        to: String,
    ) = withContext(io) {
        files.atomicMove(checked(sourceRoot, from), checked(targetRoot, to))
    }

    private fun info(
        relative: String,
        absolute: Path,
    ): DownloadFileInfo? {
        val metadata = files.metadataOrNull(absolute) ?: return null
        return DownloadFileInfo(relative, metadata.size ?: 0, metadata.lastModifiedAtMillis ?: 0, metadata.isDirectory)
    }

    private suspend fun checked(
        rootId: String,
        relative: String,
    ): Path {
        val segments = segments(relative)
        val root = roots.resolve(rootId) ?: throw IOException("Download root unavailable")
        var path = root.absolutePath.toPath()
        require(path.isAbsolute)
        for (segment in segments) {
            path /= segment
            if (files.metadataOrNull(path)?.symlinkTarget != null) throw IOException("Symlink in download path")
        }
        return path
    }

    private fun segments(relative: String): List<String> {
        require('\\' !in relative && '\u0000' !in relative && ':' !in relative && !relative.startsWith('/'))
        val segments = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid download path" }
        return segments
    }
}

private class OkioPartFile(
    override val rootId: String,
    override val path: String,
    private val files: FileSystem,
    private val io: CoroutineDispatcher,
    private val resolve: suspend () -> Path,
) : PartFile {
    private var handle: FileHandle? = null
    private var closed = false

    private suspend fun handle(): FileHandle {
        check(!closed)
        handle?.let { return it }
        val absolute = resolve()
        files.createDirectories(checkNotNull(absolute.parent))
        if (files == FileSystem.SYSTEM) platformPartialDirectory(absolute.parent.toString())
        return files.openReadWrite(absolute).also { handle = it }
    }

    override suspend fun length(): Long = withContext(io) { handle().size() }

    override suspend fun read(
        offset: Long,
        buffer: ByteArray,
        count: Int,
    ): Int =
        withContext(io) {
            require(offset >= 0 && count in 0..buffer.size)
            handle().read(offset, buffer, 0, count)
        }

    override suspend fun append(
        buffer: ByteArray,
        count: Int,
    ) = withContext(io) {
        require(count in 0..buffer.size)
        val file = handle()
        file.write(file.size(), buffer, 0, count)
    }

    override suspend fun truncate(length: Long) =
        withContext(io) {
            val file = handle()
            require(length >= 0 && length <= file.size()) { "A part may only shrink" }
            file.resize(length)
        }

    override suspend fun sync() {
        withContext(io) { handle?.flush() }
    }

    override suspend fun close() {
        withContext(io + NonCancellable) {
            closed = true
            handle?.close()
        }
    }

    override suspend fun delete() =
        withContext(io) {
            closed = true
            handle?.close()
            files.delete(resolve())
        }
}

/** Platform-only mechanics; no client construction and no JDK symbols in commonMain. */
internal expect fun platformPartialDirectory(path: String)

internal expect fun localDate(epochMillis: Long): String

internal expect fun httpDateMillis(value: String): Long?

internal expect fun pathPlatform(): PathPlatform

internal expect class PlatformLocalUris() : LocalUris {
    override fun fileUri(absolutePath: String): String
}

internal expect class PlatformSriHasher(
    roots: StorageRoots,
    files: FileSystem = FileSystem.SYSTEM,
) : SriHasher {
    override suspend fun matches(
        rootId: String,
        path: String,
        integrity: String,
    ): Boolean
}

internal expect fun platformSameStore(
    source: String,
    target: String,
): Boolean

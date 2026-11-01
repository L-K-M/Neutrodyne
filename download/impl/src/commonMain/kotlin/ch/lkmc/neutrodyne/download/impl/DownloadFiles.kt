// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.model.DownloadError
import okio.IOException

/** File-only verification/recovery. Engine owns the eventual guarded COMPLETED commit. */
internal class DownloadFiles(
    private val files: DownloadFileSystem,
    private val roots: StorageRoots,
    private val hasher: SriHasher,
    private val uris: LocalUris,
) {
    suspend fun verify(
        part: PartFile,
        total: Long?,
        mime: String?,
        integrity: String?,
    ): TransferResult {
        val size = part.length()
        if (total != null && total != size) return TransferResult.Transient(DownloadError.SIZE_MISMATCH, null, null)
        val media = MediaSniffer.classify(head(part), mime)
        if (media == MediaKind.HLS) return TransferResult.Permanent(DownloadError.UNSUPPORTED_STREAM, null)
        if (media == MediaKind.NOT_MEDIA) return TransferResult.Permanent(DownloadError.NOT_MEDIA, null)
        if (integrity != null && !hasher.matches(part.rootId, part.path, integrity)) {
            return TransferResult.Transient(DownloadError.SIZE_MISMATCH, null, null)
        }
        return TransferResult.Complete(size, mime, null, null, media)
    }

    suspend fun adopt(
        row: DownloadEntity,
        source: DownloadSourceRow,
        integrity: String?,
    ): Prepared.Adopted? {
        val total = row.totalBytes ?: return null
        if (roots.resolve(row.rootId) == null) return null
        // A zero-length file opened by the engine is not evidence of a partial transfer.
        if ((files.stat(row.rootId, row.tempPath ?: DownloadPaths.part(row.episodeId))?.bytes ?: 0) > 0) return null
        val folder = files.list(row.rootId, "").filter { it.directory && it.path.endsWith(" [p${source.podcastId}]") }
        val suffix = Regex(".* \\[e${row.episodeId}\\]\\.[a-z0-9]+")
        for (directory in folder) {
            for (candidate in files.list(row.rootId, directory.path)) {
                if (candidate.directory || candidate.bytes != total || !suffix.matches(candidate.path)) continue
                val part = files.openPart(row.rootId, candidate.path)
                val verified =
                    try {
                        verify(part, total, row.mimeType, integrity)
                    } finally {
                        part.close()
                    }
                if (verified !is TransferResult.Complete) continue
                val root = roots.resolve(row.rootId) ?: return null
                return Prepared.Adopted(
                    verified.copy(etag = row.etag, lastModified = row.lastModified),
                    candidate.path,
                    uris.fileUri("${root.absolutePath}/${candidate.path}"),
                )
            }
        }
        return null
    }

    /** Sync and close before same-volume rename. A crash after this is handled by adopt(). */
    suspend fun finish(
        part: PartFile,
        source: DownloadSourceRow,
        plan: TransferPlan,
        complete: TransferResult.Complete,
        platform: PathPlatform = pathPlatform(),
    ): Prepared.Adopted {
        val root = roots.resolve(part.rootId) ?: throw IOException("Download root unavailable")
        val head = head(part)
        val path =
            DownloadPaths.finalPath(
                files,
                root,
                source.podcastId,
                source.podcastTitle,
                source.episodeId,
                source.title,
                localDate(source.sortDate),
                MediaSniffer.extension(complete.media, head, complete.mimeType, plan.originalUrl, source.isVideo),
                platform,
            )
        files.createDirectories(part.rootId, path.substringBeforeLast('/'))
        part.sync()
        part.close()
        files.atomicMove(part.rootId, part.path, path)
        return Prepared.Adopted(complete, path, uris.fileUri("${root.absolutePath}/$path"))
    }

    private suspend fun head(part: PartFile): ByteArray {
        val buffer = ByteArray(MediaSniffer.HEAD_BYTES)
        var size = 0
        while (size < buffer.size) {
            val next = ByteArray(buffer.size - size)
            val count = part.read(size.toLong(), next, next.size)
            if (count <= 0) break
            next.copyInto(buffer, size, 0, count)
            size += count
        }
        return buffer.copyOf(size)
    }
}

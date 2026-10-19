// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.download.api.StorageRootInfo

/** Platform implementations do all probing/allocation on IO. No FD preallocation. */
internal interface StorageRoots {
    suspend fun current(): String

    suspend fun list(): List<StorageRootInfo>

    suspend fun resolve(rootId: String): ResolvedRoot?

    /** Reserve only within the reported allocatable bound, recheck; short space returns FULL. */
    suspend fun ensureSpace(
        rootId: String,
        requiredBytes: Long,
    ): StorageFailure?

    suspend fun classify(
        error: Throwable,
        rootId: String,
    ): StorageFailure
}

internal data class ResolvedRoot(
    val rootId: String,
    val absolutePath: String,
)

internal enum class StorageFailure { FULL, TOO_LARGE, UNAVAILABLE, IO }

/** Okio-backed handles; paths are root-relative and validated, never arbitrary external paths.
 * Implementations dispatch blocking IO. Engine owns close, sync and final rename; transfer owns
 * append/truncate during its child coroutine. length is the actual resume offset. */
internal interface PartFile {
    val rootId: String
    val path: String

    suspend fun length(): Long

    suspend fun read(
        offset: Long,
        buffer: ByteArray,
        count: Int,
    ): Int

    suspend fun append(
        buffer: ByteArray,
        count: Int,
    )

    suspend fun truncate(length: Long)

    suspend fun sync()

    suspend fun close()

    suspend fun delete()
}

internal interface DownloadFileSystem {
    suspend fun openPart(
        rootId: String,
        path: String,
    ): PartFile

    suspend fun stat(
        rootId: String,
        path: String,
    ): DownloadFileInfo?

    suspend fun list(
        rootId: String,
        directory: String,
    ): List<DownloadFileInfo>

    suspend fun createDirectories(
        rootId: String,
        path: String,
    )

    suspend fun delete(
        rootId: String,
        path: String,
    )

    /** Same-volume atomic rename; caller syncs/closes part first, commits COMPLETED afterwards. */
    suspend fun atomicMove(
        rootId: String,
        from: String,
        to: String,
    )
}

internal data class DownloadFileInfo(
    val path: String,
    val bytes: Long,
    val modifiedAt: Long,
    val directory: Boolean,
)

internal interface LocalUris {
    fun fileUri(absolutePath: String): String
}

internal interface SriHasher {
    suspend fun matches(
        rootId: String,
        path: String,
        integrity: String,
    ): Boolean
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.FileUris
import ch.lkmc.neutrodyne.core.data.credentials.AtomicMove
import ch.lkmc.neutrodyne.core.domain.ExportError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.Path as NioPath

/**
 * The desktop [ExportDestination] (05 Destinations "Save to file (desktop)", row C3a): the
 * destination URI is the `file:` URI the save dialog returned, and the save is an atomic
 * replace — the prepared cache file is copied into a unique sibling temp of the target,
 * flushed to disk, then renamed over the target with `ATOMIC_MOVE` + `REPLACE_EXISTING`
 * through the same [AtomicMove] seam as `DesktopSecretsFile`. A cancelled or failed save
 * leaves an existing target byte-identical; the owned temp is removed on every path.
 *
 * Unlike 05's fallback sentence this never writes non-atomically: a filesystem that refuses
 * the temp or the atomic rename (some network shares) reports [ExportError.DestinationUnavailable]
 * rather than producing a possibly-torn file — the task's invariants ("preserve old target
 * until successful replacement") hold unconditionally. Deviation recorded in 05's
 * Destinations implementation note.
 *
 * [shareUri] is the minted export leaf's own `file:` URI — the desktop has no share sheet,
 * but the value identifies the private cache file for "Show in folder" (05's snackbar)
 * without a second path-minting rule. Only leaf names produce a URI.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class FileExportDestination(
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    private val move: AtomicMove =
        AtomicMove { source, target ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
) : ExportDestination {
    private val exportDir: Path = exportDirOf(storagePaths)

    override fun shareUri(fileName: String): String? {
        if (!isLeafFileName(fileName)) return null
        return FileUris.uriFor(exportDir.resolve(fileName).toNioPath())
    }

    override suspend fun save(
        source: Path,
        destinationUri: String,
    ): Outcome<Unit, ExportError> =
        withContext(io) {
            val target =
                FileUris.parse(destinationUri)?.toAbsolutePath()
                    ?: return@withContext Outcome.Failure(ExportError.DestinationUnavailable)
            val dir =
                target.parent
                    ?: return@withContext Outcome.Failure(ExportError.DestinationUnavailable)
            val temp =
                try {
                    Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext Outcome.Failure(ExportError.DestinationUnavailable)
                }
            try {
                copyToTemp(source, temp)?.let { return@withContext Outcome.Failure(it) }
                currentCoroutineContext().ensureActive()
                try {
                    move.move(temp, target)
                } catch (_: Exception) {
                    // Covers AtomicMoveNotSupportedException and a refused rename alike — the
                    // destination cannot be written atomically there; the target is untouched.
                    return@withContext Outcome.Failure(ExportError.DestinationUnavailable)
                }
                syncDirectory(dir)
                Outcome.Success(Unit)
            } finally {
                // After a successful move the temp name is gone; on every failure or
                // cancellation the owned temp must not remain in the user's directory.
                runCatching { Files.deleteIfExists(temp) }
            }
        }

    /**
     * Full counted copy + fsync into [temp]: `null` on success, else the failure to report.
     * Source-side failures are [ExportError.Io]; everything destination-side is
     * [ExportError.DestinationUnavailable] (the user-chosen location could not take the file).
     */
    private suspend fun copyToTemp(
        source: Path,
        temp: NioPath,
    ): ExportError? {
        val input =
            try {
                fileSystem.source(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return ExportError.Io
            }
        var failure: ExportError? = null
        try {
            input.use { src ->
                fileSystem.sink(temp.toOkioPath()).buffer().use { sink ->
                    val chunk = Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read =
                            try {
                                src.read(chunk, CHUNK_BYTES)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failure = ExportError.Io
                                break
                            }
                        if (read == -1L) break
                        try {
                            sink.write(chunk, read)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failure = ExportError.DestinationUnavailable
                            break
                        }
                    }
                    try {
                        sink.flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (failure == null) failure = ExportError.DestinationUnavailable
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Opening the temp sink or closing either stream.
            if (failure == null) failure = ExportError.DestinationUnavailable
        }
        if (failure != null) return failure
        // The rename must never expose a short file: the copied bytes are on disk first.
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
        } catch (_: Exception) {
            return ExportError.DestinationUnavailable
        }
        return null
    }

    /** Makes the rename durable where a directory can be opened as a channel (POSIX). */
    private fun syncDirectory(dir: NioPath) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Windows cannot open a directory as a channel; NTFS journals the rename itself.
        }
    }

    /**
     * `shareUri` mints URIs for the export-cache leaf only — the same containment rule
     * `ExportRepositoryImpl.preparedFile` applies before it writes: no empty, `.`, `..` or
     * separator-carrying name may reach a path outside `<cache>/export`.
     */
    private fun isLeafFileName(fileName: String): Boolean =
        fileName.isNotEmpty() &&
            fileName != "." &&
            fileName != ".." &&
            '/' !in fileName &&
            '\\' !in fileName &&
            fileName.toPath().name == fileName

    private companion object {
        const val TEMP_PREFIX = ".neutrodyne-export-"
        const val TEMP_SUFFIX = ".tmp"
        const val CHUNK_BYTES = 16L * 1024
    }
}

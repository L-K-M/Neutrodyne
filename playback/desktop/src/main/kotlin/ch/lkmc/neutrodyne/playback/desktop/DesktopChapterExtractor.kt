// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterReader
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterResult
import ch.lkmc.neutrodyne.playback.engine.FfmpegPipeline
import ch.lkmc.neutrodyne.playback.engine.FileByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Download-time embedded chapters (06 Chapters, 11 Chapters and metadata): opens the local file
 * with [FfmpegPipeline] and returns the container's chapters, so a downloaded episode's chapters
 * are in `ChapterRepository` before playback. Only `file:` URIs are read; anything else is
 * unavailable, and a missing file is reported as such so the caller can react.
 */
class DesktopChapterExtractor(
    private val ffmpegDirectory: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EmbeddedChapterReader {
    override suspend fun read(fileUri: String): EmbeddedChapterResult {
        val path =
            try {
                localPath(fileUri)
            } catch (_: IllegalArgumentException) {
                return EmbeddedChapterResult.Unavailable
            } catch (_: java.net.URISyntaxException) {
                return EmbeddedChapterResult.Unavailable
            }
        if (!Files.isRegularFile(path)) return EmbeddedChapterResult.MissingFile
        return withContext(io) {
            try {
                withTimeout(INSPECTION_TIMEOUT_MS) {
                    FileByteSource(path).use { source ->
                        FfmpegPipeline.open(source, ffmpegDirectory).use { pipeline ->
                            EmbeddedChapterResult.Read(pipeline.demuxer.chapters)
                        }
                    }
                }
            } catch (_: NoSuchFileException) {
                EmbeddedChapterResult.MissingFile
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                EmbeddedChapterResult.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EmbeddedChapterResult.Unavailable
            }
        }
    }

    /** Desktop downloads are local files; the resolver's own rule, kept in one place. */
    private fun localPath(value: String): Path {
        val uri = URI(value)
        require(uri.scheme.equals("file", ignoreCase = true)) {
            "Desktop downloads require a file URI"
        }
        return Path.of(uri)
    }

    private companion object {
        /** Opening a local container and reading its chapter table is a fast, bounded operation. */
        const val INSPECTION_TIMEOUT_MS = 15_000L
    }
}

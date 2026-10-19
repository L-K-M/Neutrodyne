// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.MetadataRetriever
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterReader
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterResult
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileNotFoundException

/** Restrict the inspector to local input so enrichment cannot fetch a remote rendition. */
@ContributesBinding(AppScope::class)
@Inject
internal class MetadataRetrieverChapterReader(
    private val context: Context,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : EmbeddedChapterReader {
    override suspend fun read(fileUri: String): EmbeddedChapterResult =
        withContext(io) {
            val uri = fileUri.toUri()
            if (uri.scheme != "file" && uri.scheme != "content") return@withContext EmbeddedChapterResult.Unavailable
            if (uri.scheme == "file" && !File(uri.path.orEmpty()).isFile) {
                return@withContext EmbeddedChapterResult.MissingFile
            }
            try {
                withTimeout(INSPECTION_TIMEOUT_MS) {
                    // The inspector default omits MP4 sample tables needed by QuickTime chapter tracks.
                    val source = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    MetadataRetriever
                        .Builder(context, MediaItem.fromUri(uri))
                        .setMediaSourceFactory(source)
                        .build()
                        .use { retriever ->
                            val groups = retriever.retrieveTrackGroups().await()
                            val formats =
                                (0 until groups.length).flatMap { group ->
                                    val tracks = groups[group]
                                    (0 until tracks.length).map(tracks::getFormat)
                                }
                            EmbeddedChapterResult.Read(Chapters.fromFormats(formats).values.flatten())
                        }
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                EmbeddedChapterResult.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generateSequence<Throwable>(error) { it.cause }.any { it is FileNotFoundException }) {
                    EmbeddedChapterResult.MissingFile
                } else {
                    EmbeddedChapterResult.Unavailable
                }
            }
        }

    private companion object {
        const val INSPECTION_TIMEOUT_MS = 15_000L
    }
}

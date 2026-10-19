// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.session.SessionResult
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands
import ch.lkmc.neutrodyne.playback.core.ChapterIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Service-main ownership; each occurrence cancels all reads, writes and boundary scheduling. */
internal class Chapters(
    private val player: Player,
    private val repository: ChapterRepository,
    private val scope: CoroutineScope,
    private val seek: (Long) -> Unit = player::seekTo,
    private val publish: (CurrentChapter?) -> Unit,
) : Player.Listener {
    private var occurrence: CoroutineScope? = null
    private var boundary: Job? = null
    private var epoch = 0L
    private var index = ChapterIndex(emptyList())
    private val written = mutableSetOf<ChapterSource>()
    private val writing = mutableSetOf<ChapterSource>()

    fun attach() {
        player.addListener(this)
        currentChanged()
    }

    fun detach() {
        player.removeListener(this)
        occurrence?.cancel()
        boundary?.cancel()
        epoch++
        publish(null)
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) = currentChanged()

    private fun currentChanged() {
        occurrence?.cancel()
        boundary?.cancel()
        val captured = ++epoch
        index = ChapterIndex(emptyList())
        written.clear()
        writing.clear()
        publish(null)
        val id = player.episodeIdOrNull() ?: return
        val currentScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        occurrence = currentScope
        currentScope.launch {
            repository.observe(id).collect { rows ->
                if (captured != epoch) return@collect
                index = ChapterIndex(rows)
                evaluate()
            }
        }
        currentScope.launch { bestEffort { repository.ensureLoaded(id) } }
        extract(player.currentTracks)
    }

    override fun onTracksChanged(tracks: Tracks) = extract(tracks)

    private fun extract(tracks: Tracks) {
        if (tracks != player.currentTracks) return
        val id = player.episodeIdOrNull() ?: return
        val captured = epoch
        val currentScope = occurrence ?: return
        for ((source, rows) in fromTracks(tracks)) {
            if (source in written || !writing.add(source)) continue
            currentScope.launch {
                try {
                    if (captured != epoch || player.episodeIdOrNull() != id) return@launch
                    repository.replace(id, source, rows)
                    if (captured == epoch) written.add(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not persist embedded chapters", error)
                } finally {
                    if (captured == epoch) writing.remove(source)
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = evaluate()

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = evaluate()

    override fun onIsPlayingChanged(isPlaying: Boolean) = evaluate()

    fun command(action: String): SessionResult? {
        val target =
            when (action) {
                PlaybackSessionCommands.CHAPTER_NEXT -> index.next(player.currentPosition)
                PlaybackSessionCommands.CHAPTER_PREV -> index.previous(player.currentPosition)
                else -> return null
            }
        target?.let { seek(player.duration.takeIf { it > 0 }?.let(it::coerceAtMost) ?: it) }
        evaluate()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun evaluate() {
        boundary?.cancel()
        val id = player.episodeIdOrNull()
        val position = player.currentPosition
        publish(
            id?.let { episode ->
                index.current(position)?.let { CurrentChapter(episode, it, index.chapters.size, index.chapters[it]) }
            },
        )
        if (!player.isPlaying) return
        val next = index.nextBoundary(position) ?: return
        val speed = player.playbackParameters.speed
        if (!speed.isFinite() || speed <= 0) return
        val waitMs = ceil((next - position).toDouble() / speed).toLong().coerceAtLeast(1)
        boundary =
            occurrence?.launch {
                delay(waitMs)
                evaluate()
            }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not load chapters", error)
        }
    }

    companion object {
        private const val TAG = "Chapters"

        internal fun fromTracks(tracks: Tracks): Map<ChapterSource, List<EpisodeChapter>> =
            fromFormats(tracks.groups.flatMap { group -> (0 until group.length).map(group::getTrackFormat) })

        internal fun fromFormats(
            formats: List<androidx.media3.common.Format>,
        ): Map<ChapterSource, List<EpisodeChapter>> {
            val rows = mutableListOf<EpisodeChapter>()
            for (format in formats) {
                val metadata = format.metadata ?: continue
                for (entry in 0 until metadata.length()) {
                    val chapter = metadata[entry] as? Chapter ?: continue
                    if (chapter.startTimeMs == C.TIME_UNSET || chapter.startTimeMs < 0) continue
                    val source =
                        when {
                            chapter is ChapterFrame -> ChapterSource.ID3

                            format.containerMimeType == MimeTypes.AUDIO_MP4 ||
                                format.containerMimeType == MimeTypes.VIDEO_MP4 -> ChapterSource.MP4

                            else -> continue
                        }
                    rows +=
                        EpisodeChapter(
                            chapter.startTimeMs,
                            chapter.endTimeMs.takeUnless { it == C.TIME_UNSET },
                            chapter.title?.value,
                            null,
                            null,
                            source,
                            chapter.isHidden,
                        )
                }
            }
            return rows.groupBy { it.source }.mapValues { (_, list) -> ChapterSource.normalize(list) }
        }
    }
}

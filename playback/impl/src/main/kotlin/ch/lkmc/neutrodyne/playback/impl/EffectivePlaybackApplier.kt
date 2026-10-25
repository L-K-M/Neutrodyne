// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One instance shared by the service's projector and scope-setting command handlers. */
internal class EffectivePlaybackApplier(
    val settings: EffectivePlaybackSettings,
    private val playerScope: CoroutineScope,
) : Player.Listener {
    private var player: Player? = null
    private var following: Job? = null
    private var publishing: Job? = null
    private var media: Map<Long, PlaybackMediaInfo> = emptyMap()
    private var contextGroupId: Long? = null
    private var followedEpisodeId: Long? = null
    private var followedGroupId: Long? = null

    fun attach(player: Player) {
        check(this.player == null)
        checkPlayerThread(player)
        this.player = player
        player.addListener(this)
        publishing =
            playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.effectivePlayback.filterNotNull().collect { apply(it) }
            }
    }

    /** Called before submitting a window. All upcoming transitions have synchronous cached values. */
    suspend fun prepareWindow(
        rows: Collection<PlaybackMediaInfo>,
        groupId: Long?,
    ) {
        settings.prepareWindow(rows.map { it.podcastId }, groupId)
        media = rows.associateBy { it.episodeId }
        contextGroupId = groupId
    }

    /** Queue/metadata diffs preserve a trusted controller's temporary speed for this item. */
    fun refreshFor(episodeId: Long?) {
        val podcastId = media[episodeId]?.podcastId
        val resolved = podcastId?.let { settings.cached(it, contextGroupId) }
        if (episodeId == followedEpisodeId && contextGroupId == followedGroupId &&
            resolved == settings.effectivePlayback.value
        ) {
            return
        }

        applyFor(episodeId)
    }

    fun applyFor(episodeId: Long?) {
        followedEpisodeId = episodeId
        followedGroupId = contextGroupId
        val podcastId = media[episodeId]?.podcastId
        podcastId?.let { settings.cached(it, contextGroupId) }?.let(::apply)
        val prior = following
        prior?.cancel()
        val groupId = contextGroupId
        following =
            playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                prior?.join()
                settings.followCurrent(podcastId, groupId)
            }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        val id = player?.episodeIdOrNull()
        // Some sources emit PLAYLIST_CHANGED for an in-place metadata replacement.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && id == followedEpisodeId) {
            refreshFor(id)
        } else {
            applyFor(id)
        }
    }

    private fun apply(value: ch.lkmc.neutrodyne.core.model.EffectivePlayback) {
        val live = player ?: return
        checkPlayerThread(live)
        val speed = value.speed.value
        if (live.playbackParameters.speed != speed) live.playbackParameters = PlaybackParameters(speed)
        (live as? ExoPlayer)?.let {
            if (it.skipSilenceEnabled != value.skipSilence.value) it.skipSilenceEnabled = value.skipSilence.value
        }
    }

    /** Remove player callbacks synchronously; W8 may release immediately and optionally join. */
    fun detach(): Job {
        player?.let {
            checkPlayerThread(it)
            it.removeListener(this)
        }
        player = null
        val previous = following
        previous?.cancel()
        publishing?.cancel()
        following = null
        publishing = null
        media = emptyMap()
        followedEpisodeId = null
        followedGroupId = null
        return playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                previous?.join()
                if (player == null) settings.followCurrent(null, null)
            }
        }
    }
}

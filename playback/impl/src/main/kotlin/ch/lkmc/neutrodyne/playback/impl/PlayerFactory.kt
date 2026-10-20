// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import ch.lkmc.neutrodyne.playback.api.StreamKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/** Audio-only speech player. Stateful processors belong to each player, not the process graph. */
@Inject
internal class PlayerFactory(
    private val ctx: Application,
    private val mediaSourceFactory: NdMediaSourceFactory,
    private val boost: Provider<BoostLimiterProcessor>,
    private val prefs: Provider<PlaybackPrefs>,
) {
    fun create(): ExoPlayer {
        val snapshot = prefs().current.value
        val renderers =
            object : DefaultRenderersFactory(ctx) {
                // Video-as-audio never instantiates a video/text renderer, even if a controller
                // attempts to override track selection. M14 owns adding a visual surface.
                override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: android.os.Handler,
                    eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: ArrayList<androidx.media3.exoplayer.Renderer>,
                ) = Unit

                override fun buildTextRenderers(
                    context: Context,
                    output: androidx.media3.exoplayer.text.TextOutput,
                    outputLooper: android.os.Looper,
                    extensionRendererMode: Int,
                    out: ArrayList<androidx.media3.exoplayer.Renderer>,
                ) = Unit

                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        // Float PCM and offload bypass the processing chain.
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(false)
                        .setAudioProcessorChain(audioProcessorChain())
                        .build()
            }
        return ExoPlayer
            .Builder(ctx, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes(snapshot.pauseForNavigation), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(snapshot.skipBackMs)
            .setSeekForwardIncrementMs(snapshot.skipForwardMs)
            .setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(60_000, 600_000, 1_500, 3_000)
                    .setBackBuffer(60_000, true)
                    .setTargetBufferBytes(DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            ).setName("neutrodyne")
            .build()
            .apply {
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setAudioOffloadPreferences(
                            AudioOffloadPreferences
                                .Builder()
                                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                                .build(),
                        ).build()
                PlaybackTestHooks.playerCreated?.invoke(this)
                setSeekParameters(SeekParameters.EXACT)
                repeatMode = Player.REPEAT_MODE_OFF
                shuffleModeEnabled = false
                addListener(
                    object : Player.Listener {
                        override fun onMediaItemTransition(
                            mediaItem: MediaItem?,
                            reason: Int,
                        ) {
                            applyWakeMode(this@apply, mediaItem)
                        }
                    },
                )
            }
    }

    internal fun audioProcessorChain(): DefaultAudioSink.DefaultAudioProcessorChain =
        DefaultAudioSink.DefaultAudioProcessorChain(
            arrayOf<AudioProcessor>(boost()),
            // Design tuning; acceptance by ear remains part of the device matrix.
            SilenceSkippingAudioProcessor(250_000L, 0.2f, 400_000L, 10, 1024),
            SonicAudioProcessor(),
        )

    /** Also call before first prepare. Once a pin exists, prefer the actual stream-kind overload. */
    fun applyWakeMode(
        player: ExoPlayer,
        mediaItem: MediaItem? = player.currentMediaItem,
    ) {
        player.setWakeMode(wakeModeFor(mediaItem))
    }

    fun applyWakeMode(
        player: ExoPlayer,
        kind: StreamKind,
    ) {
        player.setWakeMode(if (kind == StreamKind.LOCAL) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
    }

    /** The caller supplies the player's application-thread scope and cancels it at teardown. */
    fun bindPrefs(
        player: ExoPlayer,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            prefs().current.collect { snapshot ->
                player.setAudioAttributes(audioAttributes(snapshot.pauseForNavigation), true)
                player.setSeekBackIncrementMs(snapshot.skipBackMs)
                player.setSeekForwardIncrementMs(snapshot.skipForwardMs)
            }
        }

    private fun audioAttributes(pauseForNavigation: Boolean): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (pauseForNavigation) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    companion object {
        fun wakeModeFor(mediaItem: MediaItem?): Int =
            if (mediaItem?.requestMetadata?.extras?.getBoolean(MediaItemFactory.LOCAL_HINT) ==
                true
            ) {
                C.WAKE_MODE_LOCAL
            } else {
                C.WAKE_MODE_NETWORK
            }
    }
}

/** Reserved before silence skipping and Sonic (D65). M12 supplies gain and limiting. */
@Inject
internal class BoostLimiterProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }
}

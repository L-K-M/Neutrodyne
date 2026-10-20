// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Robolectric coverage of [PlayerFactory] and [PlaybackPrefs] (W6). `Unconfined` collector scopes
 * run emissions inline on the test's main thread — also the player's application thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlayerFactoryTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val settings = FakeSettingsRepository()
    private val prefs = PlaybackPrefs(Provider<SettingsRepository> { settings })
    private val factory =
        PlayerFactory(
            app,
            NdMediaSourceFactory(DefaultDataSource.Factory(app), NdLoadErrorHandlingPolicy()),
            Provider { BoostLimiterProcessor() },
            Provider { prefs },
        )
    private val scopes = mutableListOf<CoroutineScope>()
    private var player: ExoPlayer? = null

    @After
    fun tearDown() {
        player?.release()
        scopes.forEach { it.cancel() }
    }

    @Test
    fun createAppliesAudioOnlyPlayerConfiguration() {
        val player = create()

        assertThat(player.audioAttributes.usage).isEqualTo(C.USAGE_MEDIA)
        assertThat(player.audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
        assertThat(player.trackSelectionParameters.disabledTrackTypes)
            .containsAtLeast(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT)
        assertThat((0 until player.rendererCount).map { player.getRendererType(it) })
            .containsNoneOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT)
        assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(player.shuffleModeEnabled).isFalse()
        assertThat(player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
            .isEqualTo(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
        assertThat(player.seekParameters).isEqualTo(SeekParameters.EXACT)
        assertThat(player.seekBackIncrement).isEqualTo(10_000L)
        assertThat(player.seekForwardIncrement).isEqualTo(30_000L)
    }

    @Test
    fun prefsHoldDefaultsThenCollectKeys() =
        runBlocking {
            assertThat(prefs.current.value).isEqualTo(PlaybackPrefsSnapshot.DEFAULT)
            prefs.start(scope())
            prefs.start(scope()) // idempotent: no second collection

            settings.set(PlaybackSettingKeys.SKIP_FORWARD_MS, 90_000L)
            settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK)

            assertThat(prefs.current.value.skipForwardMs).isEqualTo(90_000L)
            assertThat(prefs.current.value.streamOnMetered).isEqualTo(StreamOnMetered.ASK)
        }

    @Test
    fun order300InitializerStartsPrefs() =
        runBlocking {
            val initializer = PlaybackPrefsInitializer(Provider { prefs }, scope())

            assertThat(initializer.order).isEqualTo(300)
            initializer.run()
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 5_000L)

            assertThat(prefs.current.value.skipBackMs).isEqualTo(5_000L)
        }

    @Test
    fun createReadsTheHotSnapshot() =
        runBlocking {
            prefs.start(scope())
            settings.set(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION, false)
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 45_000L)

            val player = create()

            assertThat(player.audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
            assertThat(player.seekBackIncrement).isEqualTo(45_000L)
        }

    @Test
    fun bindPrefsAppliesRuntimeFocusAndSeekChanges() =
        runBlocking {
            prefs.start(scope())
            val player = create()
            factory.bindPrefs(player, scope())

            settings.set(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION, false)
            settings.set(PlaybackSettingKeys.SKIP_FORWARD_MS, 90_000L)
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 60_000L)

            assertThat(player.audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
            assertThat(player.seekForwardIncrement).isEqualTo(90_000L)
            assertThat(player.seekBackIncrement).isEqualTo(60_000L)
        }

    @Test
    fun wakeModeFollowsTheLocalHint() {
        val player = create()
        val remote = item(local = false)
        val local = item(local = true)

        player.setMediaItem(remote) // fires the transition listener; must not throw

        assertThat(PlayerFactory.wakeModeFor(remote)).isEqualTo(C.WAKE_MODE_NETWORK)
        assertThat(PlayerFactory.wakeModeFor(local)).isEqualTo(C.WAKE_MODE_LOCAL)
        assertThat(PlayerFactory.wakeModeFor(null)).isEqualTo(C.WAKE_MODE_NETWORK)
    }

    @Test
    fun boostSlotIsAnInactivePassThrough() {
        val boost = BoostLimiterProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

        assertThat(boost.configure(format)).isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(boost.isActive).isFalse()

        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)).order(ByteOrder.nativeOrder())
        boost.queueInput(input)
        val output = ByteArray(4).also { boost.output.get(it) }
        assertThat(output).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun chainUsesSonicAndSkipsLongSilence() {
        val chain = factory.audioProcessorChain()
        assertThat(chain.audioProcessors.map { it.javaClass })
            .containsExactly(
                BoostLimiterProcessor::class.java,
                SilenceSkippingAudioProcessor::class.java,
                SonicAudioProcessor::class.java,
            ).inOrder()
        chain.applyPlaybackParameters(PlaybackParameters(3f))
        chain.applySkipSilenceEnabled(true)
        val format = AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)
        chain.audioProcessors.forEach {
            it.configure(format)
            it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        assertThat(chain.audioProcessors.last().isActive).isTrue()
        val silence = chain.audioProcessors[1]
        assertThat(silence.isActive).isTrue()
        val sampleCount = 3 * 44_100
        val input = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.nativeOrder())
        repeat(sampleCount) { i -> input.putShort(if ((i / 22_050) % 2 == 0) 5000 else 0) }
        input.flip()
        var outputBytes = 0
        while (input.hasRemaining()) {
            silence.queueInput(input)
            val output = silence.output
            outputBytes += output.remaining()
            output.position(output.limit())
        }
        silence.queueEndOfStream()
        outputBytes += silence.output.remaining()
        assertThat(outputBytes).isLessThan(sampleCount * 2)
        chain.applySkipSilenceEnabled(false)
        silence.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertThat(silence.isActive).isFalse()
        chain.audioProcessors.forEach { it.reset() }
    }

    private fun scope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined).also { scopes.add(it) }

    private fun create(): ExoPlayer = factory.create().also { player = it }

    private fun item(local: Boolean): MediaItem =
        MediaItem
            .Builder()
            .setMediaId("episode:1")
            .setUri("neutrodyne://episode/1")
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setExtras(Bundle().apply { putBoolean(MediaItemFactory.LOCAL_HINT, local) })
                    .build(),
            ).build()
}

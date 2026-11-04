// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.EngineEvent
import ch.lkmc.neutrodyne.playback.engine.EngineItem
import ch.lkmc.neutrodyne.playback.engine.EngineState
import ch.lkmc.neutrodyne.playback.engine.EngineWindowDiff
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** No native libraries, device or wall-clock advancement. Tests publish engine milestones. */
class FakeAudioEngine : AudioEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    var items: List<EngineItem> = emptyList()
        private set
    var position: Long = 0
    var speed: Float = 1f
        private set
    var skipSilence: Boolean = false
        private set
    var closed: Boolean = false
        private set
    val commands = mutableListOf<String>()
    val windows = mutableListOf<List<EngineItem>>()
    private var currentIndex = 0
    private var playing = false
    var onPlay: (() -> Unit)? = null
    var onWindow: (() -> Unit)? = null

    override fun setWindow(
        items: List<EngineItem>,
        currentIndex: Int,
        startPositionMs: Long?,
    ) {
        commands += "window"
        this.items = items
        windows += items
        this.currentIndex = currentIndex
        position = startPositionMs ?: 0
        state.value = items.getOrNull(currentIndex)?.let { EngineState.Buffering(it.episodeId) } ?: EngineState.Idle
        onWindow?.invoke()
    }

    override fun updateWindow(diff: EngineWindowDiff) {
        commands += "diff"
        items = diff.applyTo(items, items.getOrNull(currentIndex)?.episodeId)
    }

    override fun play() {
        commands += "play"
        playing = true
        if (state.value is EngineState.Ready) ready()
        onPlay?.invoke()
    }

    override fun pause() {
        commands += "pause"
        playing = false
        if (state.value is EngineState.Ready) ready()
    }

    fun ready() {
        state.value = EngineState.Ready(items[currentIndex].episodeId, playing)
    }

    fun end() {
        state.value = EngineState.Ended
    }

    override fun seekTo(positionMs: Long) {
        commands += "seek"
        position = positionMs
    }

    override fun setSpeed(speed: Float) {
        commands += "speed"
        this.speed = speed
    }

    override fun setSkipSilence(enabled: Boolean) {
        commands += "silence"
        skipSilence = enabled
    }

    override fun setVolume(linear: Float) = Unit

    override fun setFade(factor: Float) = Unit

    override fun positionMs(): Long = position

    override fun close() {
        commands += "close"
        closed = true
    }
}

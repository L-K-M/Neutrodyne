// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.StartGate
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.playback.api.PlayResult
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-only consent. Downloaded items never consume a mobile-data grant. */
@SingleIn(AppScope::class)
@Inject
internal class MeteredStreamingGate(
    private val network: NetworkMonitor,
    private val prefs: PlaybackPrefs,
    @ApplicationScope scope: CoroutineScope,
) : StartGate {
    @Volatile private var granted = false
    private val pending = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null)
    val pendingConsent = pending.asStateFlow()

    init {
        scope.launch {
            network.status.collect { if (!it.isMetered) granted = false }
        }
    }

    override suspend fun check(
        item: PlaybackMediaInfo,
        localUri: String?,
    ): PlayResult? =
        check(
            item.episodeId,
            localUri != null,
            kotlinx.coroutines.currentCoroutineContext()[MeteredOrigin]?.origin ?: StartOrigin.UI,
        )

    fun check(
        episodeId: Long,
        local: Boolean,
        origin: StartOrigin,
    ): PlayResult? {
        pending.value = null
        if (local) return null
        val status = network.status.value
        if (!status.isConnected) return PlayResult.Offline
        if (!status.isMetered) {
            granted = false
            return null
        }
        return when (prefs.current.value.streamOnMetered) {
            StreamOnMetered.ALLOW -> {
                null
            }

            StreamOnMetered.NEVER -> {
                PlayResult.MeteredBlocked
            }

            StreamOnMetered.ASK -> {
                if (origin == StartOrigin.EXTERNAL) granted = true
                if (granted) null else PlayResult.NeedsMeteredConsent(episodeId).also { pending.value = it }
            }
        }
    }

    fun blockBeforeNext(local: Boolean): Boolean {
        if (local || !network.status.value.isMetered) return false
        return when (prefs.current.value.streamOnMetered) {
            StreamOnMetered.ALLOW -> false
            StreamOnMetered.ASK -> !granted
            StreamOnMetered.NEVER -> true
        }
    }

    suspend fun <T> withOrigin(
        origin: StartOrigin,
        block: suspend () -> T,
    ): T = kotlinx.coroutines.withContext(MeteredOrigin(origin)) { block() }

    fun grant() {
        granted = network.status.value.isMetered
        pending.value = null
    }

    fun clearPending() {
        pending.value = null
    }
}

internal enum class StartOrigin { UI, EXTERNAL }

private class MeteredOrigin(
    val origin: StartOrigin,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<MeteredOrigin>
}

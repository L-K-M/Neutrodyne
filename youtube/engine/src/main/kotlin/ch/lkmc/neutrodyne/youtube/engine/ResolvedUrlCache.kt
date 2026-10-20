// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio

/** Memory only. Cache identity excludes the pin, flight identity includes it. */
class ResolvedUrlCache(
    private val clock: Clock,
) {
    private data class Key(
        val videoId: String,
        val pref: AudioPref,
    )

    private val entries = LinkedHashMap<Key, ResolvedAudio>(64, 0.75f, true)

    @Synchronized fun get(
        videoId: String,
        pref: AudioPref,
    ): ResolvedAudio? {
        val key = Key(videoId, pref.copy(pinnedItag = null))
        val entry = entries[key] ?: return null
        if (!valid(entry)) {
            entries.remove(key)
            return null
        }
        return entry.takeIf { pref.pinnedItag == null || pref.pinnedItag == it.itag }
    }

    @Synchronized fun put(
        videoId: String,
        pref: AudioPref,
        audio: ResolvedAudio,
    ) {
        require(videoId == audio.videoId)
        val key = Key(videoId, pref.copy(pinnedItag = null))
        if (!valid(audio)) {
            entries.remove(key)
            return
        }
        entries[key] = audio
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }

    /** Returns whether a refused, non-preroll URL was fresh. Media callers own health reporting. */
    @Synchronized fun invalidate(videoId: String): Boolean {
        val now = clock.now()
        val removed = entries.filterKeys { it.videoId == videoId }.values.toList()
        entries.keys.removeAll { it.videoId == videoId }
        return removed.any {
            now >= it.resolvedAtMs && now - it.resolvedAtMs < FRESH_MS &&
                (it.availableAtMs ?: 0) <= now
        }
    }

    @Synchronized fun invalidateAll() {
        entries.clear()
    }

    private fun valid(audio: ResolvedAudio): Boolean {
        val now = clock.now()
        // Subtractions avoid overflow with untrusted epoch timestamps and backward wall-clock jumps.
        return now >= audio.resolvedAtMs && now - audio.resolvedAtMs < MAX_AGE_MS && audio.expiresAtMs > now &&
            audio.expiresAtMs - now > EXPIRY_MARGIN_MS
    }

    override fun toString(): String = "ResolvedUrlCache(redacted)"

    companion object {
        const val CAPACITY = 64
        const val MAX_AGE_MS = 5 * 60 * 60_000L
        const val EXPIRY_MARGIN_MS = 10 * 60_000L
        const val FRESH_MS = 2 * 60_000L
    }
}

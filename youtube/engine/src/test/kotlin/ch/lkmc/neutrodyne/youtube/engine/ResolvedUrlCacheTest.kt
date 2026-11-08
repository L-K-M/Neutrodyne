// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.AudioQuality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvedUrlCacheTest {
    private val clock = TestClock()
    private val cache = ResolvedUrlCache(clock)
    private val pref = AudioPref()

    @Test fun pinnedLookupReusesMatchingEntryButMismatchDoesNotEvictIt() {
        val audio = resolved(clock)
        cache.put(VIDEO, pref, audio)
        assertEquals(audio, cache.get(VIDEO, pref.copy(pinnedItag = 140)))
        assertNull(cache.get(VIDEO, pref.copy(pinnedItag = 251)))
        assertEquals(audio, cache.get(VIDEO, pref))
        val opus = resolved(clock, 251)
        cache.put(VIDEO, pref.copy(pinnedItag = 251), opus)
        assertEquals(opus, cache.get(VIDEO, pref))
        assertNull(cache.get(VIDEO, pref.copy(pinnedItag = 140)))
    }

    @Test fun allRepresentationPreferencesAndVideoAreIndependentKeys() {
        val audio = resolved(clock)
        val keys =
            listOf(
                pref,
                pref.copy(preferDrc = true),
                pref.copy(quality = AudioQuality.OPUS),
                pref.copy(preferredLanguage = "en"),
                pref.copy(preferredLanguage = "de"),
            )
        keys.forEachIndexed { i, key -> cache.put(VIDEO, key, audio.copy(formatId = "representation$i")) }
        keys.forEachIndexed { i, key -> assertEquals("representation$i", cache.get(VIDEO, key)?.formatId) }
        cache.put(OTHER_VIDEO, pref, audio.copy(videoId = OTHER_VIDEO))
        assertTrue(cache.invalidate(VIDEO))
        keys.forEach { assertNull(cache.get(VIDEO, it)) }
        assertNotNull(cache.get(OTHER_VIDEO, pref))
    }

    @Test fun ttlUsesEarlierExpiryMarginOrFiveHourAgeAtExactBoundary() {
        for (ttl in listOf(1000L, ResolvedUrlCache.MAX_AGE_MS)) {
            val start = clock.nowMs
            cache.put(VIDEO, pref, resolved(clock).copy(expiresAtMs = start + ttl + ResolvedUrlCache.EXPIRY_MARGIN_MS))
            clock.nowMs = start + ttl - 1
            assertNotNull(cache.get(VIDEO, pref))
            clock.nowMs++
            assertNull(cache.get(VIDEO, pref))
        }
    }

    @Test fun staleExpiredNearExpiryAndClockRollbackCannotBeHits() {
        for (audio in listOf(
            resolved(clock).copy(expiresAtMs = clock.nowMs - 1),
            resolved(clock).copy(
                expiresAtMs =
                    clock.nowMs + ResolvedUrlCache.EXPIRY_MARGIN_MS,
            ),
            resolved(clock).copy(resolvedAtMs = clock.nowMs + 1),
        )) {
            cache.put(VIDEO, pref, audio)
            assertNull(cache.get(VIDEO, pref))
        }
        cache.put(VIDEO, pref, resolved(clock))
        clock.nowMs--
        assertNull(cache.get(VIDEO, pref))
    }

    @Test fun lruTouchPreservesHotEntryAndEvictsAt64() {
        val audio = resolved(clock)
        repeat(64) { index -> cache.put("v$index", pref, audio.copy(videoId = "v$index")) }
        assertNotNull(cache.get("v0", pref))
        cache.put("v64", pref, audio.copy(videoId = "v64"))
        assertNotNull(cache.get("v0", pref))
        assertNull(cache.get("v1", pref))
        assertNotNull(cache.get("v64", pref))
        assertFalse(cache.toString().contains("sig="))
    }

    @Test fun invalidationFreshnessBoundaryPrerollAndAllClear() {
        cache.put(VIDEO, pref, resolved(clock))
        assertTrue(cache.invalidate(VIDEO))
        assertFalse(cache.invalidate(VIDEO))
        cache.put(VIDEO, pref, resolved(clock).copy(availableAtMs = clock.nowMs + 1000))
        assertFalse(cache.invalidate(VIDEO))
        cache.put(VIDEO, pref, resolved(clock))
        clock.nowMs += ResolvedUrlCache.FRESH_MS
        assertFalse(cache.invalidate(VIDEO))
        cache.put(VIDEO, pref, resolved(clock))
        cache.invalidateAll()
        assertFalse(cache.invalidate(VIDEO))
        assertNull(cache.get(VIDEO, pref))
    }

    @Test fun concurrentLoaderWritesReadsAndResetsKeepRepresentationKeysIsolated() {
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val audio = resolved(clock)
        try {
            val jobs =
                (0..3).map { worker ->
                    workers.submit {
                        start.await()
                        repeat(500) { turn ->
                            val id = "worker$worker-${turn % 65}"
                            cache.put(id, pref, audio.copy(videoId = id, formatId = "format$worker"))
                            cache.get(id, pref)?.let {
                                assertEquals(id, it.videoId)
                                assertEquals("format$worker", it.formatId)
                            }
                            if (turn % 5 == 0) cache.invalidate(id)
                            if (turn % 31 == 0) cache.invalidateAll()
                        }
                    }
                }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys as Keys

/** One monotonic limiter for all Apple dispatches, including the chart's lookup. */
@SingleIn(AppScope::class)
@Inject
internal class TokenBucket(
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val dispatches = ArrayDeque<Long>()
    private var tokens = CAPACITY
    private var lastRefill = clock.elapsedRealtime()
    private var cooldownUntil = 0L

    suspend fun take(): Boolean =
        mutex.withLock {
            val now = clock.elapsedRealtime()
            val refill = ((now - lastRefill).coerceAtLeast(0) / REFILL_MS).toInt()
            tokens = (tokens + refill).coerceAtMost(CAPACITY)
            lastRefill += refill * REFILL_MS
            while (dispatches.firstOrNull()?.let { now - it >= MINUTE_MS } == true) dispatches.removeFirst()
            if (now < cooldownUntil || tokens == 0 || dispatches.size >= CAPACITY) return@withLock false
            tokens--
            dispatches.addLast(now)
            true
        }

    suspend fun cooldown() =
        mutex.withLock {
            cooldownUntil = clock.elapsedRealtime() + MINUTE_MS
            tokens = 0
            lastRefill = clock.elapsedRealtime()
        }

    private companion object {
        const val CAPACITY = 20
        const val REFILL_MS = 3_000L
        const val MINUTE_MS = 60_000L
    }
}

/** Success-only bounded LRU; active coalesced callers retry a cancelled owner. */
internal class DirectoryCache<T>(
    private val clock: Clock,
    private val capacity: Int,
    private val ttlMs: Long,
) {
    private class Entry<T>(
        val at: Long,
        val value: T,
    )

    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry<T>>()
    private val running = mutableMapOf<String, CompletableDeferred<Outcome<T, ProviderStatus>>>()

    suspend fun load(
        key: String,
        block: suspend () -> Outcome<T, ProviderStatus>,
    ): Outcome<T, ProviderStatus> {
        while (true) {
            var owner = false
            val pending =
                mutex.withLock {
                    read(key)?.let { return Outcome.Success(it) }
                    running[key] ?: CompletableDeferred<Outcome<T, ProviderStatus>>().also {
                        if (running.size >= capacity) return Outcome.Failure(ProviderStatus.RateLimited)
                        running[key] = it
                        owner = true
                    }
                }
            if (!owner) {
                try {
                    return pending.await()
                } catch (_: CancellationException) {
                    // A cancelled loader must not cancel an unrelated live caller.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val result = block()
                if (result is Outcome.Success) put(key, result.value)
                pending.complete(result)
                return result
            } finally {
                // Release shared waiters even when the loader is cancelled.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    mutex.withLock {
                        running.remove(key)
                        pending.cancel()
                    }
                }
            }
        }
    }

    suspend fun get(key: String): T? = mutex.withLock { read(key) }

    suspend fun put(
        key: String,
        value: T,
    ) = mutex.withLock {
        entries.remove(key)
        entries[key] = Entry(clock.elapsedRealtime(), value)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** Called only while holding mutex; reads update recency and discard expired values. */
    private fun read(key: String): T? {
        val entry = entries.remove(key) ?: return null
        if (clock.elapsedRealtime() - entry.at >= ttlMs) return null
        entries[key] = entry
        return entry.value
    }
}

/** Secrets are confined here and PI request construction; public state carries only provenance. */
internal class DirectoryConfiguration(
    val country: String,
    val apple: Boolean,
    val fyyd: Boolean,
    val pi: Boolean,
    val pair: Pair<String, String>?,
    val source: DirectoryKeySource,
    val version: Long,
) {
    fun effective(id: ProviderId): Boolean =
        when (id) {
            ProviderId.APPLE -> apple
            ProviderId.FYYD -> fyyd
            ProviderId.PODCAST_INDEX -> pi && pair != null
        }
}

@SingleIn(AppScope::class)
@Inject
internal class DirectorySettings(
    private val settings: SettingsRepository,
    private val secrets: () -> SecretStore,
    private val build: BuildInfo,
    private val platform: PlatformInfo,
) {
    private val mutex = Mutex()
    private var previous: List<Any?>? = null
    private var version = 0L
    private val keyRevision = MutableStateFlow(0L)
    val changes: Flow<DirectoryConfiguration> =
        combine(
            settings.observe(Keys.COUNTRY),
            settings.observe(Keys.APPLE_ENABLED),
            settings.observe(Keys.FYYD_ENABLED),
            settings.observe(Keys.PODCASTINDEX_ENABLED),
            keyRevision,
        ) { _, _, _, _, _ -> current() }.distinctUntilChangedBy { it.version }

    suspend fun current(): DirectoryConfiguration {
        secrets().awaitLoaded()
        val user = secrets().podcastIndexKey()?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
        val pair =
            user
                ?: (build.podcastIndexKey to build.podcastIndexSecret).takeIf {
                    it.first.isNotBlank() &&
                        it.second.isNotBlank()
                }
        val source =
            when {
                user != null -> DirectoryKeySource.USER
                pair != null -> DirectoryKeySource.BUILD
                else -> DirectoryKeySource.NONE
            }
        val country =
            settings.get(Keys.COUNTRY).ifEmpty { platform.regionCode.uppercase() }.takeIf {
                it.length == 2 &&
                    it.all { c -> c in 'A'..'Z' }
            }
                ?: "US"
        val apple = settings.get(Keys.APPLE_ENABLED)
        val fyyd = settings.get(Keys.FYYD_ENABLED)
        val pi = settings.get(Keys.PODCASTINDEX_ENABLED)
        val revision =
            mutex.withLock {
                val state = listOf(country, apple, fyyd, pi, pair, source, keyRevision.value)
                if (state != previous) {
                    previous = state
                    version++
                }
                version
            }
        return DirectoryConfiguration(country, apple, fyyd, pi, pair, source, revision)
    }

    fun keyChanged() {
        keyRevision.update { it + 1 }
    }
}

/** Memory-only handoff from search/charts to Add; provider changes cannot replay stale metadata. */
@SingleIn(AppScope::class)
@Inject
internal class DirectoryMetadataCache(
    clock: Clock,
    private val settings: DirectorySettings,
) {
    private val cache = DirectoryCache<DirectoryHit>(clock, MAX_HITS, TTL_MS)

    suspend fun remember(
        version: Long,
        hits: List<DirectoryHit>,
    ) {
        for (hit in hits) cache.put(key(version, hit.feedUrl), hit)
    }

    suspend fun forFeed(url: String): DirectoryHit? = cache.get(key(settings.current().version, url))

    private fun key(
        version: Long,
        url: String,
    ): String = "$version:${UrlNormalizer.forIdentity(url)}"

    private companion object {
        const val MAX_HITS = 150
        const val TTL_MS = 30 * 60 * 1000L
    }
}

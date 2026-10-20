// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** One owner per process. Construction does not open the index or create a directory. */
@SingleIn(AppScope::class)
internal class StreamingCache internal constructor(
    private val directory: File,
    private val database: DatabaseProvider,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    prefs: StateFlow<PlaybackPrefsSnapshot>? = null,
) : PlaybackMaintenance {
    @Inject
    constructor(
        application: Application,
        @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
        prefs: PlaybackPrefs,
    ) : this(File(application.filesDir, DIRECTORY), StandaloneDatabaseProvider(application), io, scope, prefs.current)

    private val ownership = Any()
    private val activeKeys = ConcurrentHashMap<String, Int>()
    private val evictor = AdjustableLruCacheEvictor(defaultBytes(), activeKeys::containsKey)
    private var instance: SimpleCache? = null

    init {
        if (prefs != null) {
            evictor.maxBytes = prefs.value.streamCacheMb * BYTES_PER_MB
            scope.launch(io) {
                prefs.map { it.streamCacheMb }.distinctUntilChanged().collect { resize(it) }
            }
        }
    }

    /** First player/data-source creation only; Media3 initializes its index on its own thread. */
    fun cache(): SimpleCache =
        synchronized(ownership) {
            instance ?: SimpleCache(directory, evictor, database).also { instance = it }
        }

    /** Loader-thread operation, serialized against maintenance and other new pins. */
    internal fun pinRemote(key: String) =
        synchronized(ownership) {
            try {
                resetResource(key)
            } catch (e: Cache.CacheException) {
                // Spans were removed, or initialization failed before any reads were possible.
                // Let CacheDataSource observe the error and bypass caching on its next retry.
                Log.w("StreamingCache", e) { "Streaming cache unavailable" }
            }
            activeKeys.merge(key, 1, Int::plus)
            Unit
        }

    /** YouTube alone reuses bytes across sessions; reject a known incompatible stored length. */
    internal fun pinYouTube(
        key: String,
        length: Long?,
        resetKey: String?,
    ) = synchronized(ownership) {
        try {
            resetKey?.let(::resetResource)
            val cache = cache()
            val previous =
                androidx.media3.datasource.cache.ContentMetadata
                    .getContentLength(cache.getContentMetadata(key))
            if (previous != C.LENGTH_UNSET.toLong() && previous != length) resetResource(key)
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also {
                    ContentMetadataMutations.setRedirectedUri(it, null)
                },
            )
        } catch (e: Cache.CacheException) {
            // As for RSS, spans were removed or initialization failed before cache reads.
            Log.w("StreamingCache", e) { "Streaming cache unavailable" }
        }
        activeKeys.merge(key, 1, Int::plus)
        Unit
    }

    /** A reset also removes redirect/length metadata, which removeResource alone leaves behind. */
    fun resetResource(key: String) =
        synchronized(ownership) {
            val cache = cache()
            cache.removeResource(key)
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also {
                    ContentMetadataMutations.setContentLength(it, C.LENGTH_UNSET.toLong())
                    ContentMetadataMutations.setRedirectedUri(it, null)
                },
            )
        }

    internal fun unpin(key: String) {
        activeKeys.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
        // A pinned resource may temporarily exceed the limit; enforce it after cancellation.
        scope.launch(io) { synchronized(ownership) { instance?.let(evictor::trim) } }
    }

    suspend fun resize(maxMb: Int) =
        withContext(io) {
            require(maxMb in PlaybackSettingKeys.STREAM_CACHE_MB_VALUES)
            synchronized(ownership) {
                evictor.maxBytes = maxMb * BYTES_PER_MB
                instance?.let(evictor::trim)
            }
        }

    override suspend fun streamingCacheBytes(): Long =
        withContext(io) {
            cache().cacheSpace
        }

    override suspend fun clearStreamingCache() =
        withContext(io) {
            synchronized(ownership) {
                val cache = cache()
                cache.keys.filterNot(activeKeys::containsKey).forEach(cache::removeResource)
            }
        }

    companion object {
        const val DIRECTORY = "media-cache"
        private const val BYTES_PER_MB = 1_000_000L

        private fun defaultBytes(): Long = PlaybackSettingKeys.STREAM_CACHE_MB.default * BYTES_PER_MB
    }
}

/** All span bookkeeping uses the cache monitor, including explicit trims on the IO dispatcher. */
internal class AdjustableLruCacheEvictor(
    @Volatile var maxBytes: Long,
    private val isPinned: (String) -> Boolean,
) : CacheEvictor {
    private val spans = TreeSet(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
    private var bytes = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        trim(cache, length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: 0)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (spans.add(span)) bytes += span.length
        trim(cache)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (spans.remove(span)) bytes -= span.length
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun trim(
        cache: Cache,
        required: Long = 0,
    ) = synchronized(cache) {
        while (bytes > maxBytes - required) {
            val oldest = spans.firstOrNull { !isPinned(it.key) } ?: break
            cache.removeSpan(oldest)
        }
    }
}

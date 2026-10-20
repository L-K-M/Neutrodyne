// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import dev.zacsweers.metro.Inject

/** Resolve before routing: completed files never pass through the streaming cache/HTTP layer. */
internal class NdMediaSourceFactory internal constructor(
    private val dataSources: DataSource.Factory,
    policy: NdLoadErrorHandlingPolicy,
) : MediaSource.Factory {
    @Inject
    constructor(
        application: Application,
        cache: StreamingCache,
        resolver: EpisodeResolver,
        http: GuardedHttpDataSource.Factory,
        policy: NdLoadErrorHandlingPolicy,
    ) : this(
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(
                application,
                CacheDataSource
                    .Factory()
                    .setCache(cache.cache())
                    .setUpstreamDataSourceFactory(http)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            ),
            resolver,
        ),
        policy,
    )

    private val remote =
        DefaultMediaSourceFactory(
            dataSources,
            extractors(LocalHint.REMOTE),
        ).setLoadErrorHandlingPolicy(policy)
    private val local =
        DefaultMediaSourceFactory(
            dataSources,
            extractors(LocalHint.LOCAL),
        ).setLoadErrorHandlingPolicy(policy)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        (if (mediaItem.requestMetadata.extras?.getBoolean(MediaItemFactory.LOCAL_HINT) == true) local else remote)
            .createMediaSource(mediaItem)

    override fun getSupportedTypes(): IntArray = remote.supportedTypes

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): NdMediaSourceFactory =
        apply {
            remote.setDrmSessionManagerProvider(drmSessionManagerProvider)
            local.setDrmSessionManagerProvider(drmSessionManagerProvider)
        }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): NdMediaSourceFactory =
        apply {
            remote.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            local.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        }

    private enum class LocalHint { LOCAL, REMOTE }

    private fun extractors(hint: LocalHint): DefaultExtractorsFactory =
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setDisableArtworkMetadata(true)
            .apply { if (hint == LocalHint.LOCAL) setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING) }
}

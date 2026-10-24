// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment
import java.io.File

internal class SourceHarness(
    directory: File,
    prefs: kotlinx.coroutines.flow.StateFlow<PlaybackPrefsSnapshot>? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val owner =
        StreamingCache(
            directory,
            StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()),
            Dispatchers.Unconfined,
            scope,
            prefs,
        )
    val local = FakeLocalMediaIndex()
    val index = EpisodeSourceIndex { error("Unexpected database miss for $it") }
    val resolver = EpisodeResolver(index, local, owner)

    fun resolve(
        id: Long = 1,
        position: Long = 0,
        length: Long =
            androidx.media3.common.C.LENGTH_UNSET
                .toLong(),
    ): DataSpec =
        resolver.resolveDataSpec(
            DataSpec
                .Builder()
                .setUri(episodeUri(id))
                .setPosition(position)
                .setLength(length)
                .build(),
        )

    override fun close() {
        resolver.clearPins()
        scope.cancel()
        owner.cache().release()
    }
}

internal fun media(
    id: Long = 1,
    url: String? = "https://media.example/episode.mp3?token=private",
    type: SourceType = SourceType.RSS,
): PlaybackMediaInfo =
    PlaybackMediaInfo(
        episodeId = id,
        podcastId = 10,
        title = "Episode",
        podcastTitle = "Podcast",
        artwork = ArtworkRef("episode-art", null, 1),
        sourceType = type,
        availability = Availability.AVAILABLE,
        isVideo = false,
        enclosureUrl = url,
        enclosureLength = null,
        externalMediaId = if (type != SourceType.RSS) "abcdefghijk" else null,
        durationMs = 60_000,
        measuredDurationMs = null,
        playedAt = null,
        podcastArtwork = ArtworkRef("podcast-art", null, 2),
    )

internal fun Cache.write(
    key: String,
    text: String,
    position: Long = 0,
) {
    val bytes = text.toByteArray()
    val hole = checkNotNull(startReadWrite(key, position, bytes.size.toLong()))
    try {
        val file = startFile(key, position, bytes.size.toLong())
        file.writeBytes(bytes)
        commitFile(file, bytes.size.toLong())
    } finally {
        releaseHoleSpan(hole)
    }
}

/** Uses the production MEDIA client; desktop loopback exemption allows MockWebServer only. */
internal fun mediaClients(credentials: CredentialLookup = CredentialLookup.None): NetworkClients {
    val platform =
        object : PlatformInfo {
            override val kind = PlatformKind.DESKTOP
            override val userAgentPlatform = "Linux; x64"
            override val androidSdkInt: Int? = null
            override val regionCode = "DE"
        }
    val core =
        CoreClients(
            UserAgentInterceptor(UserAgentProvider(platform, "test", "https://example.com/repo")),
            LocalNetworkGuard(LocalNetworkAccess(), platform),
            DnsFamilyHints(),
            emptySet(),
        )
    return NetworkClients(core, AuthInterceptor { credentials })
}

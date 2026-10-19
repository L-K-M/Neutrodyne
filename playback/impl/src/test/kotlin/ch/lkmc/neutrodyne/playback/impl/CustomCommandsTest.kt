// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.StartRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomCommandsTest {
    @Test
    fun allM4StartRequestsRoundTripWithoutLosingFilters() {
        val requests =
            listOf(
                StartRequest.Resume,
                StartRequest.Episode(4),
                StartRequest.Episode(4, 0),
                StartRequest.Feed(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST),
                StartRequest.Feed(FeedSource.Ungrouped, FeedFilters(unplayedOnly = true), FeedOrder.OLDEST_FIRST),
                StartRequest.Feed(
                    FeedSource.Group(8),
                    FeedFilters(true, true, true, MediaFilter.VIDEO, 77),
                    FeedOrder.OLDEST_FIRST,
                    4,
                    99,
                ),
                StartRequest.Feed(
                    FeedSource.Podcast(2),
                    FeedFilters(media = MediaFilter.AUDIO),
                    FeedOrder.NEWEST_FIRST,
                    4,
                ),
            )
        requests.forEach { assertEquals(it, CustomCommands.decode(CustomCommands.encode(it))) }
    }

    @Test
    fun refusedAndAcceptedResultsRoundTrip() {
        val results =
            listOf(
                PlayResult.Started,
                PlayResult.NothingToPlay(),
                PlayResult.NothingToPlay(true),
                PlayResult.NeedsMeteredConsent(4),
                PlayResult.MeteredBlocked,
                PlayResult.Offline,
                PlayResult.ServiceUnavailable,
                PlayResult.NotPlayable(4, UnplayableReason.NoMedia),
                PlayResult.NotPlayable(4, UnplayableReason.Http(404)),
                PlayResult.NotPlayable(4, UnplayableReason.YouTubeExternal(ExternalReason.DISABLED_BY_USER)),
            )
        results.forEach { assertEquals(it, CustomCommands.playResult(CustomCommands.result(it))) }
    }

    @Test
    fun malformedCommandsAndArbitraryBrowseUrisAreRejected() {
        assertFailsWith<IllegalArgumentException> { CustomCommands.decode(Bundle()) }
        val negativeId = Bundle().apply { putLong("startEpisodeId", -1) }
        assertFailsWith<IllegalArgumentException> { CustomCommands.decode(negativeId) }
        val negativePosition =
            Bundle().apply {
                putLong("startEpisodeId", 1)
                putLong("startPositionMs", -1)
            }
        assertFailsWith<IllegalArgumentException> { CustomCommands.decode(negativePosition) }
        val invalidIds =
            listOf(
                "https://evil.test/file",
                "episode:0",
                "episode:1@group:7",
                "episode:1@upnext/trailing",
                "episode:01",
            )
        invalidIds.forEach {
            assertFailsWith<IllegalArgumentException> { SessionCallback.parseBrowseEpisode(it) }
        }
        assertEquals(4L, SessionCallback.parseBrowseEpisode("episode:4@upnext"))
    }
}

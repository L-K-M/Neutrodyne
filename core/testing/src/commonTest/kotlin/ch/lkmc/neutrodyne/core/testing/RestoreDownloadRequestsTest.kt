// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.RestoreDownloadRequests
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreDownloadRequestsTest {
    @Test fun offerDoesNotRequestAndAcceptedConsentUsesManualLane() =
        runTest {
            val downloads = FakeDownloadController()
            val media = FakePlaybackMediaLookup()
            val row =
                PlaybackMediaInfo(
                    1,
                    1,
                    "Episode",
                    "Podcast",
                    ArtworkRef("p-1", null, 0),
                    SourceType.RSS,
                    Availability.AVAILABLE,
                    false,
                    "https://example.com/e.mp3",
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            media.items.value =
                mapOf(
                    1L to row,
                    2L to row.copy(episodeId = 2, downloadState = DownloadState.COMPLETED),
                    3L to row.copy(episodeId = 3, sourceType = SourceType.YOUTUBE_CHANNEL),
                )
            val requests = RestoreDownloadRequests(downloads, media)
            val ids = listOf(1L, 2L, 3L, 4L, 1L)
            assertEquals(listOf(1L), requests.eligible(ids))
            assertTrue(downloads.calls.isEmpty())
            downloads.requestResult = RequestResult.NeedsMeteredDecision(1, 42, 0)
            assertIs<RequestResult.NeedsMeteredDecision>(requests.request(ids))
            assertNull(downloads.calls.single().allowMetered)
            downloads.requestResult = RequestResult.Queued(1, 0, emptyMap(), false)
            requests.request(ids, true)
            assertEquals(DownloadLane.MANUAL, downloads.calls.last().lane)
            assertEquals(listOf(1L), downloads.calls.last().ids)
            assertEquals(true, downloads.calls.last().allowMetered)
        }
}

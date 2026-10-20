// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine-off / no-engine / gate / pacing rules, through the real DAO and journal. */
class YouTubeEngineRulesTest {
    @Test fun disabledEngineMarksOnlyQueuedYouTubeWaits() =
        engineTest {
            val youtubes = seedYouTube(2)
            val rss = seed().single()
            caps.capabilities.value =
                YouTubeCapabilities(true, false, false, true, false, ExternalReason.DISABLED_BY_USER)
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
            youtubes.forEach { assertEquals(WaitReason.YOUTUBE_ENGINE_OFF, row(it).waitReason) }
            assertEquals(WaitReason.NONE, row(rss).waitReason)
        }

    @Test fun reEnableClearsEngineWaitsButPreservesBackoff() =
        engineTest {
            val ids = seedYouTube(2)
            dao.updateUnclaimed(
                checkNotNull(dao.byEpisode(ids[1])).copy(
                    waitReason = WaitReason.BACKOFF,
                    nextAttemptAt = clock.now() + 10_000,
                ),
                DownloadState.QUEUED,
            )
            caps.capabilities.value =
                YouTubeCapabilities(true, false, false, true, false, ExternalReason.DISABLED_BY_USER)
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
            assertEquals(WaitReason.YOUTUBE_ENGINE_OFF, row(ids[0]).waitReason)
            assertEquals(WaitReason.YOUTUBE_ENGINE_OFF, row(ids[1]).waitReason)
            caps.capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
            assertEquals(WaitReason.NONE, row(ids[0]).waitReason)
            assertNull(row(ids[0]).nextAttemptAt)
            assertEquals(WaitReason.NONE, row(ids[1]).waitReason)
            assertEquals(clock.now() + 10_000, row(ids[1]).nextAttemptAt)
            assertTrue(
                dao.candidates(listOf(DownloadLane.AUTO), clock.now(), true, true, true).none {
                    it.episodeId == ids[1]
                },
            )
        }

    @Test fun absentEngineFailsUnfinishedAndJournalsPartsButKeepsCompleted() =
        engineTest {
            val ids = seedYouTube(3)
            dao.updateUnclaimed(
                checkNotNull(dao.byEpisode(ids[2])).copy(state = DownloadState.COMPLETED),
                DownloadState.QUEUED,
            )
            val part = files.openPart("old", DownloadPaths.part(ids[0]))
            part.append(mediaBytes(4096), 4096)
            part.close()
            dao.updateUnclaimed(
                checkNotNull(dao.byEpisode(ids[0])).copy(tempPath = DownloadPaths.part(ids[0])),
                DownloadState.QUEUED,
            )
            caps.capabilities.value =
                YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_IN_THIS_APK)
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
            assertEquals(DownloadState.FAILED, row(ids[0]).state)
            assertEquals(DownloadError.UNSUPPORTED_STREAM, row(ids[0]).lastError)
            assertEquals(DownloadState.FAILED, row(ids[1]).state)
            assertEquals(DownloadState.COMPLETED, row(ids[2]).state)
            assertNull(files.stat("old", DownloadPaths.part(ids[0])))
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
        }

    @Test fun extractionGateMarksBoundedBackoffWaits() =
        engineTest {
            val ids = seedYouTube(1)
            youtubeHealth.gate = ExtractionGate.Deny(clock.now() + 90_000_000, TransientKind.RATE_LIMITED)
            engine.claimMutex.withLock { youtubeRules.syncLocked() }
            assertEquals(WaitReason.BACKOFF, row(ids[0]).waitReason)
            assertEquals(clock.now() + 1_800_000, row(ids[0]).nextAttemptAt)
        }

    @Test fun pacerAdmitsTwentyStartsThenDefers() =
        runBlocking {
            val pacer = YouTubeAutoPacer(TestClock(nowMs = 1_000_000))
            repeat(20) { assertNull(pacer.admit()) }
            assertTrue(checkNotNull(pacer.admit()) > 0)
        }
}

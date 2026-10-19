// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterResult
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.LiveProgress
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnavailablePlaybackController
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeDownloadContractsTest {
    @Test fun unconfiguredRequestsAndDeferredOperationsNeverSucceed() =
        runTest {
            val fake = FakeDownloadController()
            val queued = assertIs<RequestResult.Queued>(fake.request(listOf(1, 2), DownloadLane.MANUAL))
            assertEquals(0, queued.queued)
            assertEquals(mapOf(1L to RejectReason.UNAVAILABLE, 2L to RejectReason.UNAVAILABLE), queued.rejected)
            assertEquals(RootChangeResult.Unavailable, fake.changeRoot("int", RootChangeMode.MOVE_EXISTING))
            assertEquals(FolderRootResult.Rejected(FolderRejectReason.UNSUPPORTED), fake.registerFolderRoot("/folder"))
            assertEquals(OrphanDeleteResult.Unavailable, fake.deleteOrphanFiles())
            assertFalse(fake.observeBusy().first())
            assertEquals(0, fake.observeFailedCount().first())
            assertNull(fake.observe(1).first())
            assertTrue(
                fake
                    .observeAll()
                    .first()
                    .inProgress
                    .isEmpty(),
            )
            assertNull(fake.shareableFile(1))
        }

    @Test fun recorderCopiesInputsAndPreservesConsentAndDeletionReason() =
        runTest {
            val fake = FakeDownloadController()
            val ids = mutableListOf(4L)
            val decision = RequestResult.NeedsMeteredDecision(1, 0, 1)
            fake.requestResult = decision
            assertEquals(decision, fake.request(ids, DownloadLane.MANUAL, null))
            ids.clear()
            fake.delete(listOf(4), DeleteReason.LIFECYCLE)
            fake.setAllowMetered(listOf(4), true)
            assertEquals(listOf(4L), fake.calls.first().ids)
            assertNull(fake.calls.first().allowMetered)
            assertEquals(DeleteReason.LIFECYCLE, fake.calls[1].reason)
            assertTrue(fake.calls[2].allowMetered == true)
            assertTrue(fake.statuses.value.isEmpty())
            fake.failure = IllegalStateException("unavailable")
            assertFailsWith<IllegalStateException> { fake.pauseAll() }
        }

    @Test fun progressOnlyIncludesRequestedIdsAndCapturesSelection() =
        runTest {
            val fake = FakeDownloadProgressSource()
            val ids = mutableSetOf(1L)
            val flow = fake.observe(ids)
            ids += 2L
            val tick = LiveProgress(DownloadState.DOWNLOADING, 12, null, null)
            fake.progress.value = mapOf(1L to tick, 2L to tick)
            assertEquals(mapOf(1L to tick), flow.first())
            assertEquals(emptyMap(), fake.observe(emptySet()).first())
        }

    @Test fun completionPublishesIndexBeforeNotificationAndRemovalInvalidates() =
        runTest {
            val index = FakeLocalMediaIndex()
            assertNull(index.localUriOrNull(1))
            var seen: String? = null
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    assertEquals(setOf(1L), index.changes.first())
                    seen = index.localUriOrNull(1)
                }
            index.publish(mapOf(1L to "file:///episode.mp3"))
            observer.join()
            assertEquals("file:///episode.mp3", seen)
            val removal =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    assertEquals(setOf(1L), index.changes.first())
                    assertNull(index.localUriOrNull(1))
                }
            index.publish(emptyMap())
            removal.join()
        }

    @Test fun lifecyclePortsRecordInputsAndPropagateFailure() =
        runTest {
            val lifecycle = FakeDownloadLifecycle()
            lifecycle.beforeEpisodeRemoval(listOf(1))
            lifecycle.beforeEpisodeMerge(2, 3)
            lifecycle.beforeLibraryReplace()
            assertEquals(listOf(listOf(1L)), lifecycle.removals)
            assertEquals(listOf(2L to 3L), lifecycle.merges)
            assertEquals(1, lifecycle.replacements)
            lifecycle.failure = IllegalStateException("journal failed")
            assertFailsWith<IllegalStateException> { lifecycle.beforeLibraryReplace() }
            assertEquals(1, lifecycle.replacements)
            val reporter = FakeMissingLocalMediaReporter()
            reporter.reportFileMissing(3)
            assertEquals(listOf(3L), reporter.reports)
        }

    @Test fun localChapterAndPlaybackStubsReportUnavailable() =
        runTest {
            val reader = FakeEmbeddedChapterReader()
            assertEquals(EmbeddedChapterResult.Unavailable, reader.read("file:///local"))
            assertEquals(listOf("file:///local"), reader.reads)
            val chapters = FakeChapterRepository()
            assertEquals(EmbeddedChapterResult.Unavailable, chapters.ensureLocalLoaded(1, "file:///local"))
            assertEquals(listOf(1L to "file:///local"), chapters.localLoads)
            val playback = FakePlaybackController()
            assertEquals(PlayResult.ServiceUnavailable, playback.playDownloads(1))
            assertEquals(listOf<FakePlaybackController.Call>(FakePlaybackController.Downloads(1)), playback.calls)
            assertEquals(PlayResult.ServiceUnavailable, UnavailablePlaybackController().playDownloads())
        }
}

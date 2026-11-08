// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import ch.lkmc.neutrodyne.core.domain.YouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeAvailabilityRecorder
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.RecoveryBudget
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ErrorRecoveryTest {
    @Test fun breakerSkipsRemoteYouTubeProjectionWithoutDeletingQueuedRows() =
        runTest {
            val f = AdapterFixture(this)
            try {
                f.caps.capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
                val youtube = f.seed(3, youtube = true)
                val rss = f.seed(1).single()
                val ids = youtube + rss
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                recovery.onPlayerError(
                    error(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        YouTubeResolveException(ResolveResult.Transient(IOException(), TransientKind.BREAKER_OPEN)),
                    ),
                )
                awaitAdapter { f.player.episodeIdOrNull() == rss }
                awaitAdapter { f.player.ids().firstOrNull() == rss }
                assertEquals(youtube, f.events.filterIsInstance<PlaybackEvent.Skipped>().map { it.episodeId })
                assertEquals(
                    youtube.drop(1),
                    f.db
                        .queueDao()
                        .entries()
                        .map { it.episodeId },
                )
                for (id in youtube) {
                    assertEquals(
                        Availability.AVAILABLE,
                        f.db
                            .episodeDao()
                            .byId(id)!!
                            .availability,
                    )
                    assertEquals(
                        null,
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.playedAt,
                    )
                }
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun exhaustedYouTubeHttpIsNotRssAuthenticationOrMissingMedia() =
        runTest {
            val f = AdapterFixture(this)
            try {
                f.caps.capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
                val ids = f.seed(1, youtube = true)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                // The fake source does not open: explicitly establish the eligible YouTube pin.
                assertThrows(YouTubeResolveException::class.java) {
                    f.resolver.resolveDataSpec(DataSpec.Builder().setUri(episodeUri(ids.single())).build())
                }
                val recovery = recovery(f)
                recovery.attach(f.player)
                for (status in listOf(401, 403, 404, 410, 416)) {
                    f.player.play()
                    recovery.onPlayerError(http(status))
                    assertFalse(f.player.playWhenReady)
                    assertEquals(ids.single(), f.player.episodeIdOrNull())
                    assertTrue(f.events.isEmpty())
                }
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun everyTypedOutcomePrecedesGenericIoAndValidatedNetworkPauses() =
        runTest {
            val results =
                listOf(
                    ResolveResult.Unsupported,
                    *TransientKind.entries.map { ResolveResult.Transient(IOException(), it) }.toTypedArray(),
                )
            for (result in results) {
                val f = AdapterFixture(this)
                try {
                    val ids = f.seed(2)
                    f.attach()
                    f.start(ids, 60_000)
                    awaitAdapter { f.player.ids() == ids }
                    val recovery = recovery(f, FakeNetworkMonitor(FakeNetworkMonitor.ONLINE))
                    recovery.attach(f.player)
                    f.player.play()
                    recovery.onPlayerError(
                        error(
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            YouTubeResolveException(result).also { it.initCause(http(403)) },
                        ),
                    )
                    val skips =
                        result == ResolveResult.Unsupported ||
                            (
                                result is ResolveResult.Transient &&
                                    result.kind in setOf(TransientKind.EXTRACTION, TransientKind.BREAKER_OPEN)
                            )
                    if (skips) {
                        awaitAdapter { f.player.episodeIdOrNull() == ids[1] }
                        assertEquals(1, f.events.size)
                    } else {
                        assertFalse(f.player.playWhenReady)
                        assertEquals(ids[0], f.player.episodeIdOrNull())
                        assertEquals(60_000L, f.player.currentPosition)
                        assertEquals(0, f.events.size)
                    }
                    assertEquals(
                        null,
                        f.db
                            .episodeStateDao()
                            .byEpisode(ids[0])
                            ?.playedAt,
                    )
                    recovery.detach()
                } finally {
                    f.close()
                }
            }
        }

    @Test fun unavailableRecordsRealRowAndCannotSkipANewerOccurrence() =
        runTest {
            val f = AdapterFixture(this)
            try {
                f.caps.capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
                val ids = f.seed(2, youtube = true)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val real = PlaybackTestStores.availability(f.db)
                val recorder =
                    object : YouTubeAvailabilityRecorder {
                        override suspend fun record(
                            episodeId: Long,
                            reason: Availability,
                        ) {
                            withContext(NonCancellable) {
                                real.record(episodeId, reason)
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                val recovery = recovery(f, recorder = recorder)
                recovery.attach(f.player)
                recovery.onPlayerError(
                    error(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        YouTubeResolveException(ResolveResult.Unavailable(Availability.PRIVATE)),
                    ),
                )
                awaitAdapter { entered.isCompleted }
                assertEquals(
                    Availability.PRIVATE,
                    f.db
                        .episodeDao()
                        .byId(ids[0])!!
                        .availability,
                )
                f.projector.advance()
                awaitAdapter { f.player.episodeIdOrNull() == ids[1] }
                release.complete(Unit)
                runCurrent()
                assertEquals(ids[1], f.player.episodeIdOrNull())
                assertTrue(f.events.isEmpty())
                assertEquals(
                    Availability.AVAILABLE,
                    f.db
                        .episodeDao()
                        .byId(ids[1])!!
                        .availability,
                )
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun formatChangeRepreparesOnceAtCurrentTimeWithoutUserSeekReset() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids, 60_000)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                val old =
                    ch.lkmc.neutrodyne.youtube.api
                        .YouTubeRepresentationKey("140", 6, 123)
                val new = old.copy(lastModifiedMicros = 124)
                f.player.calls.clear()
                recovery.onPlayerError(
                    error(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        YouTubeFormatChangedException("abcdefghijk", old, new),
                    ),
                )
                assertEquals(1, f.player.calls.count { it == "prepare" })
                assertEquals(60_000L, f.player.currentPosition)
                recovery.onPlayerError(
                    error(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        YouTubeFormatChangedException("abcdefghijk", old, new),
                    ),
                )
                awaitAdapter { f.player.episodeIdOrNull() == ids[1] }
                assertEquals(UnplayableReason.YouTubeExtraction, (f.events.single() as PlaybackEvent.Skipped).reason)
                assertEquals(
                    null,
                    f.db
                        .episodeStateDao()
                        .byEpisode(ids[0])
                        ?.playedAt,
                )
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun typedRateLimitPausesBeforeGenericIoRecovery() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(1)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                f.player.play()
                recovery.onPlayerError(
                    error(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException(
                            ch.lkmc.neutrodyne.youtube.api.ResolveResult.Transient(
                                IOException(),
                                ch.lkmc.neutrodyne.youtube.api.TransientKind.RATE_LIMITED,
                            ),
                        ),
                    ),
                )
                assertEquals(PlaybackIssue.YOUTUBE_RATE_LIMITED, recovery.issue.value)
                assertFalse(f.player.playWhenReady)
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun rssHttpAndParserFailuresSkipWithReasonWithoutMarkingPlayed() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(5)
                f.attach()
                f.start(ids, AdapterFixture.DURATION_MS - 1000)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                // A near-end item that has played would be finished by an ordinary SEEK.
                f.tracker.onIsPlayingChanged(true)
                runCurrent()
                for ((offset, status) in listOf(404, 410, 401, 403).withIndex()) {
                    recovery.onPlayerError(http(status))
                    awaitAdapter { f.player.episodeIdOrNull() == ids[offset + 1] }
                    awaitAdapter { f.player.ids().firstOrNull() == ids[offset + 1] }
                    val event = f.events.last() as PlaybackEvent.Skipped
                    assertEquals(ids[offset], event.episodeId)
                    val reason =
                        if (status in listOf(401, 403)) UnplayableReason.AuthRequired else UnplayableReason.Http(status)
                    assertEquals(reason, event.reason)
                    assertEquals(
                        null,
                        f.db
                            .episodeStateDao()
                            .byEpisode(ids[offset])
                            ?.playedAt,
                    )
                }
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
                assertEquals(UnplayableReason.UnsupportedFormat, (f.events.last() as PlaybackEvent.Skipped).reason)
                assertEquals(PlaybackIssue.PLAYER_ERROR, recovery.issue.value)
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun contentChangeRetriesOnceThenSkips() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids, 60_000)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                f.player.calls.clear()
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, ContentChangedException()))
                assertEquals(1, f.player.calls.count { it == "prepare" })
                assertEquals(60_000L, f.player.currentPosition)
                recovery.onPlayerError(http(416))
                awaitAdapter { f.player.episodeIdOrNull() == ids[1] }
                assertEquals(UnplayableReason.Http(416), (f.events.single() as PlaybackEvent.Skipped).reason)
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun audioOutputRetriesOnceThenPauses() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(1)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                f.player.calls.clear()
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
                assertEquals(1, f.player.calls.count { it == "prepare" })
                assertFalse(f.player.playWhenReady)
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun reconnectHonorsLimits() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(1)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val network = FakeNetworkMonitor()
                var foreground = true
                val recovery = recovery(f, network) { foreground }
                recovery.attach(f.player)
                f.player.play()
                runCurrent()
                f.player.calls.clear()
                repeat(4) {
                    network.setStatus(FakeNetworkMonitor.OFFLINE)
                    runCurrent()
                    recovery.onPlayerError(
                        error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException()),
                    )
                    assertEquals(PlaybackIssue.NETWORK_LOST, recovery.issue.value)
                    network.setStatus(FakeNetworkMonitor.ONLINE)
                    runCurrent()
                }
                assertEquals(3, f.player.calls.count { it == "prepare" })
                assertFalse(f.player.playWhenReady)
                f.player.calls.clear()
                f.player.play()
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                f.player.pause()
                network.setStatus(FakeNetworkMonitor.OFFLINE)
                runCurrent()
                network.setStatus(FakeNetworkMonitor.ONLINE)
                runCurrent()
                assertFalse(f.player.calls.contains("prepare"))
                f.player.play()
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                foreground = false
                network.setStatus(FakeNetworkMonitor.OFFLINE)
                runCurrent()
                network.setStatus(FakeNetworkMonitor.ONLINE)
                runCurrent()
                assertFalse(f.player.calls.contains("prepare"))
                foreground = true
                f.player.play()
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                f.fakeClock.advanceTime(600_001)
                network.setStatus(FakeNetworkMonitor.OFFLINE)
                runCurrent()
                network.setStatus(FakeNetworkMonitor.ONLINE)
                runCurrent()
                assertFalse(f.player.calls.contains("prepare"))
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun sixthConsecutiveSkipPausesAndUnknownErrorKeepsCurrent() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(8)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val recovery = recovery(f)
                recovery.attach(f.player)
                repeat(5) { offset ->
                    recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, NoMediaException()))
                    awaitAdapter { f.player.ids().firstOrNull() == ids[offset + 1] }
                }
                recovery.onPlayerError(http(404))
                assertEquals(5, f.events.size)
                assertEquals(ids[5], f.player.episodeIdOrNull())
                assertFalse(f.player.playWhenReady)
                recovery.onPlayerError(error(PlaybackException.ERROR_CODE_UNSPECIFIED))
                assertEquals(ids[5], f.player.episodeIdOrNull())
                assertEquals(PlaybackIssue.PLAYER_ERROR, recovery.issue.value)
                recovery.detach()
            } finally {
                f.close()
            }
        }

    @Test fun missingLocalFileRecoversOnceAtSamePositionAndOfflinePauses() =
        runTest {
            for ((online, allowed) in listOf(true to true, false to true, true to false)) {
                val f = AdapterFixture(this)
                try {
                    val ids = f.seed(1)
                    val id = ids.single()
                    f.local.files[id] = "file:///missing.mp3"
                    f.attach()
                    f.start(ids, 42_000)
                    awaitAdapter { f.player.ids() == ids }
                    f.resolver.resolveDataSpec(DataSpec.Builder().setUri(episodeUri(id)).build())
                    val reports = mutableListOf<Long>()
                    val status = if (online) FakeNetworkMonitor.ONLINE else FakeNetworkMonitor.OFFLINE
                    val network = FakeNetworkMonitor(status)
                    val recovery =
                        ErrorRecovery(
                            RecoveryBudget(f.clock),
                            network,
                            f.clock,
                            f.resolver,
                            f.index,
                            f.projector,
                            f.scope.backgroundScope,
                            { true },
                            f.events::add,
                            reportMissing = {
                                reports += it
                                f.local.files.remove(it)
                            },
                            recorder =
                                ch.lkmc.neutrodyne.core.testing
                                    .FakeYouTubeAvailabilityRecorder(),
                            capabilities = f.caps,
                            coreSeek = f.tracker::coreSeek,
                            allowStream = { allowed },
                        )
                    recovery.attach(f.player)
                    f.player.calls.clear()
                    recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
                    runCurrent()
                    assertEquals(listOf(id), reports)
                    assertEquals(PlaybackIssue.LOCAL_FILE_MISSING, recovery.issue.value)
                    assertEquals(if (online && allowed) 1 else 0, f.player.calls.count { it == "prepare" })
                    assertEquals(42_000L, f.player.currentPosition)
                    if (online && allowed) {
                        val reopened = f.resolver.resolveDataSpec(DataSpec.Builder().setUri(episodeUri(id)).build())
                        assertEquals("https", reopened.uri.scheme)
                    } else {
                        assertFalse(f.player.playWhenReady)
                    }
                    recovery.detach()
                } finally {
                    f.close()
                }
            }
        }

    private fun recovery(
        f: AdapterFixture,
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        recorder: YouTubeAvailabilityRecorder = FakeYouTubeAvailabilityRecorder(),
        foreground: () -> Boolean = { true },
    ) = ErrorRecovery(
        RecoveryBudget(f.clock),
        network,
        f.clock,
        f.resolver,
        f.index,
        f.projector,
        f.scope.backgroundScope,
        foreground,
        f.events::add,
        recorder = recorder,
        capabilities = f.caps,
        coreSeek = f.tracker::coreSeek,
    )

    private fun error(
        code: Int,
        cause: Throwable? = null,
    ) = PlaybackException("test", cause, code)

    private fun http(status: Int) =
        error(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            HttpDataSource.InvalidResponseCodeException(
                status,
                "test",
                null,
                emptyMap(),
                DataSpec.Builder().setUri("https://example.com").build(),
                byteArrayOf(),
            ),
        )
}

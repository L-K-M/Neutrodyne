// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceQueries
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeEngine
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit coverage for the YouTube transfer owner: ranges, statuses, proof, waits, rollback. */
class YouTubeTransferSourceTest {
    private class YtRig(
        total: Long = 1_000_000,
    ) : AutoCloseable {
        val inner = TransferRig()
        val caps =
            FakeYouTubeCapabilitiesSource().apply {
                capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
            }
        val control = FakeYouTubeEngine()
        val health = FakeYouTubeHealth()
        val streams = FakeYouTubeStreamResolver()
        val pacer = YouTubeAutoPacer(inner.clock)
        var audio = audio(inner.server.url("/media").toString(), total)
        var source = ytSourceRow()
        val transfer =
            YouTubeTransferSource(
                inner.clients,
                object : DownloadSourceQueries {
                    override suspend fun downloadSources(ids: List<Long>) = listOf(source)
                },
                FakeSettingsRepository(),
                { "en" },
                streams,
                control,
                caps,
                health,
                pacer,
                inner.roots,
                inner.files,
                inner.verification,
                inner.clock,
                jitterMs = { 0 },
            )

        init {
            streams.resolve = { _, _, _ -> ResolveResult.Ok(audio) }
        }

        val row = ytRow()

        suspend fun part() = inner.files.openPart("old", DownloadPaths.part(row.episodeId))

        suspend fun <T> withPart(block: suspend (PartFile) -> T): T = part().withOpen(block)

        suspend fun go(): Pair<TransferPlan, TransferAttempt> {
            val attempt = TransferAttempt()
            val prepared = withPart { transfer.prepare(row, it, attempt) }
            assertIs<Prepared.Go>(prepared)
            return prepared.plan to attempt
        }

        override fun close() {
            inner.close()
        }
    }

    private class Ranges(
        val bytes: ByteArray,
    ) : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val range = checkNotNull(request.headers["Range"])
            val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
            val body = bytes.copyOfRange(start.toInt(), (end + 1).toInt())
            return MockResponse
                .Builder()
                .code(206)
                .setHeader("Content-Type", "audio/mp4")
                .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                .setHeader("Content-Length", body.size.toString())
                .body(Buffer().write(body))
                .build()
        }
    }

    @Test fun exactRangeChunksStreamAndValidate() =
        runTest {
            YtRig(11L * 1024 * 1024 + 7).use { rig ->
                val bytes = audioBytes(rig.audio.contentLength!!.toInt())
                val dispatcher = Ranges(bytes)
                rig.inner.server.dispatcher = dispatcher
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Complete>(result)
                    assertEquals(bytes.size.toLong(), part.length())
                }
                assertEquals(
                    listOf("bytes=0-10485759", "bytes=10485760-11534342"),
                    dispatcher.requests.map { it.headers["Range"] },
                )
                assertEquals(3, rig.streams.requests.size)
            }
        }

    @Test fun ignoredNonzeroRangeIsTransientNotSpliced() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = audioBytes(1_000_000)
                val (plan, attempt) = rig.go()
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            response(bytes, 200, "Content-Type" to "audio/mp4")
                    }
                rig.withPart { part ->
                    part.append(bytes, 400_000)
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Transient>(result)
                    assertEquals(DownloadError.NETWORK_IO, result.error)
                    assertEquals(400_000, part.length())
                }
            }
        }

    @Test fun mismatchedContentRangeStartIsTransient() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = audioBytes(1_000_000)
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            response(
                                bytes,
                                206,
                                "Content-Type" to "audio/mp4",
                                "Content-Range" to "bytes 1-1000000/1000000",
                            )
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Transient>(result)
                    assertEquals(0, part.length())
                }
            }
        }

    @Test fun fresh403InvalidatesTwiceThenForbids() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = response(code = 403)
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Transient>(result)
                    assertEquals(DownloadError.YT_FORBIDDEN, result.error)
                    assertEquals(403, result.httpStatus)
                    assertEquals(0, part.length())
                }
                assertEquals(listOf(VIDEO, VIDEO), rig.streams.invalidated)
                assertTrue(
                    rig.health.reports.any {
                        it is ExtractionOutcome.ForbiddenFreshUrl && it.videoId == VIDEO
                    },
                )
            }
        }

    @Test fun rateLimitedReportsOnceAndWaits() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = response(code = 429)
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Wait>(result)
                    assertEquals(WaitReason.BACKOFF, result.reason)
                    assertEquals(DownloadError.HTTP_RATE_LIMITED, result.error)
                    assertEquals(429, result.httpStatus)
                }
                assertTrue(rig.health.reports.any { it == ("rate" to rig.inner.clock.now()) })
            }
        }

    @Test fun unknownLengthRequiresFiniteContentRange() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.audio = rig.audio.copy(contentLength = null)
                val bytes = audioBytes(700_000)
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse
                                .Builder()
                                .code(206)
                                .setHeader("Content-Type", "audio/mp4")
                                .setHeader("Content-Range", "bytes 0-${bytes.size - 1}/*")
                                .body(Buffer().write(bytes))
                                .build()
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Transient>(result)
                    assertEquals(0, part.length())
                }
            }
        }

    @Test fun changedRepresentationResetsPartialToZero() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.withPart { part -> part.append(audioBytes(400_000), 400_000) }
                val attempt = TransferAttempt()
                val prepared = rig.withPart { rig.transfer.prepare(rig.row, it, attempt) }
                assertIs<Prepared.Go>(prepared)
                assertEquals(0, prepared.plan.offset)
            }
        }

    @Test fun matchingProofKeepsPartialOffset() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = audioBytes(400_000)
                rig.withPart { part -> part.append(bytes, bytes.size) }
                val row =
                    rig.row.copy(
                        resolvedItag = 140,
                        totalBytes = 1_000_000,
                        lastModified = rig.audio.lastModifiedMicros.toString(),
                    )
                val prepared = rig.withPart { rig.transfer.prepare(row, it, TransferAttempt()) }
                assertIs<Prepared.Go>(prepared)
                assertEquals(bytes.size.toLong(), prepared.plan.offset)
            }
        }

    @Test fun missingLastModifiedProofResetsPartial() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.withPart { part -> part.append(audioBytes(400_000), 400_000) }
                val row = rig.row.copy(resolvedItag = 140, totalBytes = 1_000_000)
                val prepared = rig.withPart { rig.transfer.prepare(row, it, TransferAttempt()) }
                assertIs<Prepared.Go>(prepared)
                assertEquals(0, prepared.plan.offset)
            }
        }

    @Test fun processDeathTrimsTornTailOnly() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.withPart { part -> part.append(audioBytes(300_000), 300_000) }
                val row =
                    rig.row.copy(
                        resolvedItag = 140,
                        totalBytes = 1_000_000,
                        lastModified = rig.audio.lastModifiedMicros.toString(),
                        lastStopReason = DownloadStopReasons.PROCESS_DEATH,
                    )
                val prepared = rig.withPart { rig.transfer.prepare(row, it, TransferAttempt()) }
                assertIs<Prepared.Go>(prepared)
                assertEquals(300_000L - 64 * 1024, prepared.plan.offset)
            }
        }

    @Test fun engineOffWaitsWithoutResolving() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.caps.capabilities.value =
                    YouTubeCapabilities(true, false, false, true, false, ExternalReason.DISABLED_BY_USER)
                val prepared = rig.withPart { rig.transfer.prepare(rig.row, it, TransferAttempt()) }
                assertIs<Prepared.Wait>(prepared)
                assertEquals(WaitReason.YOUTUBE_ENGINE_OFF, prepared.reason)
                assertTrue(rig.streams.requests.isEmpty())
            }
        }

    @Test fun absentEngineFailsAsUnsupported() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.caps.capabilities.value =
                    YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_IN_THIS_APK)
                val prepared = rig.withPart { rig.transfer.prepare(rig.row, it, TransferAttempt()) }
                assertIs<Prepared.Fail>(prepared)
                assertEquals(DownloadError.UNSUPPORTED_STREAM, prepared.error)
            }
        }

    @Test fun extractionGateDenyWaitsBounded() =
        runTest {
            YtRig(1_000_000).use { rig ->
                rig.health.gate =
                    ExtractionGate.Deny(rig.inner.clock.now() + 90_000_000, TransientKind.RATE_LIMITED)
                val prepared = rig.withPart { rig.transfer.prepare(rig.row, it, TransferAttempt()) }
                assertIs<Prepared.Wait>(prepared)
                assertEquals(WaitReason.BACKOFF, prepared.reason)
                assertEquals(rig.inner.clock.now() + 30 * 60 * 1000, prepared.untilMs)
            }
        }

    @Test fun exhaustedAutoPacerDefersAdmission() =
        runTest {
            YtRig(1_000_000).use { rig ->
                repeat(20) { assertNull(rig.pacer.admit()) }
                val row = rig.row.copy(lane = DownloadLane.AUTO)
                val prepared = rig.withPart { rig.transfer.prepare(row, it, TransferAttempt()) }
                assertIs<Prepared.Wait>(prepared)
                assertEquals(WaitReason.BACKOFF, prepared.reason)
                assertTrue(rig.streams.requests.isEmpty())
            }
        }

    @Test fun wrongMimeIsPermanentNotMedia() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = audioBytes(1_000_000)
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            response(
                                bytes,
                                206,
                                "Content-Type" to "video/mp4",
                                "Content-Range" to "bytes 0-999999/1000000",
                            )
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Permanent>(result)
                    assertEquals(DownloadError.NOT_MEDIA, result.error)
                    assertEquals(0, part.length())
                }
            }
        }

    @Test fun nonMediaSniffIsPermanentAndRolledBack() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = ByteArray(1_000_000) { 0x42 }
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            response(
                                bytes,
                                206,
                                "Content-Type" to "audio/mp4",
                                "Content-Range" to "bytes 0-999999/1000000",
                            )
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    val result = rig.transfer.transfer(plan, part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Permanent>(result)
                    assertEquals(DownloadError.NOT_MEDIA, result.error)
                    assertEquals(0, part.length())
                }
            }
        }

    @Test fun shortBodyRollsBackToChunkStart() =
        runTest {
            YtRig(1_000_000).use { rig ->
                val bytes = audioBytes(500_000)
                rig.inner.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            response(
                                bytes,
                                206,
                                "Content-Type" to "audio/mp4",
                                "Content-Range" to "bytes 0-999999/1000000",
                                "Content-Length" to "1000000",
                            )
                    }
                val (plan, attempt) = rig.go()
                rig.withPart { part ->
                    part.append(audioBytes(100_000), 100_000)
                    val result =
                        rig.transfer.transfer(plan.copy(offset = 100_000), part, rig.inner.sink, attempt)
                    assertIs<TransferResult.Transient>(result)
                    assertEquals(100_000, part.length())
                }
            }
        }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaA"
        const val VIDEO = "ytvideo0001"

        fun audioBytes(size: Int): ByteArray =
            ByteArray(size) { ((it * 31) % 251).toByte() }.also {
                ByteArray(12)
                    .also { head ->
                        "size".encodeToByteArray().copyInto(head)
                        "ftypM4A ".encodeToByteArray().copyInto(head, 4)
                    }.copyInto(it)
            }

        fun audio(
            url: String,
            total: Long?,
        ) = ResolvedAudio(
            VIDEO,
            url,
            140,
            "140",
            "audio/mp4",
            "mp4a.40.2",
            128_000,
            total,
            600_000,
            Long.MAX_VALUE,
            1_700_000_000_000_000,
            false,
            null,
            null,
            null,
            null,
            1_000_000,
        )

        fun ytSourceRow() =
            DownloadSourceRow(
                1,
                2,
                "Video",
                "Channel",
                0,
                null,
                false,
                Availability.AVAILABLE,
                SourceType.YOUTUBE_CHANNEL,
                null,
                null,
                null,
                VIDEO,
                emptyList(),
                CHANNEL,
            )

        fun ytRow() =
            DownloadEntity(
                1,
                DownloadLane.MANUAL,
                DownloadState.RESOLVING,
                priority = 100,
                requestedAt = 0,
                sourceKind = SourceKind.YOUTUBE,
                sourceRef = VIDEO,
                rootId = "old",
                allowMetered = true,
            )
    }
}

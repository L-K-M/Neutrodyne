// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.youtube.DefaultYouTubeHealth
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeSourceAdapter
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Interceptor
import okio.FileSystem
import org.junit.Rule
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real FEED client, platform parser, ingest transactions and bundled Room driver; no live traffic. */
class YouTubeSourceAdapterTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    @Test fun variantsAreSequentialAndMapCanonicalExternalRows() =
        runTest {
            fixture().use { f ->
                val id = f.channel(7, initial = true)
                val active = AtomicInteger()
                val peak = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val count = active.incrementAndGet()
                            peak.accumulateAndGet(count, ::maxOf)
                            Thread.sleep(15)
                            active.decrementAndGet()
                            val short = request.url.queryParameter("playlist_id")!!.startsWith("UUSH")
                            return mockResponse(body = atom(listOf(video(if (short) 2 else 1))))
                        }
                    }
                val result = f.parsed(id)
                assertEquals(1, peak.get())
                assertEquals(3, server.requestCount)
                val queries =
                    (1..3).map {
                        val request = server.takeRequest()
                        assertNull(request.headers["If-None-Match"])
                        assertNull(request.headers["If-Modified-Since"])
                        assertEquals(
                            "application/atom+xml, application/xml;q=0.9, */*;q=0.1",
                            request.headers["Accept"],
                        )
                        assertTrue(request.headers["User-Agent"]!!.contains("Neutrodyne"))
                        request.url.queryParameter("playlist_id")!!.take(4)
                    }
                assertEquals(listOf("UULF", "UUSH", "UULV"), queries)
                assertEquals("www.youtube.com", f.adapter.hostKey(dueFeedOf(f.db, id), "ignored"))
                assertEquals("Channel & Team", result.feed.title)
                assertEquals("Channel & Team", result.feed.author)
                assertEquals(2, result.feed.items.size)
                val ingest = f.ingest(id, result, IngestMode.INITIAL)
                assertTrue(ingest.newIds.isEmpty())
                for (item in result.feed.items) {
                    val row = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${item.externalMediaId}")!!
                    assertEquals("yt:video:${item.externalMediaId}", row.guid)
                    assertNull(row.enclosureUrl)
                    assertNull(row.enclosureType)
                    assertNull(row.enclosureLength)
                    assertFalse(row.isVideo)
                    assertFalse(row.isNew)
                    assertEquals("https://www.youtube.com/watch?v=${row.externalMediaId}", row.link)
                    assertEquals("https://i.ytimg.com/vi/${row.externalMediaId}/mqdefault.jpg", row.imageUrl)
                }
                assertTrue(
                    f.db
                        .downloadDao()
                        .downloadedEpisodeIds(listOf(id))
                        .isEmpty(),
                )
                assertEquals(1, f.monitor.reports.size)
                assertEquals(
                    ch.lkmc.neutrodyne.youtube.api.FeedOutcome.SUCCESS,
                    f.monitor.reports
                        .single()
                        .second,
                )
                assertEquals(0, f.tempBodyCount())
            }
        }

    @Test fun viewsAndUpdatedAloneAreUnchangedButAuthorRenameReingests() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                val xml = atom(listOf(video(1)))
                server.enqueue(mockResponse(body = xml))
                val first = f.parsed(id)
                f.ingest(id, first)
                val before = f.db.ingestDao().existing(id)
                server.enqueue(
                    mockResponse(
                        body =
                            xml
                                .replace("views=\"1\"", "views=\"9999\"")
                                .replace("2026-10-19T00:00:00Z", "2026-10-19T04:00:00Z"),
                    ),
                )
                assertIs<AdapterResult.Unchanged>(f.fetch(id))
                assertEquals(before, f.db.ingestDao().existing(id))
                server.enqueue(mockResponse(body = xml.replace("Channel &amp; Team", "Renamed")))
                val renamed = f.parsed(id)
                assertNotEquals(first.meta.sha256Hex, renamed.meta.sha256Hex)
                f.ingest(id, renamed)
                assertEquals(
                    "Renamed",
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .title,
                )
                assertEquals(
                    "Renamed",
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .author,
                )
            }
        }

    @Test fun missingAndMalformedPublishedNeverUseUpdatedInYouTubeRows() =
        runTest {
            fixture().use { f ->
                val id = f.channel(7)
                val xml = requireNotNull(javaClass.getResource("/youtube/m8-atom.xml")).readText()
                repeat(3) { server.enqueue(mockResponse(body = xml)) }
                val result = f.parsed(id)
                f.ingest(id, result)
                val missing = f.db.episodeDao().byIdentityKey(id, "g:yt:video:bbbbbbbbbbb")!!
                val malformed = f.db.episodeDao().byIdentityKey(id, "g:yt:video:ccccccccccc")!!
                assertNull(missing.pubDate)
                assertNull(missing.rawPubDate)
                assertEquals(NOW, missing.sortDate)
                assertNull(malformed.pubDate)
                assertEquals("not-a-date", malformed.rawPubDate)
                assertEquals(NOW, malformed.sortDate)
                repeat(
                    3,
                ) { server.enqueue(mockResponse(body = xml.replace("2026-10-19T12:00:00Z", "2026-10-19T14:00:00Z"))) }
                assertIs<AdapterResult.Unchanged>(f.fetch(id))
            }
        }

    @Test fun primaryMissingFallsBackForEveryMaskAndPreservesFailureDiagnostic() =
        runTest {
            fixture().use { f ->
                for (mask in 1..7) {
                    val id = f.channel(mask, suffix = mask)
                    server.enqueue(mockResponse(404))
                    server.enqueue(mockResponse(body = atom(listOf(video(1, short = true), video(2)))))
                    repeat(Integer.bitCount(mask) - 1) { server.enqueue(mockResponse(404)) }
                    val result = f.parsed(id)
                    assertEquals(if (mask and 2 != 0) 2 else 1, result.feed.items.size)
                    val primary = server.takeRequest()
                    assertTrue(primary.url.queryParameter("playlist_id") != null)
                    assertEquals(
                        CHANNEL_PREFIX + mask.toString().padStart(2, '0') + "A",
                        server.takeRequest().url.queryParameter("channel_id"),
                    )
                    repeat(Integer.bitCount(mask) - 1) { server.takeRequest() }
                }
                val id = f.channel()
                server.enqueue(mockResponse(410))
                server.enqueue(mockResponse(503))
                val failure = assertIs<AdapterResult.Failed>(f.fetch(id))
                assertEquals(503, failure.http)
                assertEquals(FeedErrorKind.HTTP_SERVER, failure.kind)
                assertTrue(failure.transient)
            }
        }

    @Test fun missingSecondaryIsEmptyButOtherFailuresMarkNoAbsences() =
        runTest {
            fixture().use { f ->
                val id = f.channel(3)
                server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                server.enqueue(mockResponse(410))
                assertEquals(Long.MIN_VALUE, f.parsed(id).absenceFloor)
                server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                server.enqueue(mockResponse(503))
                assertEquals(Long.MAX_VALUE, f.parsed(id).absenceFloor)
                assertTrue(f.monitor.reports.all { it.second == ch.lkmc.neutrodyne.youtube.api.FeedOutcome.SUCCESS })
            }
        }

    @Test fun anyRateLimitDefersCurrentAndSubsequentChannelsWithoutFailure() =
        runTest {
            fixture().use { f ->
                for (code in listOf(403, 429)) {
                    f.health.reportFeedRecovered()
                    f.health.awaitLoaded()
                    val id = f.channel(3, suffix = code % 100)
                    server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                    server.enqueue(mockResponse(code, "", "Retry-After" to "7200"))
                    val result = assertIs<AdapterResult.Deferred>(f.fetch(id))
                    assertEquals(NOW + 7_200_000, result.untilMs)
                    assertEquals(
                        ch.lkmc.neutrodyne.youtube.api.FeedOutcome.INCONCLUSIVE,
                        f.monitor.reports
                            .last()
                            .second,
                    )
                    f.monitor.gate = { _, _ -> FeedGate.Deferred(result.untilMs) }
                    val count = server.requestCount
                    assertEquals(result, f.fetch(f.channel(suffix = 50 + code % 100)))
                    assertEquals(count, server.requestCount)
                    f.monitor.gate = { _, _ -> FeedGate.Fetch }
                }
            }
        }

    @Test fun twoMegabyteWireCapAndMalformedAtomLeaveNoTempFiles() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                server.enqueue(
                    mockResponse(
                        body =
                            "<feed xmlns='http://www.w3.org/2005/Atom'>" + " ".repeat(2 * 1024 * 1024) + "</feed>",
                    ),
                )
                assertEquals(FeedErrorKind.TOO_LARGE, assertIs<AdapterResult.Failed>(f.fetch(id)).kind)
                server.enqueue(
                    mockResponse(body = "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///missing'>]><feed>&x;</feed>"),
                )
                assertEquals(FeedErrorKind.PARSE_ERROR, assertIs<AdapterResult.Failed>(f.fetch(id)).kind)
                server.enqueue(mockResponse(body = atom(listOf("<entry><title>No video identity</title></entry>"))))
                assertEquals(FeedErrorKind.PARSE_ERROR, assertIs<AdapterResult.Failed>(f.fetch(id)).kind)
                assertEquals(0, f.tempBodyCount())
            }
        }

    @Test fun shortEmptyAndFallbackFilteredEmptyActivateWithoutErasingHistory() =
        runTest {
            fixture().use { f ->
                for (fallback in listOf(false, true)) {
                    val id = f.channel(initial = true, suffix = if (fallback) 3 else 4)
                    server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                    f.ingest(id, f.parsed(id), IngestMode.INITIAL)
                    f.db.useWriterConnection {
                        it.execSql("UPDATE podcast SET initialFetch = 1, status = 'PENDING_FIRST_FETCH' WHERE id = $id")
                    }
                    if (fallback) server.enqueue(mockResponse(404))
                    server.enqueue(
                        mockResponse(body = atom(if (fallback) listOf(video(2, short = true)) else emptyList())),
                    )
                    val result = f.ingest(id, f.parsed(id), IngestMode.INITIAL)
                    assertTrue(result.inserted.isEmpty())
                    assertTrue(result.newIds.isEmpty())
                    assertEquals(
                        PodcastStatus.ACTIVE,
                        f.db
                            .podcastDao()
                            .byId(id)!!
                            .status,
                    )
                    assertFalse(
                        f.db
                            .podcastDao()
                            .byId(id)!!
                            .initialFetch,
                    )
                    assertTrue(
                        f.db
                            .episodeDao()
                            .byIdentityKey(id, "g:yt:video:${vid(1)}")!!
                            .inFeed,
                    )
                }
            }
        }

    @Test fun stickyShortsAndEnrichedFieldsSurviveVariantAndTitleEdits() =
        runTest {
            fixture().use { f ->
                val id = f.channel(3)
                server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                server.enqueue(mockResponse(body = atom(emptyList())))
                val first = f.parsed(id)
                f.ingest(id, first)
                val row = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${vid(1)}")!!
                f.db.useWriterConnection {
                    it.execSql(
                        "UPDATE episode SET durationMs = 90000, availability = 'UNAVAILABLE' WHERE id = ${row.id}",
                    )
                }
                server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                server.enqueue(mockResponse(body = atom(listOf(video(1)))))
                val reclassified = f.parsed(id)
                assertNotEquals(first.meta.sha256Hex, reclassified.meta.sha256Hex)
                assertEquals(1, f.ingest(id, reclassified).updated)
                assertTrue(
                    f.db
                        .episodeDao()
                        .byId(row.id)!!
                        .isShort,
                )
                server.enqueue(mockResponse(body = atom(listOf(video(1, title = "Edited")))))
                server.enqueue(mockResponse(body = atom(emptyList())))
                f.ingest(id, f.parsed(id))
                val changed = f.db.episodeDao().byId(row.id)!!
                assertEquals("Edited", changed.title)
                assertEquals(90_000L, changed.durationMs)
                assertEquals(ch.lkmc.neutrodyne.core.model.Availability.UNAVAILABLE, changed.availability)
                assertTrue(changed.isShort)
            }
        }

    @Test fun rawFloorsProtectScrolledOutRowsAndUserStateForTenRefreshes() =
        runTest {
            fixture().use { f ->
                val id = f.channel(3)
                val old =
                    listOf(
                        video(1, date = NOW - 100 * DAY),
                        video(2, date = NOW - DAY),
                        video(
                            3,
                            date =
                                NOW - 500 * DAY,
                        ),
                    )
                server.enqueue(mockResponse(body = atom(old)))
                server.enqueue(mockResponse(body = atom(emptyList())))
                f.ingest(id, f.parsed(id))
                val row = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${vid(1)}")!!
                f.db.episodeStateDao().upsert(
                    ch.lkmc.neutrodyne.core.testing.database
                        .episodeStateEntity(
                            row.id,
                            playedAt = NOW,
                        ).copy(isFavorite = true),
                )
                f.db.useWriterConnection {
                    it.execSql(
                        "INSERT INTO episode_position(episodeId, positionMs, positionSource, updatedAt) VALUES (${row.id}, 2345, 'STREAM', $NOW)",
                    )
                }
                repeat(10) { refresh ->
                    server.enqueue(
                        mockResponse(
                            body =
                                atom(
                                    (10..24).map {
                                        video(
                                            it + refresh * 30,
                                            date =
                                                NOW - 365 * DAY + it * 1000,
                                        )
                                    },
                                ),
                        ),
                    )
                    server.enqueue(
                        mockResponse(
                            body =
                                atom(
                                    (1000..1014).map {
                                        video(
                                            it + refresh * 30,
                                            date =
                                                NOW - DAY / 2 + it * 1000,
                                        )
                                    },
                                ),
                        ),
                    )
                    val parsed = f.parsed(id)
                    assertTrue(parsed.absenceFloor!! > NOW - DAY)
                    f.ingest(id, parsed)
                }
                assertTrue(
                    f.db
                        .episodeDao()
                        .byId(row.id)!!
                        .inFeed,
                )
                assertTrue(
                    f.db
                        .episodeDao()
                        .byIdentityKey(id, "g:yt:video:${vid(3)}")!!
                        .inFeed,
                )
                f.db.useReaderConnection { connection ->
                    connection.usePrepared(
                        "SELECT p.positionMs, s.playedAt, s.isFavorite FROM episode_state s JOIN episode_position p USING(episodeId) WHERE s.episodeId = ${row.id}",
                    ) {
                        assertTrue(it.step())
                        assertEquals(2345L, it.getLong(0))
                        assertEquals(NOW, it.getLong(1))
                        assertEquals(1L, it.getLong(2))
                    }
                }
            }
        }

    @Test fun insideWindowAbsencesFlipButFailedSecondaryPreservesThem() =
        runTest {
            fixture().use { f ->
                val id = f.channel(3)
                server.enqueue(mockResponse(body = atom(listOf(video(1, date = NOW)))))
                server.enqueue(mockResponse(body = atom(emptyList())))
                f.ingest(id, f.parsed(id))
                val row = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${vid(1)}")!!
                server.enqueue(mockResponse(body = atom(listOf(video(2)))))
                server.enqueue(mockResponse(500))
                f.ingest(id, f.parsed(id))
                assertTrue(
                    f.db
                        .episodeDao()
                        .byId(row.id)!!
                        .inFeed,
                )
                server.enqueue(mockResponse(body = atom(listOf(video(3)))))
                server.enqueue(mockResponse(body = atom(emptyList())))
                f.ingest(id, f.parsed(id))
                assertFalse(
                    f.db
                        .episodeDao()
                        .byId(row.id)!!
                        .inFeed,
                )
            }
        }

    @Test fun engineCallsPostHookOnUnchangedAndPersistsBoundedGapSchedule() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                val settings = FakeSettingsRepository()
                settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 60)
                val refresher =
                    newRefresher(f.db, mapOf(SourceType.YOUTUBE_CHANNEL to f.adapter), f.clock, settings = settings)
                val xml = atom((1..15).map { video(it) })
                server.enqueue(mockResponse(body = xml))
                assertIs<FeedOutcome.Ingested>(refresher.run(request(id)).outcomes[id])
                assertEquals(
                    NOW + 30 * 60_000,
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .nextRefreshAt,
                )
                repeat(3) { index ->
                    server.enqueue(mockResponse(body = xml))
                    assertIs<FeedOutcome.Unchanged>(refresher.run(request(id)).outcomes[id])
                    val minutes = if (index < 2) 30 else 60
                    assertEquals(
                        NOW + minutes * 60_000L,
                        f.db
                            .podcastDao()
                            .byId(id)!!
                            .nextRefreshAt,
                    )
                }
                assertEquals(4, f.metadata.calls.size)
                assertTrue(f.metadata.calls.all { it == id to ChannelMetadataReason.AFTER_INGEST })
            }
        }

    @Test fun successFloorServerHintsNeverAndFailuresKeepTheirDeadlines() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                val feed = dueFeedOf(f.db, id)
                server.enqueue(
                    mockResponse(
                        body = atom((1..15).map { video(it) }),
                        headers =
                            arrayOf("Cache-Control" to "max-age=3600"),
                    ),
                )
                val parsed = f.parsed(id)
                assertEquals(NOW + 60 * 60_000L, f.adapter.nextRefreshAt(feed, parsed, NOW + 60 * 60_000L))
                assertEquals(
                    NOW + 15 * 60_000L,
                    f.adapter.nextRefreshAt(
                        feed,
                        parsed.copy(meta = parsed.meta.copy(maxAgeSec = 0)),
                        NOW + 60_000,
                    ),
                )
                assertEquals(RefreshPolicy.NEVER, f.adapter.nextRefreshAt(feed, parsed, RefreshPolicy.NEVER))
                // Manual-only successes consume the bounded gap budget without scheduling a poll.
                assertEquals(
                    NOW + 60 * 60_000L,
                    f.adapter.nextRefreshAt(
                        feed,
                        parsed.copy(meta = parsed.meta.copy(maxAgeSec = 0)),
                        NOW + 60 * 60_000L,
                    ),
                )
                val failed = AdapterResult.Failed(FeedErrorKind.HTTP_SERVER, 503, 8_000_000, true)
                assertEquals(NOW + 8_000_000, f.adapter.nextRefreshAt(feed, failed, NOW + 8_000_000))
                assertEquals(NOW + 123, f.adapter.nextRefreshAt(feed, AdapterResult.Deferred(NOW + 123), NOW))
                // Two manual refreshes can fetch inside the scheduling floor.
                server.enqueue(mockResponse(body = atom(emptyList())))
                assertIs<AdapterResult.Parsed>(f.fetch(id))
            }
        }

    @Test fun postHookFiltersHiddenAndUnavailableIdsAndCancellationStillAnnouncesCommittedRows() =
        runTest {
            fixture().use { f ->
                val id = f.channel(3)
                server.enqueue(mockResponse(body = atom(listOf(video(1), video(2), video(3)))))
                server.enqueue(mockResponse(body = atom(listOf(video(2)))))
                f.ingest(id, f.parsed(id))
                val rows = f.db.ingestDao().existing(id)
                val hidden = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${vid(2)}")!!
                val upcoming = f.db.episodeDao().byIdentityKey(id, "g:yt:video:${vid(3)}")!!
                f.db.useWriterConnection {
                    it.execSql("UPDATE podcast SET youtubeVariants = 1 WHERE id = $id")
                    it.execSql("UPDATE episode SET availability = 'UPCOMING' WHERE id = ${upcoming.id}")
                }
                val announce = f.adapter.afterIngest(id, emptyList(), rows.map { it.id })
                assertFalse(hidden.id in announce)
                assertFalse(upcoming.id in announce)
                assertEquals(1, announce.size)
                f.metadata.refresh = { _, _ -> throw CancellationException("metadata timeout") }
                server.enqueue(mockResponse(body = atom(listOf(video(4)))))
                val report =
                    newRefresher(
                        f.db,
                        mapOf(SourceType.YOUTUBE_CHANNEL to f.adapter),
                        f.clock,
                    ).run(request(id))
                assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                assertEquals(
                    1,
                    report.newEpisodes
                        .single()
                        .episodeIds.size,
                )
            }
        }

    @Test fun cancelledAdmittedFetchReportsInconclusiveAndReleasesBody() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(atom(listOf(video(1))))
                        .headersDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build(),
                )
                val job = async { f.fetch(id) }
                kotlinx.coroutines.withContext(Dispatchers.IO) { server.takeRequest() }
                job.cancel()
                job.join()
                assertEquals(1, server.requestCount)
                assertEquals(
                    ch.lkmc.neutrodyne.youtube.api.FeedOutcome.INCONCLUSIVE,
                    f.monitor.reports
                        .single()
                        .second,
                )
                assertEquals(0, f.tempBodyCount())
            }
        }

    @Test fun unchangedResponsePromotesNewPremiereButInitialAndQuietChannelsStaySilent() =
        runTest {
            fixture().use { f ->
                val id = f.channel()
                val refresher = newRefresher(f.db, mapOf(SourceType.YOUTUBE_CHANNEL to f.adapter), f.clock)
                val xml = atom(listOf(video(1)))
                f.engine.enrich = { _, ids, _ ->
                    ch.lkmc.neutrodyne.youtube.api.EnrichResult.Ok(
                        ids.map {
                            fact(it, availability = ch.lkmc.neutrodyne.core.model.Availability.UPCOMING)
                        },
                    )
                }
                server.enqueue(mockResponse(body = xml))
                assertTrue(refresher.run(request(id)).newEpisodes.isEmpty())
                val row = f.db.episodeDao().youTubeByVideoId(id, vid(1))!!
                val before = row.copy(availability = ch.lkmc.neutrodyne.core.model.Availability.AVAILABLE)
                f.engine.enrich = { _, ids, _ ->
                    ch.lkmc.neutrodyne.youtube.api.EnrichResult.Ok(
                        ids.map {
                            fact(it, availability = ch.lkmc.neutrodyne.core.model.Availability.AVAILABLE)
                        },
                    )
                }
                server.enqueue(mockResponse(body = xml))
                val promoted = refresher.run(request(id))
                assertIs<FeedOutcome.Unchanged>(promoted.outcomes[id])
                assertEquals(listOf(row.id), promoted.newEpisodes.single().episodeIds)
                assertEquals(before, f.db.episodeDao().byId(row.id))
                // Refreshing an already available row must not promote it again.
                server.enqueue(mockResponse(body = xml))
                assertTrue(refresher.run(request(id)).newEpisodes.isEmpty())
                f.db.useWriterConnection {
                    it.execSql(
                        "UPDATE episode SET availability = 'UPCOMING' WHERE id = ${row.id}",
                    )
                }
                server.enqueue(mockResponse(body = xml))
                assertTrue(refresher.run(request(id).copy(origin = RefreshOrigin.RESTORE)).newEpisodes.isEmpty())
                val initial = f.channel(initial = true, suffix = 7)
                server.enqueue(mockResponse(body = xml))
                val first = refresher.run(request(initial))
                assertTrue(first.newEpisodes.all { it.initialFetch })
                f.db.useWriterConnection {
                    it.execSql(
                        "UPDATE episode SET availability = 'UPCOMING' WHERE podcastId = $initial",
                    )
                }
                server.enqueue(mockResponse(body = xml))
                assertTrue(refresher.run(request(initial)).newEpisodes.isEmpty())
            }
        }

    private suspend fun TestScope.fixture(): Fixture = Fixture(this)

    private inner class Fixture(
        scope: TestScope,
    ) : AutoCloseable {
        val clock = TestClock(NOW)
        val db = newDb(clock, kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler))
        val root =
            kotlin.io.path
                .createTempDirectory("nd-youtube-adapter")
                .toFile()
        val monitor = FakeYouTubeFeedOutageMonitor()
        val metadata = FakeYouTubeChannelMetadata()
        val engine = ControlledEnricher(clock)
        val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, scope.backgroundScope) {}
        private val clients =
            NetworkClients(
                CoreClients(
                    UserAgentInterceptor(testUserAgent()),
                    LocalNetworkGuard(LocalNetworkAccess(), fakePlatform()),
                    DnsFamilyHints(),
                    setOf(
                        Interceptor { chain ->
                            val url = chain.request().url
                            check(url.host == "www.youtube.com")
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .url(server.url(url.encodedPath + "?" + url.encodedQuery))
                                    .build(),
                            )
                        },
                    ),
                ),
                AuthInterceptor { CredentialLookup.None },
            )
        private val transport = newFetcher(root, clock = clock, networkClients = clients)
        val adapter =
            YouTubeSourceAdapter(
                transport.fetcher,
                XmlPullFeedParser.discovered(),
                transport.tempFiles,
                FileSystem.SYSTEM,
                db,
                health,
                monitor,
                metadata,
                clock,
                ch.lkmc.neutrodyne.core.data.youtube.YouTubeEnrichment(
                    db,
                    engine,
                    ch.lkmc.neutrodyne.core.data.youtube.YouTubeFactsWriterImpl(
                        db,
                        ch.lkmc.neutrodyne.feeds.jvm.html
                            .JsoupShowNotesSanitizer(),
                    ),
                    clock,
                    kotlin.random.Random(1),
                    enabledYouTubeCapabilities(),
                ),
                Dispatchers.IO,
            )

        suspend fun channel(
            bits: Int = 1,
            initial: Boolean = false,
            suffix: Int = 0,
        ): Long {
            val channel = CHANNEL_PREFIX + suffix.toString().padStart(2, '0') + "A"
            return seedPodcast(
                db,
                YouTubeFeedUrls.canonical(channel),
                initialFetch = initial,
                status = if (initial) PodcastStatus.PENDING_FIRST_FETCH else PodcastStatus.ACTIVE,
                block = {
                    copy(
                        sourceType = SourceType.YOUTUBE_CHANNEL,
                        youtubeChannelId = channel,
                        youtubeVariants = bits,
                    )
                },
            )
        }

        suspend fun fetch(id: Long) = adapter.fetchAndParse(dueFeedOf(db, id), FetchMode.REFRESH)

        suspend fun parsed(id: Long) = assertIs<AdapterResult.Parsed>(fetch(id))

        suspend fun ingest(
            id: Long,
            result: AdapterResult.Parsed,
            mode: IngestMode = IngestMode.REFRESH,
        ) = newIngestor(db, clock).ingest(
            dueFeedOf(db, id),
            result.feed,
            IngestContext(mode, result.partial, result.meta, result.rowHints, result.absenceFloor),
        )

        fun tempBodyCount() = root.resolve("cache/feeds").listFiles()?.size ?: 0

        override fun close() {
            transport.close()
            db.close()
            root.deleteRecursively()
        }
    }

    private fun request(id: Long) = RefreshRequest(RefreshScope.Podcasts(listOf(id)), true, false, RefreshOrigin.MANUAL)

    private fun atom(entries: List<String>) =
        """
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/">
          <title>Videos</title><author><name>Channel &amp; Team</name></author>${entries.joinToString("")}
        </feed>
        """.trimIndent()

    private fun video(
        number: Int,
        date: Long = NOW - DAY,
        short: Boolean = false,
        title: String = "Video $number",
    ): String {
        val id = vid(number)
        val link = if (short) "https://www.youtube.com/shorts/$id" else YouTubeFeedUrls.watch(id)
        return """<entry><id>untrusted:$id</id><yt:videoId>$id</yt:videoId><title>$title</title>
            <link rel="alternate" href="$link"/><published>${Instant.ofEpochMilli(
            date,
        )}</published><updated>2026-10-19T00:00:00Z</updated>
            <media:group><media:description>Description $number</media:description><media:community><media:statistics views="1"/></media:community></media:group></entry>"""
    }

    private fun vid(number: Int) = number.toString().padStart(11, 'a')

    private companion object {
        const val NOW = 1_792_411_200_000L
        const val DAY = 86_400_000L
        const val CHANNEL_PREFIX = "UCaaaaaaaaaaaaaaaaaaa"
    }
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeAvailabilityRecorderImpl
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeDescriptionLoader
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeEnrichment
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeFactsWriterImpl
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.VideoFacts
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bundled Room driver, real writers and codec; controlled engine data is never production-bound. */
class YouTubeFactsIntegrationTest {
    @Test fun partialFactsAreStickyAndCannotTouchAtomOrUserState() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel, availability = Availability.LIVE)
                val before = f.db.episodeDao().byId(id)!!
                val state =
                    EpisodeStateEntity(
                        id,
                        playedAt = 50,
                        playCount = 4,
                        isFavorite = true,
                        measuredDurationMs = 200,
                        updatedAt = 60,
                    )
                val position = EpisodePositionEntity(id, 20, 200, PositionSource.STREAM, 60)
                f.db.episodeStateDao().upsert(state)
                f.db.positionDao().upsert(position)
                f.db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "V", addedAt = 40))
                val queue = f.db.queueDao().entries()
                f.writer.applyFacts(
                    channel,
                    listOf(
                        fact(
                            VIDEO,
                            125_000,
                            Availability.AVAILABLE,
                            true,
                        ).copy(title = "Engine title", publishedAtMs = 1),
                    ),
                )
                val updated = f.db.episodeDao().byId(id)!!
                val expected = before.copy(durationMs = 125_000, availability = Availability.AVAILABLE, isShort = true)
                assertEquals(expected, updated)
                f.writer.applyFacts(channel, listOf(fact(VIDEO, -1, null, false)))
                f.writer.applyFacts(channel, listOf(fact(VIDEO, null, null, null)))
                assertEquals(updated, f.db.episodeDao().byId(id))
                assertEquals(state, f.db.episodeStateDao().byEpisode(id))
                assertEquals(position, f.db.positionDao().byEpisode(id))
                assertEquals(queue, f.db.queueDao().entries())
            }
        }

    @Test fun completeDescriptionsUseCodecAndNeverOverwriteExistingNotesOrSnippet() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                val text = "0:00 First\n0:10 Next\n0:20 Last <plain> " + "Notes ".repeat(150)
                f.writer.fillDescriptionIfAbsent(id, text, DescriptionCompleteness.TRUNCATED)
                assertNull(f.db.episodeDao().description(id))
                f.writer.fillDescriptionIfAbsent(id, text, DescriptionCompleteness.COMPLETE)
                assertEquals(text, EpisodeDescriptionCodec.decode(f.db.episodeDao().description(id)!!).text)
                assertTrue(
                    f.db
                        .episodeDao()
                        .byId(id)!!
                        .snippet!!
                        .length <= 200,
                )
                val written = f.db.episodeDao().byId(id)
                f.writer.fillDescriptionIfAbsent(id, "Replacement", DescriptionCompleteness.COMPLETE)
                assertEquals(text, EpisodeDescriptionCodec.decode(f.db.episodeDao().description(id)!!).text)
                assertEquals(written, f.db.episodeDao().byId(id))
            }
        }

    @Test fun failedStorageRollsBackAllFactsAndNoopFactsDoNotWrite() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                val before = f.db.episodeDao().byId(id)!!
                f.db.useWriterConnection { c ->
                    c.usePrepared(
                        "CREATE TRIGGER reject_fact BEFORE UPDATE ON episode " +
                            "BEGIN SELECT RAISE(ABORT, 'storage test'); END",
                    ) { it.step() }
                }
                f.writer.applyFacts(channel, listOf(fact(VIDEO)))
                assertFailsWith<Exception> { f.writer.applyFacts(channel, listOf(fact(VIDEO, 10, Availability.LIVE))) }
                assertEquals(before, f.db.episodeDao().byId(id))
                f.db.useWriterConnection { c -> c.usePrepared("DROP TRIGGER reject_fact") { it.step() } }
                f.writer.applyFacts(channel, listOf(fact(VIDEO, 10, Availability.LIVE)))
                assertEquals(
                    10,
                    f.db
                        .episodeDao()
                        .byId(id)!!
                        .durationMs,
                )
            }
        }

    @Test fun recorderChangesOnlyYouTubeAvailabilityAndIsIdempotent() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                val before = f.db.episodeDao().byId(id)!!
                val recorder = YouTubeAvailabilityRecorderImpl(f.db)
                recorder.record(id, Availability.REGION_BLOCKED)
                recorder.record(id, Availability.REGION_BLOCKED)
                assertEquals(before.copy(availability = Availability.REGION_BLOCKED), f.db.episodeDao().byId(id))
                val rss = seedPodcast(f.db, "https://rss.example.com/feed")
                val rssId = f.episode(rss)
                recorder.record(rssId, Availability.PRIVATE)
                assertEquals(
                    Availability.AVAILABLE,
                    f.db
                        .episodeDao()
                        .byId(rssId)!!
                        .availability,
                )
            }
        }

    @Test fun disabledEnrichmentConsumesNoBudgetOrCallsAndEnableCanRecoverImmediately() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.channel()
                f.episode(id)
                val enabled = f.capabilities.capabilities.value
                f.capabilities.capabilities.value = enabled.copy(enrichment = false)
                repeat(101) { assertTrue(f.enrichment.refresh(id).isEmpty()) }
                assertTrue(f.engine.enrichCalls.isEmpty())
                assertEquals(0, testScheduler.currentTime)
                f.capabilities.capabilities.value = enabled
                f.engine.enrich = { _, _, _ -> EnrichResult.Ok(emptyList()) }
                f.enrichment.refresh(id)
                assertEquals(1, f.engine.enrichCalls.size)
            }
        }

    @Test fun candidatesAreRecentAndExcludeKnownDurationsAndOldLive() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                f.episode(channel, VIDEO)
                f.episode(channel, OTHER, duration = 10)
                f.episode(channel, THIRD, age = 8 * DAY)
                f.episode(channel, "ddddddddddD", Availability.UPCOMING, age = 29 * DAY)
                f.episode(channel, "eeeeeeeeeeE", Availability.LIVE, age = 31 * DAY)
                assertEquals(
                    setOf(VIDEO, "ddddddddddD"),
                    f.db
                        .episodeDao()
                        .youTubeCandidates(channel, f.clock.now())
                        .map {
                            it.externalMediaId
                        }.toSet(),
                )
            }
        }

    @Test fun enrichmentPromotesOnlyNewVisibleItemsAndKeepsQuietRowsQuiet() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val fresh = f.episode(channel, VIDEO, Availability.UPCOMING, isNew = true)
                f.episode(channel, OTHER, Availability.LIVE, isNew = false)
                f.episode(channel, THIRD, Availability.LIVE, isNew = true, short = true)
                f.engine.enrich = { _, ids, _ -> EnrichResult.Ok(ids.map { fact(it, 20, Availability.AVAILABLE) }) }
                assertEquals(listOf(fresh), f.enrichment.refresh(channel))
                assertEquals(emptyList(), f.enrichment.refresh(channel))
                assertEquals(1, f.engine.enrichCalls.size)
            }
        }

    @Test fun missingLiveChecksAreLimitedToFiveAndTwentyPerWindow() =
        runTest {
            FactsFixture(this).use { f ->
                f.engine.enrich = { _, _, _ -> EnrichResult.Ok(emptyList()) }
                f.engine.facts = { VideoFactsResult.Ok(fact(it, 10, Availability.AVAILABLE)) }
                repeat(5) { i ->
                    val channel = f.channel(i)
                    repeat(
                        7,
                    ) {
                        f.episode(
                            channel,
                            "v" + (i * 10 + it).toString().padStart(10, '0'),
                            Availability.UPCOMING,
                            isNew = true,
                        )
                    }
                    f.episode(channel, "z" + i.toString().padStart(10, '0'))
                    f.enrichment.refresh(channel)
                    assertEquals(minOf((i + 1) * 5, 20), f.engine.factCalls.size)
                }
                assertTrue(f.engine.factCalls.none { it.startsWith("z") })
            }
        }

    @Test fun channelDeadlineAndNonCooperativeLateFactsCannotWrite() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                f.engine.enrich = { _, _, _ ->
                    delay(21_000)
                    EnrichResult.Ok(listOf(fact(VIDEO, 10)))
                }
                assertEquals(emptyList(), f.enrichment.refresh(channel))
                assertNull(
                    f.db
                        .episodeDao()
                        .byId(id)!!
                        .durationMs,
                )
                assertTrue(testScheduler.currentTime in 20_000..21_000)
                f.engine.enrich =
                    { _, _, _ ->
                        withContext(NonCancellable) { delay(21_000) }
                        EnrichResult.Ok(listOf(fact(VIDEO, 10)))
                    }
                assertEquals(emptyList(), f.enrichment.refresh(channel))
                assertNull(
                    f.db
                        .episodeDao()
                        .byId(id)!!
                        .durationMs,
                )
            }
        }

    @Test fun refreshBudgetJitterPauseAndSlidingWindowAreBounded() =
        runTest {
            FactsFixture(this).use { f ->
                f.engine.enrich = { _, _, _ -> EnrichResult.Ok(emptyList()) }
                val ids = (0..100).map { f.channel(it).also { c -> f.episode(c) } }
                ids.forEach { f.enrichment.refresh(it) }
                assertEquals(100, f.engine.enrichCalls.size)
                val starts = f.engine.starts
                val intervals = starts.zipWithNext { a, b -> b - a }
                assertEquals(1, intervals.count { it >= 6500 })
                assertTrue(intervals.filter { it < 6500 }.all { it in 500..1500 })
                delay(600_001)
                f.enrichment.refresh(ids.last())
                assertEquals(101, f.engine.enrichCalls.size)
            }
        }

    @Test fun refreshConcurrencyIsTwoAndVariantChangeDiscardsLateResult() =
        runTest {
            FactsFixture(this).use { f ->
                val ids = (0..3).map { f.channel(it).also { c -> f.episode(c) } }
                var active = 0
                var peak = 0
                f.engine.enrich =
                    { _, _, _ ->
                        active++
                        peak = maxOf(peak, active)
                        delay(2000)
                        active--
                        EnrichResult.Ok(emptyList())
                    }
                ids.map { async { f.enrichment.refresh(it) } }.forEach { it.await() }
                assertEquals(2, peak)
                val late = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                f.engine.enrich =
                    { _, _, _ ->
                        entered.complete(Unit)
                        late.await()
                        EnrichResult.Ok(listOf(fact(VIDEO, 300)))
                    }
                val pending = async { f.enrichment.refresh(ids.first()) }
                entered.await()
                f.db.podcastDao().setYouTubeVariants(ids.first(), 3)
                late.complete(Unit)
                pending.await()
                assertNull(
                    f.db
                        .episodeDao()
                        .youTubeByVideoId(ids.first(), VIDEO)!!
                        .durationMs,
                )
            }
        }

    @Test fun currentDescriptionCoalescesAndLastWaiterCancelsWithoutLateWrites() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.engine.facts =
                    {
                        entered.complete(Unit)
                        release.await()
                        VideoFactsResult.Ok(fact(it).copy(description = "Complete notes"))
                    }
                val a = async { loader.load(id) }
                entered.await()
                val b = async { loader.load(id) }
                // Let both callers join before releasing the controlled facts call.
                delay(100)
                a.cancelAndJoin()
                release.complete(Unit)
                assertEquals("Complete notes", b.await())
                assertEquals(1, f.engine.factCalls.size)
                assertNull(loader.load(id))
                assertEquals(1, f.engine.factCalls.size)
            }
        }

    @Test fun existingBlobWithoutCompletenessCannotAuthorizeChapters() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                val partial = "0:00 First\n0:10 Second\n0:20 Third"
                val xml =
                    """
                    <feed xmlns="http://www.w3.org/2005/Atom"
                        xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                        xmlns:media="http://search.yahoo.com/mrss/">
                      <title>Channel</title><entry><id>yt:video:aaaaaaaaaaA</id>
                      <yt:videoId>aaaaaaaaaaA</yt:videoId><title>Atom title</title>
                      <media:group><media:description>$partial${" ".repeat(512 * 1024)}0:30 Fourth</media:description>
                      </media:group></entry>
                    </feed>
                    """.trimIndent()
                val parsed =
                    kotlin.test.assertIs<ch.lkmc.neutrodyne.feeds.parse.ParseResult.Ok>(
                        ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser.discovered().parse(
                            { okio.Buffer().writeUtf8(xml) },
                            null,
                            YouTubeFeedUrls.canonical("UC" + "0".repeat(21) + "A"),
                        ),
                    )
                assertTrue(
                    parsed.feed.warnings.any {
                        it.code == ch.lkmc.neutrodyne.feeds.model.WarningCode.TEXT_TRUNCATED
                    },
                )
                newIngestor(
                    f.db,
                    f.clock,
                    defaultDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                ).ingest(
                    dueFeedOf(f.db, channel),
                    parsed.feed,
                    ch.lkmc.neutrodyne.core.data.ingest.IngestContext(
                        ch.lkmc.neutrodyne.core.data.ingest.IngestMode.REFRESH,
                        true,
                        fetchMeta(),
                    ),
                )
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                assertNull(loader.load(id))
                assertTrue(f.engine.factCalls.isEmpty())
                assertEquals(partial, EpisodeDescriptionCodec.decode(f.db.episodeDao().description(id)!!).text)
            }
        }

    @Test fun lastDescriptionWaiterCancelsAndLateFactsCannotStoreNotes() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                val ready = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                f.engine.facts = {
                    ready.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    VideoFactsResult.Ok(fact(it).copy(description = "Late notes"))
                }
                val load = async { loader.load(id) }
                ready.await()
                load.cancelAndJoin()
                finish.complete(Unit)
                runCurrent()
                assertNull(f.db.episodeDao().description(id))
                assertEquals(1, f.engine.factCalls.size)
            }
        }

    @Test fun truncatedOrTransientCurrentNotesStayAbsentAndCanRecover() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                f.engine.facts =
                    {
                        VideoFactsResult.Ok(
                            fact(it).copy(description = "0:00 One\n0:10 Two", descriptionTruncated = true),
                        )
                    }
                assertNull(loader.load(id))
                assertNull(f.db.episodeDao().description(id))
                f.engine.facts = { VideoFactsResult.Failed(TransientKind.NETWORK, null) }
                assertNull(loader.load(id))
                f.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = "Complete")) }
                assertEquals("Complete", loader.load(id))
            }
        }

    private companion object {
        const val VIDEO = "aaaaaaaaaaA"
        const val OTHER = "bbbbbbbbbbB"
        const val THIRD = "ccccccccccC"
        const val DAY = 86_400_000L
    }
}

internal fun fact(
    id: String,
    duration: Long? = null,
    availability: Availability? = null,
    short: Boolean? = null,
) = VideoFacts(id, null, null, false, duration, availability, short, null)

internal class ControlledEnricher(
    private val clock: Clock,
) : YouTubeEnricher {
    var facts: suspend (String) -> VideoFactsResult = { VideoFactsResult.Unsupported }
    var enrich: suspend (String, Set<String>, Int) -> EnrichResult = { _, _, _ -> EnrichResult.Unsupported }
    var page: suspend (String, Int, UploadsCursor?) -> UploadsPageResult = { _, _, _ -> UploadsPageResult.Unsupported }
    val factCalls = mutableListOf<String>()
    val enrichCalls = mutableListOf<Set<String>>()
    val starts = mutableListOf<Long>()
    val pages = mutableListOf<Triple<String, Int, UploadsCursor?>>()

    override suspend fun facts(videoId: String): VideoFactsResult {
        factCalls += videoId
        return facts.invoke(videoId)
    }

    override suspend fun enrich(
        channelId: String,
        videoIds: Set<String>,
        variants: Int,
    ): EnrichResult {
        enrichCalls +=
            videoIds
        starts += clock.elapsedRealtime()
        return enrich.invoke(channelId, videoIds, variants)
    }

    override suspend fun uploadsPage(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult {
        pages +=
            Triple(channelId, variant, cursor)
        return page.invoke(channelId, variant, cursor)
    }
}

internal class FactsFixture(
    val scope: TestScope,
) : AutoCloseable {
    val clock = TestClock.from(scope.testScheduler)
    val db = newDb(clock, kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler))
    val engine = ControlledEnricher(clock)
    val writer = YouTubeFactsWriterImpl(db, JsoupShowNotesSanitizer())
    val capabilities = enabledYouTubeCapabilities()
    val enrichment = YouTubeEnrichment(db, engine, writer, clock, Random(7), capabilities)

    suspend fun channel(
        index: Int = 0,
        variants: Int = 1,
    ): Long =
        seedPodcast(
            db,
            YouTubeFeedUrls.canonical("UC" + index.toString().padStart(21, '0') + "A"),
        ) {
            copy(
                sourceType = SourceType.YOUTUBE_CHANNEL,
                youtubeChannelId = "UC" + index.toString().padStart(21, '0') + "A",
                youtubeVariants = variants,
            )
        }

    suspend fun episode(
        channel: Long,
        video: String = "aaaaaaaaaaA",
        availability: Availability = Availability.AVAILABLE,
        duration: Long? = null,
        isNew: Boolean = false,
        short: Boolean = false,
        age: Long = 0,
    ): Long =
        db.episodeDao().insertStub(
            episodeEntity(podcastId = channel) {
                copy(
                    identityKey = "g:yt:video:$video",
                    guid = "yt:video:$video",
                    externalMediaId = video,
                    title = "Atom title",
                    pubDate =
                        clock.now() - age,
                    sortDate = clock.now() - age,
                    firstSeenAt = clock.now() - age,
                    durationMs = duration,
                    availability = availability,
                    isNew = isNew,
                    isShort = short,
                    contentHash = 1234,
                )
            },
        )

    override fun close() = db.close()
}

internal fun enabledYouTubeCapabilities() =
    ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource().apply {
        capabilities.value =
            ch.lkmc.neutrodyne.youtube.api
                .YouTubeCapabilities(true, true, true, true, true, null)
    }

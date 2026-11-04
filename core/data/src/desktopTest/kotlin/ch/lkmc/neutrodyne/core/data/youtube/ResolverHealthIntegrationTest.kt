// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.DataStoreSettingsRepository
import ch.lkmc.neutrodyne.core.data.FactsFixture
import ch.lkmc.neutrodyne.core.data.fetchMeta
import ch.lkmc.neutrodyne.core.data.newIngestor
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.stubAdapter
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractionReservation
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.VideoFacts
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.DefaultNetworkReset
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.ResolvedUrlCache
import ch.lkmc.neutrodyne.youtube.engine.YtDlpClient
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEngine
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEnricher
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtDlpStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

/** Real lifecycle, admission, mapping, cache and persistent health; only host IO is scripted. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolverHealthIntegrationTest {
    private val roots = mutableListOf<File>()

    @AfterTest fun cleanTempStores() = roots.forEach { it.deleteRecursively() }

    @Test fun halfOpenJoinsCompleteOnceAndCancellationReleasesTrial() =
        runTest {
            val f = fixture()
            f.openAndRetry()
            f.transport.answer = { awaitCancellation() }
            val owner = async { f.resolve() }
            runCurrent()
            val join = async { f.resolve() }
            runCurrent()
            assertEquals(1, f.probe.admissions.size)
            assertIs<ExtractionGate.Deny>(f.gate())
            owner.cancelAndJoin()
            assertTrue(f.probe.completions.isEmpty())
            join.cancelAndJoin()
            runCurrent()
            assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.Inconclusive), f.probe.completions)
            assertEquals(1, f.transport.cancelled.size)
            assertEquals(ExtractionGate.AllowTrial, f.gate())
            f.transport.answer = { f.reply() }
            assertIs<ResolveResult.Ok>(f.resolve())
            assertEquals(
                2,
                f.probe.admissions
                    .distinct()
                    .size,
            )
            assertEquals(
                listOf<ExtractionOutcome>(ExtractionOutcome.Inconclusive, ExtractionOutcome.Success),
                f.probe.completions,
            )
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            assertEquals(f.probe.admissions, f.probe.completedTokens)
            f.health.awaitLoaded()
            assertEquals(0, f.settings.get(Keys.BREAKER_OPEN_UNTIL))
        }

    @Test fun cacheHitAndCancelledQueuedCallLeaveHalfOpenSlotFree() =
        runTest {
            val f = fixture()
            val audio = assertIs<ResolveResult.Ok>(f.resolve()).audio
            f.openAndRetry()
            // Seed a valid representation to exercise the cache branch while the real gate is half-open.
            f.cache.put(VIDEO, AudioPref(), audio)
            assertIs<ResolveResult.Ok>(f.resolve())
            assertEquals(1, f.probe.admissions.size)
            assertEquals(ExtractionGate.AllowTrial, f.gate())
            f.resolver.invalidateAll()
            f.transport.status.value =
                f.transport.status.value
                    .copy(workers = 1)
            val occupied =
                async {
                    f.client.operation(YtxPriority.PLAYBACK, YtxDeadline(100_000)) { awaitCancellation() }
                }
            runCurrent()
            val queued = async { f.resolve() }
            runCurrent()
            queued.cancelAndJoin()
            runCurrent()
            assertEquals(1, f.probe.admissions.size)
            assertEquals(ExtractionGate.AllowTrial, f.gate())
            occupied.cancelAndJoin()
            assertIs<ResolveResult.Ok>(f.resolve())
            assertEquals(2, f.probe.admissions.size)
            assertEquals(2, f.probe.completions.size)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
        }

    @Test fun openingSynchronouslyFencesCacheAndInflightReplyBeforeLaterAdmission() =
        runTest {
            val f = fixture()
            assertIs<ResolveResult.Ok>(f.resolve())
            val release = CompletableDeferred<Unit>()
            f.transport.answer = {
                release.await()
                f.reply(OTHER)
            }
            val old = async { f.resolve(OTHER) }
            runCurrent()
            f.open()
            assertEquals(1, f.invalidations)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            assertIs<ExtractionGate.Deny>(f.gateDuringInvalidation)
            assertEquals(TransientKind.BREAKER_OPEN, assertIs<ResolveResult.Transient>(f.resolve()).kind)
            assertEquals(2, f.transport.calls.size)
            release.complete(Unit)
            assertIs<ResolveResult.Ok>(old.await())
            assertNull(f.cache.get(OTHER, AudioPref()))
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            f.health.awaitLoaded()
            assertEquals(f.health.state.value.breakerOpenUntil, f.settings.get(Keys.BREAKER_OPEN_UNTIL))
            f.health.retryNow()
            f.transport.answer = { f.reply(OTHER) }
            assertIs<ResolveResult.Ok>(f.resolve(OTHER))
            assertEquals(3, f.transport.calls.size)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
        }

    @Test fun networkIdentityResetFencesOldCacheAndHintsWithoutHealthReports() =
        runTest {
            val f = fixture()
            val network = DefaultNetworkReset(f.resolver::defaultNetworkChanged)
            network.changed("wifi-a")
            assertIs<ResolveResult.Ok>(f.resolve())
            val release = CompletableDeferred<Unit>()
            f.transport.answer = {
                release.await()
                f.reply(OTHER)
            }
            val old = async { f.resolve(OTHER) }
            runCurrent()
            network.changed("wifi-b")
            assertNull(f.cache.get(VIDEO, AudioPref()))
            assertNull(f.hints.current())
            release.complete(Unit)
            assertIs<ResolveResult.Ok>(old.await())
            assertNull(f.cache.get(OTHER, AudioPref()))
            assertNull(f.hints.current())
            assertEquals(
                listOf<ExtractionOutcome>(ExtractionOutcome.Success, ExtractionOutcome.Success),
                f.probe.completions,
            )
            f.transport.answer = { f.reply(OTHER) }
            assertIs<ResolveResult.Ok>(f.resolve(OTHER))
            network.changed("wifi-b")
            assertIs<ResolveResult.Ok>(f.resolve(OTHER))
            assertEquals(3, f.transport.calls.size)
            assertEquals(IpFamily.V4, f.hints.current())
            assertEquals(0, f.invalidations)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
        }

    @Test fun malformedAudioCannotCloseTrialAndRateIsIndependentOfParseFailure() =
        runTest {
            val f = fixture()
            f.openAndRetry()
            f.transport.answer = { f.reply().replace("mp4a.40.2", "https://invalid.test/codec") }
            assertEquals(TransientKind.EXTRACTION, assertIs<ResolveResult.Transient>(f.resolve()).kind)
            assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.ParseFailure(VIDEO)), f.probe.completions)
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            f.health.retryNow()
            f.transport.answer = { throw YtxCallException("RATE_LIMITED") }
            assertEquals(TransientKind.RATE_LIMITED, assertIs<ResolveResult.Transient>(f.resolve()).kind)
            assertEquals(ExtractionOutcome.Inconclusive, f.probe.completions.last())
            assertEquals(TransientKind.RATE_LIMITED, assertIs<ExtractionGate.Deny>(f.gate()).kind)
            f.health.awaitLoaded()
            assertEquals(1, f.settings.get(Keys.RATE_LIMIT_LEVEL))
            f.health.retryNow()
            f.transport.answer = { f.reply() }
            assertIs<ResolveResult.Ok>(f.resolve())
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            assertNull(f.health.state.value.rateLimitedUntil)
        }

    @Test fun parseDedupAndInvalidationDoNotDuplicateMediaReports() =
        runTest {
            val f = fixture()
            f.transport.answer = { "{}" }
            repeat(8) { assertIs<ResolveResult.Transient>(f.resolve()) }
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            for (video in listOf(OTHER, "ccccccccccC", "ddddddddddD")) f.resolve(video)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            repeat(8) {
                f.resolver.invalidate(VIDEO)
                f.resolver.invalidateAll()
            }
            assertEquals(0, f.invalidations)
            f.resolve("eeeeeeeeeeE")
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertEquals(1, f.invalidations)
            assertEquals(f.probe.admissions.size, f.probe.completions.size)
        }

    @Test fun expiredHealthLoadDoesNotReserveOrCompleteAnOperation() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val f = fixture(release)
            val result = async { f.resolve() }
            runCurrent()
            f.clock.elapsedMs = 30_000
            release.complete(Unit)
            assertEquals(TransientKind.TIMEOUT, assertIs<ResolveResult.Transient>(result.await()).kind)
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.probe.admissions.isEmpty(), "expired load must not reserve real health")
            assertTrue(f.probe.completions.isEmpty())
        }

    @Test fun factsAndCompositeExpireDuringRealHealthLoadBeforeReservation() =
        runTest {
            for (composite in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                val f = fixture(release)
                val result =
                    async {
                        if (composite) {
                            f.enricher.enrich(CHANNEL, setOf(VIDEO), 7)
                        } else {
                            f.enricher.facts(VIDEO)
                        }
                    }
                runCurrent()
                f.clock.elapsedMs = 46_000
                release.complete(Unit)
                val outcome = result.await()
                if (composite) {
                    assertEquals(TransientKind.TIMEOUT, assertIs<EnrichResult.Failed>(outcome).kind)
                } else {
                    assertEquals(TransientKind.TIMEOUT, assertIs<VideoFactsResult.Failed>(outcome).kind)
                }
                assertTrue(f.transport.calls.isEmpty())
                assertTrue(f.probe.admissions.isEmpty())
                assertTrue(f.probe.completions.isEmpty())
            }
        }

    @Test fun threeTabCompositeCompletesOneRealTrialAndMalformedFactsReopenIt() =
        runTest {
            val f = fixture()
            f.openAndRetry()
            f.transport.answer = { """{"cursor":null,"items":[],"hasMore":false}""" }
            assertIs<EnrichResult.Ok>(f.enricher.enrich(CHANNEL, setOf(VIDEO), 7))
            assertEquals(3, f.transport.calls.size)
            assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.Success), f.probe.completions)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            f.openAndRetry()
            f.transport.answer = { """{"videoId":"wrong"}""" }
            assertIs<VideoFactsResult.Failed>(f.enricher.facts(VIDEO))
            assertEquals(ExtractionOutcome.ParseFailure(VIDEO), f.probe.completions.last())
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertEquals(f.probe.admissions, f.probe.completedTokens)
            f.health.awaitLoaded()
            assertTrue(f.settings.get(Keys.BREAKER_OPEN_UNTIL) > f.clock.now())
        }

    @Test fun cancelledCompositeReleasesTrialAndRateFactsPersistOnce() =
        runTest {
            val f = fixture()
            f.openAndRetry()
            f.transport.answer = { awaitCancellation() }
            val owner = async { f.enricher.enrich(CHANNEL, setOf(VIDEO), 7) }
            runCurrent()
            assertIs<ExtractionGate.Deny>(f.gate())
            assertIs<VideoFactsResult.Failed>(f.enricher.facts(OTHER))
            assertEquals(1, f.probe.admissions.size)
            owner.cancelAndJoin()
            assertEquals(ExtractionGate.AllowTrial, f.gate())
            assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.Inconclusive), f.probe.completions)
            f.transport.answer = { throw YtxCallException("RATE_LIMITED") }
            val rate = assertIs<VideoFactsResult.Failed>(f.enricher.facts(VIDEO))
            assertEquals(TransientKind.RATE_LIMITED, rate.kind)
            f.health.awaitLoaded()
            assertEquals(1, f.settings.get(Keys.RATE_LIMIT_LEVEL))
            assertEquals(2, f.probe.completions.size)
            assertIs<ExtractionGate.Deny>(f.gate())
            f.health.retryNow()
            f.transport.answer = { """{"videoId":"$VIDEO","description":"Full notes","descriptionTruncated":false}""" }
            assertIs<VideoFactsResult.Ok>(f.enricher.facts(VIDEO))
            assertEquals(f.probe.admissions, f.probe.completedTokens)
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
            f.health.awaitLoaded()
            assertEquals(0, f.settings.get(Keys.RATE_LIMIT_LEVEL))
        }

    @Test fun realFactsPersistCompleteSnapshotAndReuseWithoutAnotherHostCall() =
        runTest {
            val f = fixture()
            FactsFixture(this).use { data ->
                val id = data.episode(data.channel())
                f.transport.answer =
                    { """{"videoId":"$VIDEO","description":"0:00 Full notes","descriptionTruncated":false}""" }
                val loader = YouTubeDescriptionLoader(data.db, f.enricher, data.writer, data.clock, backgroundScope)
                assertEquals("0:00 Full notes", loader.load(id))
                assertEquals("0:00 Full notes", loader.load(id))
                assertEquals(1, f.transport.calls.size)
                assertEquals(f.probe.admissions, f.probe.completedTokens)
            }
        }

    @Test fun realFactsPromotionSurvivesRefreshDeadlineAfterCommit() =
        runTest {
            val f = fixture()
            FactsFixture(this).use { data ->
                val channel = data.channel()
                val id = data.episode(channel, availability = Availability.UPCOMING, isNew = true)
                f.transport.answer = { """{"videoId":"$VIDEO","availability":"public","liveStatus":"not_live"}""" }
                val adapter =
                    stubAdapter(
                        onFetch = { _, _ -> AdapterResult.Unchanged(fetchMeta()) },
                        announce = { _, _, _ ->
                            val facts = assertIs<VideoFactsResult.Ok>(f.enricher.facts(VIDEO)).facts
                            data.writer.applyFacts(channel, listOf(facts))
                            awaitCancellation()
                        },
                    )
                val report =
                    newRefresher(data.db, mapOf(SourceType.YOUTUBE_CHANNEL to adapter), f.clock).run(
                        RefreshRequest(
                            RefreshScope.Podcasts(listOf(channel)),
                            true,
                            false,
                            RefreshOrigin.MANUAL,
                            deadlineElapsedMs = 60_000,
                        ),
                    )
                assertEquals(listOf(id), report.newEpisodes.flatMap { it.episodeIds })
                assertEquals(1, f.transport.calls.size)
                assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.Success), f.probe.completions)
            }
        }

    @Test fun realPageCompletionStaysOnceWhenCancelledStorageRollsBack() =
        runTest {
            val f = fixture()
            FactsFixture(this).use { data ->
                val channel = data.channel()
                f.transport.answer = { checkNotNull(javaClass.getResource("/recorded/tab/result.json")).readText() }
                val entered = CompletableDeferred<Unit>()
                val writer =
                    object : YouTubeFactsWriter by data.writer {
                        override suspend fun applyFacts(
                            podcastId: Long,
                            facts: List<VideoFacts>,
                        ) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                val loader =
                    YouTubeOlderLoader(
                        data.db,
                        f.enricher,
                        newIngestor(data.db, data.clock, defaultDispatcher = StandardTestDispatcher(testScheduler)),
                        writer,
                        data.clock,
                        backgroundScope,
                    )
                val load = async { loader.load(channel) }
                entered.await()
                load.cancelAndJoin()
                runCurrent()
                assertTrue(
                    data.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
                assertEquals(1, f.transport.calls.size)
                assertEquals(listOf<ExtractionOutcome>(ExtractionOutcome.Success), f.probe.completions)
                assertEquals(f.probe.admissions, f.probe.completedTokens)
            }
        }

    @Test fun unmarkedWireDescriptionCannotAuthorizeCompleteStoredNotes() =
        runTest {
            val f = fixture()
            FactsFixture(this).use { data ->
                val id = data.episode(data.channel())
                val loader = YouTubeDescriptionLoader(data.db, f.enricher, data.writer, data.clock, backgroundScope)
                for (flag in listOf("", ",\"descriptionTruncated\":\"false\"", ",\"descriptionTruncated\":null")) {
                    f.transport.answer = { """{"videoId":"$VIDEO","description":"0:00 Claimed full"$flag}""" }
                    assertNull(loader.load(id))
                    assertNull(data.db.episodeDao().description(id))
                }
                assertEquals(List<ExtractionOutcome>(3) { ExtractionOutcome.ParseFailure(VIDEO) }, f.probe.completions)
                assertEquals(f.probe.admissions, f.probe.completedTokens)
            }
        }

    @Test fun resolverAndFactsShareUnavailableHistoryAndTheRealHealthOwner() =
        runTest {
            val f = fixture()
            f.transport.answer = { throw YtxCallException("UNAVAILABLE") }
            assertIs<ResolveResult.Unavailable>(f.resolve(VIDEO))
            assertIs<ResolveResult.Unavailable>(f.resolve(OTHER))
            val third = "ccccccccccC"
            val clustered = assertIs<VideoFactsResult.Failed>(f.enricher.facts(third))
            assertEquals(TransientKind.EXTRACTION, clustered.kind)
            assertEquals(ExtractionOutcome.ParseFailure(third), f.probe.completions.last())
            f.transport.answer = { throw YtxCallException("EXTRACTION") }
            for (video in listOf("ddddddddddD", "eeeeeeeeeeE", "ffffffffffF", "ggggggggggG")) {
                assertIs<VideoFactsResult.Failed>(f.enricher.facts(video))
            }
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertEquals(1, f.invalidations)
            assertEquals(f.probe.admissions, f.probe.completedTokens)
            f.health.awaitLoaded()
            assertTrue(f.settings.get(Keys.BREAKER_OPEN_UNTIL) > f.clock.now())
        }

    private suspend fun TestScope.fixture(loadBarrier: CompletableDeferred<Unit>? = null): Fixture {
        val root =
            kotlin.io.path
                .createTempDirectory("nd-resolver-health")
                .toFile()
                .also(roots::add)
        val dirs =
            AppDirs(
                root.resolve("data").toPath(),
                root.resolve("config").toPath(),
                root.resolve("cache").toPath(),
                root.resolve("state").toPath(),
                root.resolve("logs").toPath(),
                root.resolve("downloads").toPath(),
            )
        val factory =
            SettingsDataStoreFactory(StoragePaths(dirs), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val settings =
            DataStoreSettingsRepository(
                DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
                DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
            )
        val repository =
            object : SettingsRepository by settings {
                override suspend fun <T : Any> get(key: SettingKey<T>): T {
                    loadBarrier?.await()
                    return settings.get(key)
                }
            }
        return Fixture(this, repository).also { if (loadBarrier == null) it.health.awaitLoaded() }
    }

    private class Fixture(
        scope: TestScope,
        val settings: SettingsRepository,
    ) {
        val clock = TestClock()
        val transport = FakeYtxTransport()
        val cache = ResolvedUrlCache(clock)
        val hints =
            object : IpFamilyHints {
                private var family: IpFamily? = null

                override fun current() = family

                override fun set(family: IpFamily?) {
                    this.family = family
                }
            }
        var invalidations = 0
        var gateDuringInvalidation: ExtractionGate? = null
        val health = DefaultYouTubeHealth(settings, clock, scope.backgroundScope, 42, ::invalidate) {}
        val probe = HealthProbe(health)
        val engine =
            YtDlpEngine(
                transport,
                FakeEngineStore(),
                FakeEngineSettings(),
                FakeEnginePlatformPreflight(),
                CompileMarker(42, "2026.08.19", "3.14"),
                health,
                scope.backgroundScope,
            )
        val client = YtDlpClient(transport, clock, scope.backgroundScope)
        private val errors = YtDlpErrorMapper(clock)
        val enricher =
            YtDlpEnricher(
                client,
                engine,
                probe,
                errors,
                { YtxContext("en", "US", "test-agent") },
                clock,
            )
        val resolver =
            YtDlpStreamResolver(
                client,
                engine,
                probe,
                cache,
                errors,
                hints,
                { YtxContext("en", "US", "test-agent") },
                clock,
                scope.backgroundScope,
            )

        init {
            transport.answer = { reply() }
        }

        private fun invalidate() {
            invalidations++
            gateDuringInvalidation = gate()
            resolver.invalidateAll()
        }

        fun reply(video: String = VIDEO) =
            checkNotNull(javaClass.getResource("/recorded/resolve/result.json"))
                .readText()
                .replace(VIDEO, video)

        suspend fun resolve(video: String = VIDEO) = resolver.resolveAudio(video, AudioPref(), ResolvePurpose.PLAYBACK)

        fun gate() = health.extractionGate(clock.now())

        fun open() {
            repeat(5) { health.reportExtraction(ExtractionOutcome.ParseFailure("open$it")) }
        }

        fun openAndRetry() {
            open()
            health.retryNow()
        }
    }

    /** Observation only: every call and token is forwarded to the same real actor. No policy here. */
    private class HealthProbe(
        private val real: YouTubeExtractionHealth,
    ) : YouTubeExtractionHealth by real {
        val admissions = mutableListOf<ExtractionReservation>()
        val completions = mutableListOf<ExtractionOutcome>()
        val completedTokens = mutableListOf<ExtractionReservation>()

        override fun reserveExtraction(nowMs: Long): ExtractionAdmission =
            real.reserveExtraction(nowMs).also {
                if (it is ExtractionAdmission.Admitted) admissions += it.reservation
            }

        override fun completeExtraction(
            reservation: ExtractionReservation,
            outcome: ExtractionOutcome,
        ) {
            completedTokens += reservation
            completions += outcome
            real.completeExtraction(reservation, outcome)
        }
    }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaA"
        const val VIDEO = "aaaaaaaaaaA"
        const val OTHER = "bbbbbbbbbbB"
    }
}

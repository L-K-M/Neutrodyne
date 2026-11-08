// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitor
import ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitorBindings
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.PortableYouTubePlayback
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.desktop.youtube.DesktopYouTubeBindingsModule
import ch.lkmc.neutrodyne.download.api.DownloadAudioLanguage
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import ch.lkmc.neutrodyne.youtube.impl.DefaultYouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.impl.NoOpYouTubeEnricher
import com.sun.net.httpserver.HttpServer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real desktop graph, persistent Room, real resolver/engine owners, a local range server, and a
 * generated >20MiB audio-like payload served only via exact 10MiB ranges.
 */
class DesktopYouTubeJourneyTest {
    @Test fun manualDownloadStreamsExactRangesAndCommitsAfterRename() =
        runBlocking {
            val env = Env()
            try {
                val first = env.open()
                val id = first.seed(VIDEO).second
                first.playback.reply { env.resolveJson(VIDEO) }
                first.runner.start()
                assertIs<RequestResult.Queued>(
                    first.controller.request(listOf(id), DownloadLane.MANUAL, null),
                )
                val row = first.awaitCompleted(id)
                assertEquals(listOf(range(0), range(1), range(2)), env.requests.toList())
                assertEquals(TOTAL.toLong(), row.totalBytes)
                assertEquals(SourceKind.YOUTUBE, row.sourceKind)
                assertEquals(VIDEO, row.sourceRef)
                assertEquals(140, row.resolvedItag)
                assertEquals("audio/mp4", row.mimeType)
                assertEquals(row.finalUri, first.index.localUriOrNull(id))
                val finalFile = env.dirs.downloadsDefault.resolve(checkNotNull(row.relativePath))
                assertTrue(env.bytes.contentEquals(Files.readAllBytes(finalFile)))
                first.runner.stop(RUNNER_GRACE)
                env.close(first)
            } finally {
                env.shutdown()
            }
        }

    @Test fun gracefulStopAtChunkBoundaryResumesWithoutSplice() =
        runBlocking {
            val env = Env(blockSecondChunk = true)
            try {
                val first = env.open()
                val id = first.seed(VIDEO).second
                first.playback.reply { env.resolveJson(VIDEO) }
                first.runner.start()
                assertIs<RequestResult.Queued>(
                    first.controller.request(listOf(id), DownloadLane.MANUAL, null),
                )
                val part = env.dirs.downloadsDefault.resolve(".partial/$id.part")
                withTimeout(TIMEOUT_MS) {
                    while (!Files.exists(part) || Files.size(part) != CHUNK) delay(POLL_MS)
                }
                // Wait until the held second-chunk request is in flight, then stop mid-request.
                withTimeout(TIMEOUT_MS) { while (env.requests.size < 2) delay(POLL_MS) }
                first.runner.stop(RUNNER_GRACE)
                env.close(first)
                check(Files.exists(part) && Files.size(part) == CHUNK) {
                    "part lost: exists=${Files.exists(part)} size=${Files.size(part)}"
                }
                val second = env.open()
                second.playback.reply { env.resolveJson(VIDEO) }
                second.runner.start()
                second.runner.poke("downloads-manual")
                val row =
                    try {
                        second.awaitCompleted(id)
                    } catch (failure: Throwable) {
                        throw AssertionError("requests=${env.requests}", failure)
                    }
                // The held chunk-1 request was cancelled mid-flight; the resume re-requests it exactly once.
                assertEquals(
                    listOf(range(0), range(1), range(1), range(2)),
                    env.requests.toList(),
                )
                val finalFile = env.dirs.downloadsDefault.resolve(checkNotNull(row.relativePath))
                assertTrue(env.bytes.contentEquals(Files.readAllBytes(finalFile)))
                second.runner.stop(RUNNER_GRACE)
                env.close(second)
            } finally {
                env.shutdown()
            }
        }

    @Test fun autoPlannerQueuesAndAutoLaneCompletes() =
        runBlocking {
            val env = Env()
            try {
                val graph = env.open()
                val (channel, id) = graph.seed(VIDEO, auto = true)
                graph.playback.reply { env.resolveJson(VIDEO) }
                graph.settings.set(YouTubeSettingKeys.AUTO_DOWNLOAD, true)
                graph.runner.start()
                graph.planner.plan(listOf(channel))
                val row = graph.db.downloadDao().byEpisode(id)
                assertNotNull(row)
                assertEquals(DownloadLane.AUTO, row.lane)
                graph.runner.poke("downloads-auto")
                val completed = graph.awaitCompleted(id)
                assertEquals(listOf(range(0), range(1), range(2)), env.requests.toList())
                assertEquals(completed.finalUri, graph.index.localUriOrNull(id))
                graph.runner.stop(RUNNER_GRACE)
                env.close(graph)
            } finally {
                env.shutdown()
            }
        }

    @Test fun cancelRemovesTheQueuedRowAndPart() =
        runBlocking {
            val env = Env(blockSecondChunk = true)
            try {
                val graph = env.open()
                val id = graph.seed(VIDEO).second
                graph.playback.reply { env.resolveJson(VIDEO) }
                graph.runner.start()
                assertIs<RequestResult.Queued>(
                    graph.controller.request(listOf(id), DownloadLane.MANUAL, null),
                )
                val part = env.dirs.downloadsDefault.resolve(".partial/$id.part")
                withTimeout(TIMEOUT_MS) {
                    while (!Files.exists(part) || Files.size(part) < CHUNK) delay(POLL_MS)
                }
                graph.controller.cancel(listOf(id))
                withTimeout(TIMEOUT_MS) {
                    while (graph.db.downloadDao().byEpisode(id) != null) delay(POLL_MS)
                }
                withTimeout(TIMEOUT_MS) {
                    while (Files.exists(part)) delay(POLL_MS)
                }
                assertNull(graph.index.localUriOrNull(id))
                graph.runner.stop(RUNNER_GRACE)
                env.close(graph)
            } finally {
                env.shutdown()
            }
        }

    @Test fun capabilityReenableAdmitsPersistedRowWithoutNetworkEmission() =
        runBlocking {
            val env = Env()
            try {
                val graph =
                    env.open { opened ->
                        opened.playback.setEngineEnabled(false)
                        opened.awaitCapabilities(false)
                    }
                try {
                    val (_, id) = graph.seed(VIDEO)
                    val refused = graph.seed(VIDEO2).second
                    // A YouTube row persisted while downloads were still allowed: validly queued,
                    // now stranded by the engine-off capability state.
                    graph.db.downloadDao().insert(
                        DownloadEntity(
                            id,
                            DownloadLane.MANUAL,
                            DownloadState.QUEUED,
                            priority = 100,
                            requestedAt = 1,
                            sourceKind = SourceKind.YOUTUBE,
                            sourceRef = VIDEO,
                            rootId = "dir:default",
                            allowMetered = true,
                        ),
                    )
                    // New admissions keep rejecting; only the persisted row may recover.
                    val result = graph.controller.request(listOf(refused), DownloadLane.MANUAL, null)
                    val queued = assertIs<RequestResult.Queued>(result)
                    assertEquals(0, queued.queued)
                    assertEquals(RejectReason.YOUTUBE_NOT_SUPPORTED, queued.rejected[refused])
                    graph.playback.reply { env.resolveJson(VIDEO) }
                    graph.runner.start()
                    graph.awaitWaitReason(id, WaitReason.YOUTUBE_ENGINE_OFF)
                    // Only the capability flips: static network and charging never emit again.
                    graph.playback.setEngineEnabled(true)
                    graph.awaitCapabilities(true)
                    graph.runner.poke("downloads-manual")
                    val row = graph.awaitCompleted(id)
                    assertEquals(listOf(range(0), range(1), range(2)), env.requests.toList())
                    assertEquals(row.finalUri, graph.index.localUriOrNull(id))
                    graph.runner.stop(RUNNER_GRACE)
                } finally {
                    env.close(graph)
                }
            } finally {
                env.shutdown()
            }
        }

    /**
     * W10 journey leg: the explicit channel search runs the real client/lookup/search pipeline,
     * a hit's channel-page URL is what `AddPodcastKey` would carry, and the normal Add path —
     * Layer A resolver + `subscribeUseCase.youTube` — lands a YOUTUBE_CHANNEL row. Synthetic
     * wire answers only; nothing touches the network.
     */
    @Test fun searchHitEntersNormalAddAndSubscribes() =
        runBlocking {
            val env = Env()
            try {
                val graph = env.open()
                graph.playback.compiledStore()
                graph.playback.setEngineEnabled(true)
                graph.awaitCapabilities(true)
                graph.playback.replyFor { method, _ ->
                    when (method) {
                        "search_open" -> env.searchJson()
                        "lookup" -> env.lookupJson()
                        else -> error("unexpected $method")
                    }
                }
                val page = assertIs<ChannelSearchResult.Ok>(graph.channelSearch.search("synthetic"))
                val hit = page.hits.single()
                assertEquals(HIT_CHANNEL, hit.channelId)
                // The UI turns a hit into AddPodcastKey(YouTubeFeedUrls.channelPage(id)); the same
                // classification → resolve → subscribe pipeline then owns the channel.
                val ref = YouTubeUrlClassifier().classify(YouTubeFeedUrls.channelPage(hit.channelId))
                val resolution =
                    assertIs<ChannelResolution.Resolved>(
                        graph.channelResolver.resolve(checkNotNull(ref), MetadataDepth.AVATAR),
                    )
                assertEquals(HIT_CHANNEL, resolution.channelId)
                assertEquals(ResolvedVia.EXTRACTOR, resolution.via)
                val outcome =
                    graph.subscribeUseCase.youTube(resolution, YouTubeVariantBits.LONG_FORM, emptySet())
                val podcastId = assertIs<Outcome.Success<Long>>(outcome).value
                val row = checkNotNull(graph.db.podcastDao().byId(podcastId))
                assertEquals(SourceType.YOUTUBE_CHANNEL, row.sourceType)
                assertEquals(HIT_CHANNEL, row.youtubeChannelId)
                assertEquals(
                    listOf("search_open", "lookup"),
                    graph.playback.transportCallNames(),
                )
                env.close(graph)
            } finally {
                env.shutdown()
            }
        }

    private class Env(
        val blockSecondChunk: Boolean = false,
    ) {
        val dirs = tempAppDirs("nd-yt-journey").also { it.ensureCreated() }
        val bytes =
            ByteArray(TOTAL) { ((it * 31) % 251).toByte() }.also {
                ByteArray(12)
                    .also { head ->
                        "size".encodeToByteArray().copyInto(head)
                        "ftypM4A ".encodeToByteArray().copyInto(head, 4)
                    }.copyInto(it)
            }
        val requests = CopyOnWriteArrayList<String>()
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private var graph: YouTubeJourneyGraph? = null

        init {
            server.executor = executor
            server.createContext("/audio") { exchange ->
                val header = checkNotNull(exchange.requestHeaders.getFirst("Range"))
                requests += header
                val (start, end) = header.removePrefix("bytes=").split("-").map { it.toLong() }
                if (blockSecondChunk && start == CHUNK) release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                val body = bytes.copyOfRange(start.toInt(), (end + 1).toInt())
                exchange.responseHeaders.add("Content-Type", "audio/mp4")
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
                try {
                    exchange.responseBody.use { it.write(body) }
                } catch (_: java.io.IOException) {
                    // The runner's graceful stop closes the blocked socket.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun searchJson(): String =
            """{"cursor":null,"hits":[{"channelId":"$HIT_CHANNEL","title":"Synthetic channel",""" +
                """"handle":"@synthetic","followerCount":null,"avatar":null,"description":null,""" +
                """"verified":false}],"hasMore":false}"""

        fun lookupJson(): String =
            """{"channelId":"$HIT_CHANNEL","title":"Synthetic channel","handle":"@synthetic",""" +
                """"description":null,"followerCount":null,"avatars":[],"banners":[]}"""

        fun resolveJson(videoId: String): String =
            """{"videoId":"$videoId","duration":120,"liveStatus":"not_live","formats":[""" +
                """{"formatId":"140","url":"http://127.0.0.1:${server.address.port}/audio?itag=140""" +
                """&clen=$TOTAL&lmt=1700000000000000&expire=4102444800&sig=T",""" +
                """"ext":"m4a","acodec":"mp4a.40.2","tbr":128.0,"filesize":$TOTAL,""" +
                """"language":null,"languagePreference":null,"formatNote":"medium","protocol":"http","xtags":null}]}"""

        suspend fun open(beforeInitializers: suspend (YouTubeJourneyGraph) -> Unit = {}): YouTubeJourneyGraph {
            val info = BuildInfoLoader.load()
            val reporter = DesktopCrashReporter(dirs, info, DesktopClock, RecentLogBuffer())
            val graph = createGraphFactory<YouTubeJourneyGraph.Factory>().create(dirs, info, reporter)
            beforeInitializers(graph)
            runInitializers(graph.initializers.filter { it.order <= 130 }.toSet())
            this.graph = graph
            return graph
        }

        suspend fun close(graph: YouTubeJourneyGraph) {
            graph.scope.cancel()
            graph.scope.coroutineContext[Job]?.join()
            graph.db.close()
            release.countDown()
            this.graph = null
        }

        fun shutdown() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            dirs.data.parent
                .toFile()
                .deleteRecursively()
        }
    }

    private suspend fun YouTubeJourneyGraph.seed(
        videoId: String,
        auto: Boolean = false,
    ): Pair<Long, Long> {
        val channel =
            db.podcastDao().insertPodcast(
                podcastEntity {
                    copy(
                        sourceType = SourceType.YOUTUBE_CHANNEL,
                        youtubeChannelId = CHANNEL,
                        nextRefreshAt = Long.MAX_VALUE,
                        autoDownloadEligibleAfter = if (auto) 0 else null,
                    )
                },
            )
        val id =
            db.episodeDao().insertStub(
                episodeEntity(podcastId = channel) {
                    copy(
                        externalMediaId = videoId,
                        enclosureUrl = null,
                        enclosureType = null,
                        isNew = auto,
                        firstSeenAt = 1_000,
                    )
                },
            )
        return channel to id
    }

    private suspend fun YouTubeJourneyGraph.awaitCapabilities(expected: Boolean) =
        withTimeout(TIMEOUT_MS) { playback.capabilities.capabilities.first { it.downloads == expected } }

    private suspend fun YouTubeJourneyGraph.awaitWaitReason(
        id: Long,
        reason: WaitReason,
    ) = withTimeout(TIMEOUT_MS) {
        while (db.downloadDao().byEpisode(id)?.waitReason != reason) delay(POLL_MS)
    }

    private suspend fun YouTubeJourneyGraph.awaitCompleted(id: Long) =
        withTimeout(TIMEOUT_MS) {
            var row = db.downloadDao().byEpisode(id)
            while (row?.state != DownloadState.COMPLETED || index.localUriOrNull(id) != row.finalUri) {
                check(
                    row == null ||
                        (
                            row.state != DownloadState.FAILED &&
                                (row.waitReason != WaitReason.BACKOFF || row.lastError == null)
                        ),
                ) {
                    "download failed: state=${row?.state} wait=${row?.waitReason} error=${row?.lastError}"
                }
                delay(POLL_MS)
                row = db.downloadDao().byEpisode(id)
            }
            checkNotNull(row)
        }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaA"
        const val HIT_CHANNEL = "UCbbbbbbbbbbbbbbbbbbbbbA"
        const val VIDEO = "ytvideo0001"
        const val VIDEO2 = "ytvideo0002"
        const val TOTAL = 21 * 1024 * 1024 + 513
        const val CHUNK = 10L * 1024 * 1024
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 10L
        const val HOLD_SECONDS = 30L
        val RUNNER_GRACE = 3.seconds

        fun range(chunk: Int) = "bytes=${chunk * CHUNK}-${minOf((chunk + 1) * CHUNK, TOTAL.toLong()) - 1}"
    }
}

@DependencyGraph(
    AppScope::class,
    excludes = [DesktopNetworkMonitorBindings::class, DesktopYouTubeBindingsModule::class],
)
internal interface YouTubeJourneyGraph {
    val controller: DownloadController
    val planner: AutoDownloadPlanner
    val resolver: EffectiveSettingsResolver
    val index: LocalMediaIndex
    val runner: DesktopJobRunner
    val db: NeutrodyneDatabase
    val settings: SettingsRepository
    val playback: PortableYouTubePlayback
    val channelResolver: YouTubeChannelResolver
    val channelSearch: YouTubeChannelSearch
    val subscribeUseCase: SubscribeUseCase
    val initializers: Set<AppInitializer>

    @ApplicationScope val scope: CoroutineScope

    @Binds val DesktopCrashReporter.asCrashReporter: CrashReporter

    @Binds val DesktopCrashReporter.asCrashContext: CrashContext

    @Provides fun database(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    @Provides fun monitor(
        @ApplicationScope scope: CoroutineScope,
        power: PowerMonitor,
    ): DesktopNetworkMonitor = DesktopNetworkMonitorBindings.desktopNetworkMonitor(scope, power)

    @Provides fun notifier(): DesktopNotifier =
        object : DesktopNotifier {
            override suspend fun post(n: DesktopNotification) = Unit

            override fun cancel(id: String) = Unit
        }

    @Provides fun poster(): NewEpisodePoster =
        object : NewEpisodePoster {
            override suspend fun post(notices: List<NewEpisodeNotice>) = Unit

            override suspend fun removePodcasts(podcastIds: List<Long>) = Unit
        }

    @Provides fun audioLanguage(): DownloadAudioLanguage = DownloadAudioLanguage { "en" }

    @SingleIn(AppScope::class)
    @Provides
    fun playback(
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): PortableYouTubePlayback = PortableYouTubePlayback(clock, scope) { FakeYouTubeHealth() }

    @Provides fun capabilities(playback: PortableYouTubePlayback): YouTubeCapabilitiesSource = playback.capabilities

    @Provides fun nullableCaps(playback: PortableYouTubePlayback): YouTubeCapabilitiesSource? = playback.capabilities

    @Provides fun streams(playback: PortableYouTubePlayback): YouTubeStreamResolver = playback.streams

    @Provides fun engine(playback: PortableYouTubePlayback): YouTubeEngine = playback.control

    @Provides fun enricher(): YouTubeEnricher = NoOpYouTubeEnricher()

    @Provides fun channelSearch(playback: PortableYouTubePlayback): YouTubeChannelSearch = playback.search

    @Provides fun channelLookup(playback: PortableYouTubePlayback): ExtractorChannelLookup = playback.lookup

    @Provides fun channelResolver(
        clients: NeutrodyneHttpClients,
        reader: YouTubeFeedMetadataReader,
        extractor: ExtractorChannelLookup,
    ): YouTubeChannelResolver = DefaultYouTubeChannelResolver(clients, reader, extractor) { "en" }

    @DependencyGraph.Factory fun interface Factory {
        fun create(
            @Provides dirs: AppDirs,
            @Provides info: BuildInfo,
            @Provides reporter: DesktopCrashReporter,
        ): YouTubeJourneyGraph
    }
}

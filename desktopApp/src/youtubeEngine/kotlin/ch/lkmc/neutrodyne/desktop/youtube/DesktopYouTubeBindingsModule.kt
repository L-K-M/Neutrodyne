// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.desktop.smoke.EngineSmokeProof
import ch.lkmc.neutrodyne.download.api.DownloadAudioLanguage
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.ResolvedUrlCache
import ch.lkmc.neutrodyne.youtube.engine.YtDlpChannelLookup
import ch.lkmc.neutrodyne.youtube.engine.YtDlpChannelSearch
import ch.lkmc.neutrodyne.youtube.engine.YtDlpClient
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEngine
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEnricher
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtDlpStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import ch.lkmc.neutrodyne.youtube.impl.DefaultYouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DataStoreEngineSettings
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEnginePreparationObserver
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEngineStatusInitializer
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEngineStorePaths
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopIpFamilyHints
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopPythonRuntimeLocator
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.EngineResources
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.NoLaneEngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.PythonRuntimeLocator
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.StdioReplayArming
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.StdioYtxTransport
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.YtxProcess
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Locale

/**
 * The desktop's engine-backed YouTube bindings, selected by the `neutrodyne.youtubeEngine`
 * build property like the Android `youtubeEngine` source set (11). This is MD3's first slice:
 * the stdio host plus the bundled-engine store/settings/context; the update lane arrives with
 * its own slice, so [EngineUpdateHooks] is the no-lane implementation until then.
 */
@BindingContainer
object DesktopYouTubeBindingsModule {
    @Provides
    @SingleIn(AppScope::class)
    fun runtime(engineResources: EngineResourceDir): PythonRuntimeLocator =
        DesktopPythonRuntimeLocator.standard(engineResources::dir, rootDir())

    @Provides
    @SingleIn(AppScope::class)
    fun engineResources(): EngineResourceDir = EngineResourceDir(EngineResources.standard(rootDir()))

    @Provides
    @SingleIn(AppScope::class)
    fun paths(
        dirs: AppDirs,
        engineResources: EngineResourceDir,
        runtime: PythonRuntimeLocator,
    ): DesktopEngineStorePaths = DesktopEngineStorePaths(dirs, engineResources::dir, runtime::locate)

    @Provides
    @SingleIn(AppScope::class)
    fun identity(
        build: BuildInfo,
        paths: DesktopEngineStorePaths,
    ): CompileMarker = CompileMarker(build.versionCode, BUNDLED_VERSION, paths.pythonMinor)

    @Provides
    @SingleIn(AppScope::class)
    fun store(
        paths: DesktopEngineStorePaths,
        identity: CompileMarker,
        clock: Clock,
    ): EngineStore = BundledEngineStore(paths, identity.appVersionCode, clock)

    @Provides
    @SingleIn(AppScope::class)
    fun settings(
        portable: SettingsStore,
        device: DeviceSettingsStore,
        identity: CompileMarker,
        @ApplicationScope scope: CoroutineScope,
    ): EngineSettings = DataStoreEngineSettings(portable, device, identity, scope)

    /** Desktop hosts are always 64-bit; a missing Python reports through the host's UNUSABLE. */
    @Provides
    @SingleIn(AppScope::class)
    fun preflight(build: BuildInfo): EnginePlatformPreflight =
        object : EnginePlatformPreflight {
            override fun limitation(): PlatformLimitation? =
                if (build.youTubeEngineBundled) null else PlatformLimitation.NO_ENGINE
        }

    @Provides
    @SingleIn(AppScope::class)
    fun hints(hints: DnsFamilyHints): IpFamilyHints = DesktopIpFamilyHints(hints)

    @Provides
    fun context(
        agent: UserAgentProvider,
        hints: IpFamilyHints,
    ): YtxContext {
        val locale = Locale.getDefault()
        return YtxContext(
            locale.language,
            locale.country.ifEmpty { "US" },
            agent.value,
            hints.current()?.name,
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun process(
        runtime: PythonRuntimeLocator,
        paths: DesktopEngineStorePaths,
    ): YtxProcess = YtxProcess(runtime, paths)

    /**
     * The replay arming's only production-wired state (11 Stdio protocol, MD3 AC2): null
     * everywhere except inside the smoke-armed packaged run, where it names the recorded
     * scenario the launcher passed. [PackagedEngineProof.replayArming] owns the gate.
     */
    @Provides
    fun replayArming(): StdioReplayArming? = PackagedEngineProof.replayArming()

    @Provides
    @SingleIn(AppScope::class)
    fun transport(
        process: YtxProcess,
        store: EngineStore,
        settings: EngineSettings,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        context: () -> YtxContext,
        build: BuildInfo,
        arming: StdioReplayArming?,
    ): YtxTransport =
        StdioYtxTransport(
            process,
            store,
            settings,
            arming = arming,
            debugHooks = build.debug,
            clock = clock,
            scope = scope,
            ping = YtxControlRequest(context()),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun engine(
        transport: YtxTransport,
        store: EngineStore,
        settings: EngineSettings,
        preflight: EnginePlatformPreflight,
        identity: CompileMarker,
        health: YouTubeHealth,
        clock: Clock,
        updateHooks: EngineUpdateHooks,
        @ApplicationScope scope: CoroutineScope,
    ): YtDlpEngine = YtDlpEngine(transport, store, settings, preflight, identity, health, clock, scope, updateHooks)

    /** No update lane this slice: checks answer ENGINE_UNAVAILABLE, reset still resets the record. */
    @Provides
    @SingleIn(AppScope::class)
    fun updateHooks(store: EngineStore): EngineUpdateHooks = NoLaneEngineUpdateHooks(store)

    @Provides fun capabilities(engine: YtDlpEngine): YouTubeCapabilitiesSource = engine

    @Provides fun engineControl(engine: YtDlpEngine): YouTubeEngine = engine

    @Provides
    @SingleIn(AppScope::class)
    fun client(
        transport: YtxTransport,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): YtDlpClient = YtDlpClient(transport, clock, scope)

    @Provides
    @SingleIn(AppScope::class)
    fun cache(clock: Clock): ResolvedUrlCache = ResolvedUrlCache(clock)

    @Provides
    @SingleIn(AppScope::class)
    fun errors(clock: Clock): YtDlpErrorMapper = YtDlpErrorMapper(clock)

    @Provides
    @SingleIn(AppScope::class)
    fun streams(
        client: YtDlpClient,
        capabilities: YouTubeCapabilitiesSource,
        health: YouTubeExtractionHealth,
        cache: ResolvedUrlCache,
        errors: YtDlpErrorMapper,
        hints: IpFamilyHints,
        context: () -> YtxContext,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): YouTubeStreamResolver =
        YtDlpStreamResolver(client, capabilities, health, cache, errors, hints, context, clock, scope)

    @Provides
    @SingleIn(AppScope::class)
    fun enricher(
        client: YtDlpClient,
        capabilities: YouTubeCapabilitiesSource,
        health: YouTubeExtractionHealth,
        errors: YtDlpErrorMapper,
        context: () -> YtxContext,
        clock: Clock,
    ): YouTubeEnricher = YtDlpEnricher(client, capabilities, health, errors, context, clock)

    @Provides
    @SingleIn(AppScope::class)
    fun search(
        client: YtDlpClient,
        capabilities: YouTubeCapabilitiesSource,
        health: YouTubeExtractionHealth,
        context: () -> YtxContext,
        clock: Clock,
    ): YouTubeChannelSearch = YtDlpChannelSearch(client, capabilities, health, context, clock)

    @Provides
    @SingleIn(AppScope::class)
    fun lookup(
        client: YtDlpClient,
        capabilities: YouTubeCapabilitiesSource,
        health: YouTubeExtractionHealth,
        context: () -> YtxContext,
        clock: Clock,
    ): ExtractorChannelLookup = YtDlpChannelLookup(client, capabilities, health, context, clock)

    @Provides
    @SingleIn(AppScope::class)
    fun channelResolver(
        clients: NeutrodyneHttpClients,
        reader: YouTubeFeedMetadataReader,
        extractor: ExtractorChannelLookup,
    ): YouTubeChannelResolver =
        DefaultYouTubeChannelResolver(clients, reader, extractor) {
            Locale.getDefault().toLanguageTag()
        }

    @Provides fun audioLanguage(): DownloadAudioLanguage = DownloadAudioLanguage { Locale.getDefault().language }

    /**
     * MD3 AC2's packaged engine proof (11 Smoke mode step 6): the engine build's binding
     * for the graph's `engineSmokeProof`. It stays inert until the packaged smoke arms
     * `neutrodyne.smoke.engineReplay` — nothing else calls it. The application services
     * arrive as lambdas so the proof's media phase stays a fixture harness boundary: the
     * controllers keep their own ports, never a database or socket of their own.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun engineSmokeProof(
        engineResources: EngineResourceDir,
        runtime: PythonRuntimeLocator,
        paths: DesktopEngineStorePaths,
        store: EngineStore,
        identity: CompileMarker,
        transport: YtxTransport,
        streams: YouTubeStreamResolver,
        capabilities: YouTubeCapabilitiesSource,
        context: () -> YtxContext,
        clock: Clock,
        databaseOpener: DatabaseOpener,
        playbackController: PlaybackController,
        playbackStateSource: PlaybackStateSource,
        downloadController: () -> DownloadController,
        localMediaIndex: LocalMediaIndex,
    ): EngineSmokeProof? =
        PackagedEngineProof(
            engineResources,
            runtime,
            paths,
            store,
            identity,
            transport,
            streams,
            capabilities,
            context,
            clock,
            database = { databaseOpener.requireDatabase() },
            playbackController = { playbackController },
            playbackStateSource = { playbackStateSource },
            downloadController = downloadController,
            localMediaIndex = { localMediaIndex },
        )

    @Provides
    @IntoSet
    fun statusInitializer(store: EngineStore): AppInitializer = DesktopEngineStatusInitializer(store)

    @Provides
    @IntoSet
    fun prepareInitializer(
        store: EngineStore,
        transport: YtxTransport,
        settings: EngineSettings,
        preflight: EnginePlatformPreflight,
        context: () -> YtxContext,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): AppInitializer =
        DesktopEnginePreparationObserver(
            store,
            transport,
            settings,
            preflight,
            { YtxControlRequest(context()) },
            clock,
            scope,
        )

    private fun rootDir(): () -> File? = { System.getProperty("neutrodyne.rootDir")?.let(::File) }

    private const val BUNDLED_VERSION = "2026.08.19"
}

/** Metro cannot inject `() -> File?`; this named holder carries the resources resolver lazily. */
class EngineResourceDir(
    private val resolve: () -> File?,
) {
    val dir: File? get() = resolve()
}

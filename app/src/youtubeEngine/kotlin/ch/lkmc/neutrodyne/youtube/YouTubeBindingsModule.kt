// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.app.Application
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.download.api.DownloadAudioLanguage
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
import ch.lkmc.neutrodyne.youtube.ytdlp.AndroidDefaultNetworkReset
import ch.lkmc.neutrodyne.youtube.ytdlp.AndroidEngineStorePaths
import ch.lkmc.neutrodyne.youtube.ytdlp.AndroidIpFamilyHints
import ch.lkmc.neutrodyne.youtube.ytdlp.BinderYtxTransport
import ch.lkmc.neutrodyne.youtube.ytdlp.DataStoreEngineSettings
import ch.lkmc.neutrodyne.youtube.ytdlp.EnginePrepareInitializer
import ch.lkmc.neutrodyne.youtube.ytdlp.EnginePrepareWorker
import ch.lkmc.neutrodyne.youtube.ytdlp.EngineStatusInitializer
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The Android shell owns every public YouTube binding. The desktop remains external until MD3. */
@BindingContainer
object YouTubeBindingsModule {
    @Provides
    @SingleIn(AppScope::class)
    fun identity(build: BuildInfo): CompileMarker = CompileMarker(build.versionCode, BUNDLED_VERSION, "3.14")

    @Provides
    @SingleIn(AppScope::class)
    fun paths(app: Application): AndroidEngineStorePaths = AndroidEngineStorePaths(app)

    @Provides
    @SingleIn(AppScope::class)
    fun store(
        paths: AndroidEngineStorePaths,
        identity: CompileMarker,
        clock: Clock,
    ): EngineStore =
        BundledEngineStore(
            paths,
            identity.appVersionCode,
            clock,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun settings(
        portable: SettingsStore,
        device: DeviceSettingsStore,
        identity: CompileMarker,
        @ApplicationScope scope: CoroutineScope,
    ): EngineSettings = DataStoreEngineSettings(portable, device, identity, scope)

    @Provides
    @SingleIn(AppScope::class)
    fun preflight(build: BuildInfo): EnginePlatformPreflight =
        object : EnginePlatformPreflight {
            override fun limitation(): PlatformLimitation? =
                when {
                    !build.youTubeEngineBundled -> PlatformLimitation.NO_ENGINE

                    !Process.is64Bit() -> PlatformLimitation.PROCESS_32_BIT

                    Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" &&
                        Build.VERSION.SDK_INT < 28 -> PlatformLimitation.X86_64_API_26_27

                    else -> null
                }
        }

    @Provides
    @SingleIn(AppScope::class)
    fun hints(hints: DnsFamilyHints): IpFamilyHints = AndroidIpFamilyHints(hints)

    @Provides
    fun context(
        app: Application,
        agent: UserAgentProvider,
        hints: IpFamilyHints,
    ): YtxContext {
        val locale = app.resources.configuration.locales[0]
        return YtxContext(locale.language, locale.country.ifEmpty { "US" }, agent.value, hints.current()?.name)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun transport(
        app: Application,
        store: EngineStore,
        identity: CompileMarker,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        context: () -> YtxContext,
        settings: EngineSettings,
    ): YtxTransport = BinderYtxTransport(app, store, identity, clock, scope, YtxControlRequest(context()), settings)

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
        @ApplicationScope scope: CoroutineScope,
    ): YtDlpEngine = YtDlpEngine(transport, store, settings, preflight, identity, health, clock, scope)

    @Provides fun capabilities(engine: YtDlpEngine): YouTubeCapabilitiesSource = engine

    @Provides fun engineControl(engine: YtDlpEngine): YouTubeEngine = engine

    @Provides
    @SingleIn(AppScope::class)
    fun client(
        transport: YtxTransport,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): YtDlpClient =
        YtDlpClient(
            transport,
            clock,
            scope,
        )

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
        app: Application,
    ): YouTubeChannelResolver =
        DefaultYouTubeChannelResolver(
            clients,
            reader,
            extractor,
        ) {
            app.resources.configuration.locales[0]
                .toLanguageTag()
        }

    @Provides fun audioLanguage(app: Application): DownloadAudioLanguage =
        DownloadAudioLanguage {
            app.resources.configuration.locales[0]
                .toLanguageTag()
        }

    @Provides @IntoSet
    fun statusInitializer(store: EngineStore): AppInitializer = EngineStatusInitializer(store)

    @Provides @IntoSet
    internal fun prepareInitializer(
        store: EngineStore,
        app: Application,
        settings: EngineSettings,
        @ApplicationScope scope: CoroutineScope,
    ): AppInitializer = EnginePreparationObserver(app, store, settings, scope)

    @Provides @IntoSet
    internal fun networkInitializer(
        app: Application,
        streams: YouTubeStreamResolver,
    ): AppInitializer = YouTubeNetworkInitializer(app, streams)

    @Provides @IntoMap
    @WorkerKey(EnginePrepareWorker::class)
    fun prepareWorker(
        store: EngineStore,
        transport: YtxTransport,
        settings: EngineSettings,
        preflight: EnginePlatformPreflight,
        context: () -> YtxContext,
    ): WorkerCreator =
        {
            app,
            params,
            ->
            EnginePrepareWorker(app, params, store, transport, settings, preflight, YtxControlRequest(context()))
        }

    @Provides @IntoSet
    internal fun breakerNotifier(
        app: Application,
        health: YouTubeHealth,
        @ApplicationScope scope: CoroutineScope,
    ): AppInitializer = YouTubeBreakerNotifier(app, health, scope)

    private const val BUNDLED_VERSION = "2026.08.19"
}

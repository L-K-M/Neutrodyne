// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.artwork.NeutrodyneImageLoaderFactory
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.LevelMutableLogSink
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.EmbeddedChapterReader
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.notifications.DesktopBannerNotifier
import ch.lkmc.neutrodyne.desktop.system.OsDesktopNotifier
import ch.lkmc.neutrodyne.desktop.window.WindowIcons
import ch.lkmc.neutrodyne.desktop.youtube.DesktopYouTubeBindingsModule
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.LocalMediaChanges
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop shell's composition root (11 DesktopAppGraph, 01 Dependency injection). It
 * contributes the desktop implementations of the shared interfaces and nothing Android has.
 *
 * `AppDirs`, `BuildInfo` and the shell's [DesktopCrashReporter] come in through the factory (the
 * crash reporter is constructed by `MainKt` before the graph so 11's start-up order — logging,
 * crash handler, `session.json` before the graph — holds; 01's sketch is amended accordingly,
 * 2026-10-06). M1a adds the database plumbing: [databaseOpener] drives the open at initializer
 * band 100 and the window's start-up gate, and [jobRunner] serves the shutdown's
 * `stop(grace = 3 s)` (11 Shutdown step 2). The media/OS bindings of `:desktop:system` join with
 * their milestones.
 */
@DependencyGraph(
    AppScope::class,
    bindingContainers = [DesktopYouTubeBindingsModule::class, ch.lkmc.neutrodyne.sync.impl.SyncBindings::class],
)
interface DesktopAppGraph {
    /** 10's sync application service (Settings › Sync, the shell banners). */
    val syncController: ch.lkmc.neutrodyne.sync.api.SyncController

    /** The link state machine; `null` while no link runs. */
    val linkFlow: ch.lkmc.neutrodyne.sync.impl.LinkFlowImpl

    /**
     * 10 Scheduling on the desktop's window-refocus trigger: the window's focus listener calls
     * it; the binding resolves the lane lazily so an early focus never blocks on the open.
     */
    val syncFocusPoke: ch.lkmc.neutrodyne.sync.impl.SyncFocusPoke

    val dirs: AppDirs

    val buildInfo: BuildInfo

    @ApplicationScope
    val appScope: CoroutineScope

    val initializers: Set<AppInitializer>

    val desktopPlaybackController: ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController

    val playbackController: PlaybackController
    val playbackStateSource: PlaybackStateSource
    val playbackMaintenance: PlaybackMaintenance
    val localMediaIndex: LocalMediaIndex
    val localMediaChanges: LocalMediaChanges
    val downloadController: Provider<DownloadController>
    val autoDownloadPlanner: Provider<AutoDownloadPlanner>
    val cleanupPlanner: Provider<CleanupPlanner>
    val downloadMover: Provider<DownloadMover>
    val downloadProgress: Provider<DownloadProgressSource>
    val youtubeCapabilities: YouTubeCapabilitiesSource
    val youtubeStreamResolver: YouTubeStreamResolver

    /** Database-backed; resolve only after the database open gate (M9a health binding). */
    val youTubeHealth: YouTubeHealth

    /** Download-time container chapters (06 Chapters): the desktop `EmbeddedChapterReader`. */
    val embeddedChapterReader: Provider<EmbeddedChapterReader?>

    /** Resolve database-backed playback ports only after the database open gate. */
    val queueRepository: Provider<QueueRepository>
    val playContextResolver: Provider<PlayContextResolver>
    val playSessionStore: Provider<PlaySessionStore>
    val positionStore: Provider<PositionStore>
    val playbackMediaLookup: Provider<PlaybackMediaLookup>

    /** Every feature's navigation entries (01 Feature entry installers). */
    val entryInstallers: Set<EntryProviderInstaller>

    /** The shell's shared bindings (the M0b graph test resolves these; 01 Graph tests). */
    val settingsRepository: SettingsRepository

    /** The user-facing refresh port (11 Menus' File › Refresh, Ctrl/Cmd+R / F5). */
    val refreshController: ch.lkmc.neutrodyne.core.domain.RefreshController

    val appUpdateChecker: AppUpdateChecker
    val updateNotices: UpdateNotices

    /** Discovery bindings remain safe to resolve before the database startup gate. */
    val searchRepository: SearchRepository
    val autodiscovery: Autodiscovery

    val networkMonitor: NetworkMonitor

    val crashReporter: CrashReporter

    /**
     * MD2's OS integration: the per-OS media session (or the documented unavailable one),
     * the `PowerMonitor` port's `OsPowerMonitor`, the idle-sleep inhibitor, and the surface
     * wiring the shell starts at band 250 and closes on shutdown.
     */
    val systemMediaSession: ch.lkmc.neutrodyne.desktop.system.SystemMediaSession

    val idleSleepInhibitor: ch.lkmc.neutrodyne.desktop.system.IdleSleepInhibitor

    val osSurfaces: ch.lkmc.neutrodyne.desktop.os.DesktopOsSurfaces

    /** The OS surfaces' late-bound window hooks (MPRIS `Raise`/`Quit`). */
    val shellActions: ch.lkmc.neutrodyne.desktop.di.DesktopShellActions

    /** The window maps `openState` onto the start-up gate (01 Splash and start-up gate). */
    val databaseOpener: DatabaseOpener

    /**
     * M1b: the secrets-file store — graph tests prove `CredentialLookup` resolves to it, so
     * `AuthInterceptor` can never be left reading `None` in the app process.
     */
    val secretStore: SecretStore

    /** What `AuthInterceptor` injects: the same singleton as [secretStore]. */
    val credentialLookup: CredentialLookup

    /**
     * M3 C3a: resolving it proves the desktop `ExportDestination`/`FileExportDestination`
     * binding is real — the repository is `internal`, so the graph test asserts the resolved
     * class name instead.
     */
    val exportRepository: ExportRepository

    /** M3 C3a: D34's no-op scheduler — the desktop has no Auto Backup. */
    val snapshotScheduler: SnapshotScheduler

    /** M3 C3b: the desktop `import-backup` lane's session host. */
    val importRepository: ImportRepository

    /** M3 C3b: backup writes and restore claims through `LaneImportWorkScheduler`. */
    val backupRepository: BackupRepository

    /**
     * The bound [DesktopNotifier] — MD2's `OsDesktopNotifier` (`:desktop:system`) over the
     * in-app banner fallback ([DesktopBannerNotifier]): every post reaches the OS first and
     * the banners whenever the OS channel cannot deliver (11 Notifications). The window
     * feeds [DesktopBannerNotifier.userMessages] into `RootSlots.userMessages` and attaches
     * the route dispatch; OS clicks arrive through [osNotifier].
     */
    val bannerNotifier: DesktopBannerNotifier

    /** The OS notification half of the bound [DesktopNotifier] (clicks, diagnostics). */
    val osNotifier: OsDesktopNotifier

    /** The D-Bus portal for the Linux chooser and "Show in Files"; `null` off Linux. */
    val linuxDesktopPortal: ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal?

    /** The bound notification port: the OS notifier over the banner fallback. */
    val desktopNotifier: DesktopNotifier

    /**
     * MD2: the desktop new-episode poster (03) — grouping by group, the unseen set cleared
     * by window focus and notification clicks; replaces M2's temporary no-op binding.
     */
    val newEpisodePoster: NewEpisodePoster

    /**
     * The runner the shutdown stops (11 Shutdown step 2). Resolving pulls the lane chain through
     * [provideDatabase], which blocks until the band-100 open finishes — held behind a lambda at
     * the call site so an early quit never pays it, and by then the runner is the started
     * singleton anyway.
     */
    val jobRunner: DesktopJobRunner

    /**
     * The diagnostics ring sink (09). The shell installs its sinks before the graph exists,
     * then re-installs with this singleton appended — only post-graph lines reach it.
     */
    val ringBufferLogSink: ch.lkmc.neutrodyne.core.common.RingBufferLogSink

    /**
     * `metroViewModel()`/`assistedMetroViewModel()` resolve through `LocalMetroViewModelFactory`,
     * provided at the window root (01 Feature entry installers) — the Library grid is the first
     * VM-backed entry.
     */
    val metroViewModelFactory: MetroViewModelFactory

    /** The Coil `SingletonImageLoader.Factory` the shell installs once (08 Coil ImageLoader). */
    val imageLoaderFactory: NeutrodyneImageLoaderFactory

    @Binds
    val DesktopCrashReporter.asCrashReporter: CrashReporter

    @Binds
    val DesktopCrashReporter.asCrashContext: CrashContext

    /**
     * The `DesktopNotifier` binding: MD2's `OsDesktopNotifier` over the in-app banner
     * fallback — 11 Notifications' fallback column. A post the OS channel cannot deliver
     * shows the banner instead, so the owners' notifiers stay source-agnostic.
     */
    @Binds
    val OsDesktopNotifier.asDesktopNotifier: DesktopNotifier

    /**
     * 03's desktop new-episode poster. Bound here, not contributed, so the journey tests'
     * graphs can keep providing their fake without a duplicate-binding clash.
     */
    @Binds
    val ch.lkmc.neutrodyne.desktop.notifications.DesktopNewEpisodePoster.asNewEpisodePoster: NewEpisodePoster

    @Provides
    @SingleIn(AppScope::class)
    fun osDesktopNotifier(
        buildInfo: BuildInfo,
        banner: DesktopBannerNotifier,
    ): OsDesktopNotifier =
        OsDesktopNotifier.create(
            os = buildInfo.desktop?.os,
            fallback = banner,
            trayIcon = { WindowIcons.trayImage() },
        )

    /**
     * The one database accessor (02 Error handling and recovery): blocks a background caller until
     * the open finishes, throws on the EDT before that — callers hold this lazily.
     */
    @Provides
    fun provideDatabase(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    /** "Detailed log for 24 hours" (09) lowers the ring and the file sink alike. */
    @Provides
    @IntoSet
    fun provideRingLevelMutable(sink: ch.lkmc.neutrodyne.core.common.RingBufferLogSink): LevelMutableLogSink = sink

    /** The shell's pre-graph [RollingFileSink], contributed into the same set by its interface. */
    @Provides
    @IntoSet
    fun provideFileLevelMutable(fileSink: LevelMutableLogSink): LevelMutableLogSink = fileSink

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides dirs: AppDirs,
            @Provides buildInfo: BuildInfo,
            @Provides crashReporter: DesktopCrashReporter,
            @Provides fileSink: LevelMutableLogSink,
        ): DesktopAppGraph
    }
}

/** Builds the graph from the shell's pre-graph instances (single construction path, also smoke's). */
internal fun createDesktopGraph(
    dirs: AppDirs,
    buildInfo: BuildInfo,
    crashReporter: DesktopCrashReporter,
    fileSink: LevelMutableLogSink,
): DesktopAppGraph =
    createGraphFactory<DesktopAppGraph.Factory>().create(
        dirs = dirs,
        buildInfo = buildInfo,
        crashReporter = crashReporter,
        fileSink = fileSink,
    )

/** Tests and smoke runs have no pre-graph file sink; the verbose toggle simply has one member less. */
internal fun createDesktopGraph(
    dirs: AppDirs,
    buildInfo: BuildInfo,
    crashReporter: DesktopCrashReporter,
): DesktopAppGraph = createDesktopGraph(dirs, buildInfo, crashReporter, NoopLevelMutableLogSink)

private object NoopLevelMutableLogSink : LevelMutableLogSink {
    override var minLevel: LogLevel = LogLevel.INFO

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) = Unit
}

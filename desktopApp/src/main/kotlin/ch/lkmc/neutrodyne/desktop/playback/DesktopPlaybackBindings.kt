// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.desktop.DesktopEpisodeSourceResolver
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackRuntime
import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.FfAudioEngine
import ch.lkmc.neutrodyne.playback.engine.SpanCache
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Lazy runtime keeps graph construction and pre-database recovery screens storage-free. */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopPlaybackBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun desktopController(
        @ApplicationScope scope: CoroutineScope,
        clock: Clock,
        runtime: Provider<DesktopPlaybackRuntime>,
    ): DesktopPlaybackController = DesktopPlaybackController(scope, clock, { runtime() })

    @Provides fun controller(value: DesktopPlaybackController): PlaybackController = value

    @Provides fun state(value: DesktopPlaybackController): PlaybackStateSource = value

    @Provides fun maintenance(value: DesktopPlaybackController): PlaybackMaintenance = value

    // M6 supplies the completed-file index. No files are produced by this milestone.
    @Provides fun localMediaIndex(): LocalMediaIndex = LocalMediaIndex { null }

    @Provides
    fun runtime(
        starter: PlayStarter,
        planner: QueueWindowPlanner,
        saver: PositionSaver,
        settings: EffectivePlaybackSettings,
        sessions: PlaySessionStore,
        queue: QueueRepository,
        media: PlaybackMediaLookup,
        positions: PositionStore,
        capabilities: YouTubeCapabilitiesSource,
        resolver: DesktopEpisodeSourceResolver,
        cache: SpanCache,
        engine: AudioEngine,
    ): DesktopPlaybackRuntime =
        DesktopPlaybackRuntime(
            starter,
            planner,
            saver,
            settings,
            sessions,
            queue,
            media,
            positions,
            capabilities,
            resolver,
            cache,
            engine,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun resolver(
        localMedia: LocalMediaIndex,
        capabilities: YouTubeCapabilitiesSource,
        youtube: YouTubeStreamResolver,
        clock: Clock,
    ): DesktopEpisodeSourceResolver = DesktopEpisodeSourceResolver(localMedia, capabilities, youtube, clock)

    @Provides
    @SingleIn(AppScope::class)
    fun cache(dirs: AppDirs): SpanCache = SpanCache(dirs.cache.resolve("media"))

    @Provides
    fun engine(
        resolver: DesktopEpisodeSourceResolver,
        clients: NetworkClients,
        cache: SpanCache,
    ): AudioEngine {
        val paths = DesktopEnginePaths.current()
        return FfAudioEngine(paths.ffmpeg, paths.ndmedia, resolver, clients, cache)
    }
}

/** Band 300 restores the mini player after database open without opening an audio device. */
@ContributesIntoSet(AppScope::class)
@Inject
class DesktopPlaybackInitializer(
    private val opener: DatabaseOpener,
    private val controller: DesktopPlaybackController,
) : AppInitializer {
    override val order: Int = 300

    override suspend fun run() {
        opener.awaitOpen()
        controller.initialize()
    }
}

internal data class DesktopEnginePaths(
    val ffmpeg: Path,
    val ndmedia: Path,
) {
    companion object {
        fun current(): DesktopEnginePaths {
            val developmentRoot = System.getProperty("neutrodyne.rootDir")
            if (developmentRoot == null) {
                System.getProperty("compose.application.resources.dir")?.let {
                    val native = Path.of(it).resolve("native")
                    return DesktopEnginePaths(native.resolve("ffmpeg"), native.resolve("ndmedia"))
                }
            }
            // Gradle run uses desktopApp as its working directory; CI and tests set rootDir.
            val root = Path.of(System.getProperty("neutrodyne.rootDir", "..")).toAbsolutePath().normalize()
            val target =
                when {
                    System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
                    System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
                    System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
                    else -> "linux-x64"
                }
            val native = root.resolve("playback/native/build")
            return DesktopEnginePaths(native.resolve("ffmpeg/$target"), native.resolve("ndmedia/$target"))
        }
    }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.download.api.DownloadAudioLanguage
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.AbsentYouTubeEngine
import ch.lkmc.neutrodyne.youtube.impl.DefaultYouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.impl.ExternalOnlyYouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.NoExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.impl.NoOpYouTubeEnricher
import ch.lkmc.neutrodyne.youtube.impl.StaticYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.impl.UnsupportedYouTubeChannelSearch
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Shell-owned bindings: all M8 builds remain external until M9a / MD3. */
@BindingContainer
object YouTubeBindingsModule {
    private val external = StaticYouTubeCapabilitiesSource(ch.lkmc.neutrodyne.core.model.ExternalReason.NOT_IN_THIS_APK)
    private val control = AbsentYouTubeEngine()
    private val streams = ExternalOnlyYouTubeStreamResolver()
    private val enrichment = NoOpYouTubeEnricher()
    private val search = UnsupportedYouTubeChannelSearch()
    private val lookup = NoExtractorChannelLookup()

    @Provides fun capabilities(): YouTubeCapabilitiesSource = external

    @Provides
    fun audioLanguage(application: Application): DownloadAudioLanguage =
        DownloadAudioLanguage {
            application.resources.configuration.locales[0]
                .language
        }

    @Provides fun engine(): YouTubeEngine = control

    @Provides fun streamResolver(): YouTubeStreamResolver = streams

    @Provides fun enricher(): YouTubeEnricher = enrichment

    @Provides fun channelSearch(): YouTubeChannelSearch = search

    @Provides fun channelLookup(): ExtractorChannelLookup = lookup

    @Provides @IntoSet
    fun cleanup(app: Application): AppInitializer = EngineFilesCleanup(app)

    @Provides
    @SingleIn(AppScope::class)
    fun channelResolver(
        clients: NeutrodyneHttpClients,
        reader: YouTubeFeedMetadataReader,
        extractor: ExtractorChannelLookup,
        application: Application,
    ): YouTubeChannelResolver =
        DefaultYouTubeChannelResolver(clients, reader, extractor) {
            application.resources.configuration.locales[0]
                .toLanguageTag()
        }
}

private class EngineFilesCleanup(
    private val app: Application,
) : AppInitializer {
    override val order = 300

    override suspend fun run() =
        withContext(Dispatchers.IO) {
            for (root in listOf(app.noBackupFilesDir.resolve("ytdlp"), app.cacheDir.resolve("yt-dlp"))) {
                if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                // Walk without following links, including a link at the engine root itself.
                Files.walkFileTree(
                    root.toPath(),
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            path: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            check(path.toFile().setWritable(true, true)) { "Cannot clean the old engine directory" }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            path: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.delete(path)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            path: Path,
                            failure: java.io.IOException?,
                        ): FileVisitResult {
                            if (failure != null) throw failure
                            Files.delete(path)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
        }
}

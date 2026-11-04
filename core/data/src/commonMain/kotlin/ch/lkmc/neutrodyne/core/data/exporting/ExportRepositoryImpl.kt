// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ExportFormat
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import ch.lkmc.neutrodyne.feeds.youtube.NewPipeSubscriptions
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * `ExportRepository` (05 OPML export): [prepare] writes the document [OpmlExporter] builds to
 * `<cache>/export/{fileName}` and returns the counts the R1.9 warning needs; nothing reaches a
 * destination here (AC10 — the cache file is app-private, a Cancel only ever leaves it to
 * [ExportFilesCleaner]). [saveTo] copies the prepared file out through the platform
 * [ExportDestination].
 *
 * Not contributed to the graph: [ExportDestination] has no binding on either platform — its
 * `SafExportDestination`/`FileExportDestination` implementations land with M3 rows C1/C3, and
 * `@ContributesBinding` joins them then. `@Inject` stays so the class is graph-constructible.
 */
@SingleIn(AppScope::class)
@Inject
internal class ExportRepositoryImpl(
    private val exporter: OpmlExporter,
    private val destination: ExportDestination,
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    private val buildInfo: BuildInfo,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : ExportRepository {
    private val exportDir: Path = exportDirOf(storagePaths)

    override suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError> {
        require(request.format != ExportFormat.NEWPIPE_JSON || request.groupId == null) {
            "NewPipe export is full-library only"
        }
        val newPipe = request.format == ExportFormat.NEWPIPE_JSON

        // A deleted group is the one failure the sealed ExportError has no case for; Io is the
        // closest — the file could not be produced.
        val built =
            exporter.build(if (newPipe) request.copy(includeYouTube = true, includePasswords = false) else request)
                ?: return Outcome.Failure(ExportError.Io)
        val fileName =
            if (newPipe) {
                built.fileName.replace("neutrodyne-subscriptions-", "neutrodyne-youtube-").removeSuffix(".opml") +
                    ".json"
            } else {
                built.fileName
            }
        val channels =
            if (newPipe) {
                built.document.feeds
                    .filter {
                        it.youtube
                    }.map {
                        NewPipeSubscriptions.Channel(it.xmlUrl.substringAfter("channel_id="), it.displayTitle.orEmpty())
                    }
            } else {
                emptyList()
            }
        val path = preparedFile(fileName) ?: return Outcome.Failure(ExportError.Io)
        val layout =
            when (request.layout) {
                ExportLayout.GROUPED -> OpmlLayout.GROUPED
                ExportLayout.FLAT -> OpmlLayout.FLAT
            }
        val written =
            withContext(io) {
                suspendRunCatching {
                    fileSystem.createDirectories(exportDir)
                    // A same-name predecessor (a same-day re-export) is replaced — 05's
                    // "each export also deletes older files with the same name".
                    fileSystem.write(path) {
                        if (newPipe) {
                            NewPipeSubscriptions.write(this, buildInfo.versionName, buildInfo.versionCode, channels)
                        } else {
                            OpmlWriter.write(this, built.document, layout)
                        }
                    }
                    destination.shareUri(fileName)
                }
            }
        if (written.isFailure) return Outcome.Failure(ExportError.Io)
        return Outcome.Success(
            PreparedExport(
                fileName = fileName,
                mimeType = if (newPipe) "application/json" else OPML_MIME_TYPE,
                shareUri = written.getOrNull(),
                feeds = if (newPipe) channels.size else built.document.feeds.size,
                privateLinks = if (newPipe) 0 else built.privateLinks,
                passwords = if (newPipe) 0 else built.passwords,
            ),
        )
    }

    override suspend fun saveTo(
        export: PreparedExport,
        destinationUri: String,
    ): Outcome<Unit, ExportError> {
        // Resolve and stat on IO like prepare's write: callers sit on the UI dispatcher.
        val source =
            withContext(io) {
                preparedFile(export.fileName)?.takeIf { fileSystem.exists(it) }
            } ?: return Outcome.Failure(ExportError.Io)
        // A throw from the platform seam (provider quirks beyond its own Outcome cases) still
        // reports "couldn't write" instead of crashing — cancellation still propagates.
        return suspendRunCatching { destination.save(source, destinationUri) }
            .getOrNull() ?: Outcome.Failure(ExportError.Io)
    }

    /**
     * [fileName] resolved inside the export dir, `null` when it would escape — `prepare` mints
     * every name it returns, but the model type crosses layers and must never reach outside.
     * Okio's `Path.parent` is `.` (not `null`) for a lone segment, so the checks stay textual.
     */
    private fun preparedFile(fileName: String): Path? {
        if (fileName.isEmpty() || fileName == "." || fileName == "..") return null
        if ('/' in fileName || '\\' in fileName) return null
        val path = fileName.toPath()
        // Reject names `toPath` normalises: no minted name ever differs after parsing.
        if (path.name != fileName) return null
        return exportDir / path
    }

    private companion object {
        const val OPML_MIME_TYPE = "text/x-opml"
    }
}

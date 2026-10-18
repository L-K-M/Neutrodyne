// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.HostileInput
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.UnsupportedSource
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.ImportWarning
import ch.lkmc.neutrodyne.core.model.SourceGroup
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.importing.ImportDocument
import ch.lkmc.neutrodyne.feeds.importing.ImportDocumentWarning
import ch.lkmc.neutrodyne.feeds.importing.ImportDocuments
import ch.lkmc.neutrodyne.feeds.importing.ImportGroup
import ch.lkmc.neutrodyne.feeds.importing.ImportSourceSniffer
import ch.lkmc.neutrodyne.feeds.importing.SniffResult
import ch.lkmc.neutrodyne.feeds.importing.SniffedFormat
import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.IOException
import okio.Path

/**
 * The create half of `ImportRepository` (05 OPML import §1–§5): [create] acquires the payload
 * through [PayloadStore], sniffs, parses, classifies and persists the `PREVIEW` session plus its
 * items in one write transaction. Preview only — no `podcast`/`podcast_group` row is ever
 * written here (05: library rows appear at confirm, B5), and [createAdopted] is the
 * app-private-snapshot entry point (`files/backup/auto-snapshot.zip`, 05 §1 step 6).
 *
 * 05 §1 step 5's split: the copy runs in the caller's coroutine (its URI grant dies with it),
 * then sniff → insert runs as one `@ApplicationScope` job the caller only awaits — a cancelled
 * caller still leaves the session persisted for the resume banner.
 *
 * Not contributed to the graph yet: `OpmlReader`/`ArchiveReaderFactory`/`PayloadSource` get
 * their bindings with M3's platform rows, same as `exporting.ExportDestination`.
 */
@Inject
internal class ImportSessions(
    private val db: NeutrodyneDatabase,
    private val payloads: PayloadStore,
    private val classifier: ImportClassifier,
    private val opmlReader: OpmlReader,
    private val archives: ArchiveReaderFactory,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    suspend fun create(
        source: ImportSource,
        options: CreateImportOptions,
    ): Outcome<Long, ImportError> {
        val staged =
            when (val acquired = payloads.acquire(source)) {
                is Outcome.Failure -> return acquired
                is Outcome.Success -> acquired.value
            }
        return awaitStage(appScope.async(io) { stage(staged, source.displayName, options, adopted = false) }, staged)
    }

    /** [create] for an app-private snapshot (05 §1.6): marks the session `fromAndroidBackup`. */
    suspend fun createAdopted(
        path: Path,
        displayName: String?,
        options: CreateImportOptions,
    ): Outcome<Long, ImportError> {
        val staged =
            when (val adopted = payloads.adopt(path, displayName)) {
                is Outcome.Failure -> return adopted
                is Outcome.Success -> adopted.value
            }
        return awaitStage(appScope.async(io) { stage(staged, displayName, options, adopted = true) }, staged)
    }

    /**
     * The `create` contract is `Outcome`-only — a programming error inside the stage job (an
     * unmapped sniff tag, a serializer bug) lands as [ImportError.Storage], not a crash, and the
     * staged file is still swept. Cancellation is control flow and rethrows.
     */
    private suspend fun awaitStage(
        deferred: Deferred<Outcome<Long, ImportError>>,
        staged: PayloadStore.Staged,
    ): Outcome<Long, ImportError> =
        try {
            deferred.await()
        } catch (e: CancellationException) {
            // A cancelled *caller* leaves the application job owning the staged copy — it
            // either publishes it or sweeps it itself (05 §1 step 5). A cancelled *job*
            // (teardown, or a cancel landing inside stage/persist) leaves it to no one, so
            // sweep here. A committed session is safe either way: publish() already moved
            // its payload off the staged path.
            if (deferred.isCancelled) payloads.delete(staged.path)
            throw e
        } catch (_: Exception) {
            payloads.delete(staged.path)
            Outcome.Failure(ImportError.Storage)
        }

    /**
     * Sniff → parse → classify → persist of one staged payload. Failure paths delete [staged]'s
     * file — it is never referenced once this returns an error.
     */
    private suspend fun stage(
        staged: PayloadStore.Staged,
        fallbackName: String?,
        options: CreateImportOptions,
        adopted: Boolean,
    ): Outcome<Long, ImportError> {
        val sniffed =
            try {
                ImportSourceSniffer.sniff({ payloads.open(staged.path) }, staged.path, archives)
            } catch (_: Exception) {
                // Reading our own cache copy — a storage problem, not an unreadable source.
                return fail(staged, ImportError.Storage)
            }

        val format =
            when (sniffed) {
                is SniffResult.Unsupported -> {
                    return fail(staged, ImportError.Unsupported(unsupportedKind(sniffed.reason)))
                }

                is SniffResult.Hostile -> {
                    return fail(staged, ImportError.Hostile(hostileCap(sniffed.reason)))
                }

                is SniffResult.Recognized -> {
                    when (sniffed.format) {
                        SniffedFormat.OPML, SniffedFormat.NEUTRODYNE_BACKUP -> {
                            ImportFormat.valueOf(sniffed.format.name)
                        }

                        // M3 has OPML and backup; the YouTube formats' parsers land in M8 (05
                        // documents this interim mapping in a 2026-10-17 implementation note).
                        else -> {
                            return fail(staged, ImportError.Unsupported(UnsupportedSource.UNKNOWN))
                        }
                    }
                }
            }

        val document =
            when (format) {
                ImportFormat.OPML -> {
                    when (val read = readOpml(staged)) {
                        is Outcome.Failure -> return fail(staged, read.error)
                        is Outcome.Success -> read.value
                    }
                }

                else -> {
                    null
                }
            }

        if (document == null) {
            // A backup session carries no import_item rows — the restore preview inspects the
            // payload through `BackupRepository` (05 Full backup and restore).
            return persist(staged, fallbackName, format, options, adopted, document = null, classified = null)
        }
        if (document.entries.isEmpty()) return fail(staged, ImportError.Empty)

        val classified =
            try {
                classifier.classify(document.entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return fail(staged, ImportError.Storage)
            }
        return persist(staged, fallbackName, format, options, adopted, document, classified)
    }

    private fun fail(
        staged: PayloadStore.Staged,
        error: ImportError,
    ): Outcome.Failure<ImportError> {
        payloads.delete(staged.path)
        return Outcome.Failure(error)
    }

    /** The cascade re-opens the payload per pass — the same staged file every time. */
    private fun readOpml(staged: PayloadStore.Staged): Outcome<ImportDocument, ImportError> =
        try {
            when (val result = opmlReader.read({ payloads.open(staged.path) })) {
                is OpmlReadResult.Ok -> {
                    Outcome.Success(ImportDocuments.fromOpml(result.document))
                }

                is OpmlReadResult.Failed -> {
                    Outcome.Failure(
                        when (result.failure) {
                            OpmlFailure.ENTITY_DECLARED -> {
                                ImportError.Hostile(HostileInput.ENTITY)
                            }

                            OpmlFailure.TAG_TOO_LONG -> {
                                ImportError.Hostile(HostileInput.TAG_TOO_LONG)
                            }

                            OpmlFailure.TOO_DEEP -> {
                                ImportError.Hostile(HostileInput.TOO_DEEP)
                            }

                            OpmlFailure.TOO_MANY_FEEDS -> {
                                ImportError.Hostile(HostileInput.TOO_MANY_FEEDS)
                            }

                            OpmlFailure.TOO_MANY_OUTLINES -> {
                                ImportError.Hostile(HostileInput.TOO_MANY_OUTLINES)
                            }

                            // A document no pass could read holds no feed entries (05's failure
                            // table: "No feed outlines → Empty").
                            OpmlFailure.NO_OUTLINES, OpmlFailure.MALFORMED -> {
                                ImportError.Empty
                            }
                        },
                    )
                }
            }
        } catch (_: IOException) {
            // Reading our own staged copy — a storage problem, not an unreadable source.
            Outcome.Failure(ImportError.Storage)
        }

    /**
     * The one write transaction of `create` (02's caller-owns-transaction rule): session row,
     * then the payload rename and `payloadPath` update, then the item rows — a rollback anywhere
     * leaves no session and no renamed file.
     */
    private suspend fun persist(
        staged: PayloadStore.Staged,
        fallbackName: String?,
        format: ImportFormat,
        options: CreateImportOptions,
        adopted: Boolean,
        document: ImportDocument?,
        classified: ImportClassifier.Result?,
    ): Outcome<Long, ImportError> {
        val sessionOptions = sessionOptions(options, document, classified, adopted)
        val warnings = sessionWarnings(document, classified)
        var publishedPath: String? = null
        var insertedId: Long? = null
        return try {
            val sessionId =
                db.withWriteTransaction {
                    val id =
                        db.importDao().insertSession(
                            ImportSessionEntity(
                                createdAt = clock.now(),
                                sourceName = staged.displayName ?: fallbackName,
                                sourceFormat = format,
                                state = ImportState.PREVIEW,
                                recoveredBySalvage = document?.salvaged == true,
                                optionsJson = ImportOptionsJson.encode(sessionOptions),
                                warningsJson = warnings?.let(ImportWarningJson::encodeAll),
                            ),
                        )
                    insertedId = id
                    publishedPath = payloads.publish(staged, id)
                    db.importDao().setPayloadPath(id, publishedPath)
                    if (classified != null) {
                        db.importDao().insertItems(
                            classified.items.map { it.toEntity(id) },
                        )
                    }
                    id
                }
            Outcome.Success(sessionId)
        } catch (e: CancellationException) {
            // Room re-checks cancellation when the transaction block returns, so this catch
            // sees both sides of COMMIT: cancellation delivered mid-transaction rolled the
            // insert back and strands the staged temp plus any already-renamed payload;
            // cancellation delivered after COMMIT means the session row owns them and they
            // must stay (main's ingest fix, #153/#154). The row itself is the only reliable
            // witness — probe it non-cancellably rather than guessing from the call site.
            val id = insertedId
            val committed =
                id != null &&
                    withContext(NonCancellable) {
                        runCatching { db.importDao().sessionById(id) != null }
                            .getOrDefault(true)
                    }
            if (!committed) {
                payloads.delete(staged.path)
                publishedPath?.let { payloads.delete(payloads.payloadFile(it)) }
            }
            throw e
        } catch (_: Exception) {
            // The transaction rolled back — no session row. Sweep the temp and any file
            // publish() had renamed inside it so no orphan survives in the import cache.
            payloads.delete(staged.path)
            publishedPath?.let { payloads.delete(payloads.payloadFile(it)) }
            Outcome.Failure(ImportError.Storage)
        }
    }

    /**
     * The preview's stored options (05 §4): "Import OPML into this group" sets `targetGroupId`
     * and clears `importGroups`; proposals excluded by default pre-fill `excludedGroupKeys`;
     * the ordered proposal list itself — looks, excluded reasons and all — lives in
     * `sourceGroups` because empty folders have no `import_item` row to carry them.
     * `itemCustomTitles` rides the same session JSON — the parsed rename is parser metadata
     * `import_item` cannot hold — keyed by item ordinal so it survives the file dedupe:
     * a `DUPLICATE_IN_FILE` row keeps its own ordinal, the winner's entry keeps its own
     * rename under its own.
     */
    private fun sessionOptions(
        options: CreateImportOptions,
        document: ImportDocument?,
        classified: ImportClassifier.Result?,
        adopted: Boolean,
    ): ImportOptions {
        val sourceGroups = document?.groups?.mapNotNull(::sourceGroupOf).orEmpty()
        return ImportOptions(
            importGroups = options.targetGroupId == null,
            excludedGroupKeys =
                sourceGroups
                    .mapNotNullTo(HashSet()) { group ->
                        group.nameKey.takeIf { group.excludedReason != null }
                    },
            targetGroupId = options.targetGroupId,
            fromAndroidBackup = adopted,
            sourceGroups = sourceGroups,
            itemCustomTitles =
                classified
                    ?.items
                    ?.mapNotNull { item ->
                        item.customTitle?.let { item.ordinal to it }
                    }?.toMap()
                    .orEmpty(),
        )
    }

    private fun sourceGroupOf(group: ImportGroup): SourceGroup? {
        // The adapter truncated over-long proposal names already, so this cannot fail in
        // practice; a name that still normalises empty simply cannot be proposed.
        val valid = GroupNames.truncate(group.name) ?: return null
        return SourceGroup(
            nameKey = valid.nameKey,
            name = valid.name,
            colorArgb = group.colorArgb,
            iconKey = group.iconKey,
            excludedReason = group.excludedReason,
        )
    }

    /** `warningsJson`: the read's warnings, folding entry-level name truncation into the adapter's. */
    private fun sessionWarnings(
        document: ImportDocument?,
        classified: ImportClassifier.Result?,
    ): List<ImportWarning>? {
        if (document == null) return null
        val warnings =
            document.warnings
                .map { ImportWarning(code = it.code, count = it.count) }
                .toMutableList()
        val truncated = classified?.truncatedNames ?: 0
        if (truncated > 0) {
            val index = warnings.indexOfFirst { it.code == ImportDocumentWarning.TRUNCATED_NAMES }
            if (index >= 0) {
                warnings[index] = warnings[index].copy(count = warnings[index].count + truncated)
            } else {
                warnings += ImportWarning(code = ImportDocumentWarning.TRUNCATED_NAMES, count = truncated)
            }
        }
        return warnings.ifEmpty { null }
    }

    private fun ImportClassifier.ClassifiedItem.toEntity(sessionId: Long): ImportItemEntity =
        ImportItemEntity(
            sessionId = sessionId,
            ordinal = ordinal,
            title = title,
            originalUrl = originalUrl,
            normalizedUrl = normalizedUrl,
            kind = kind,
            groupNamesJson = groupNamesJson(groupNames),
            selected = selected,
            status = status,
            podcastId = podcastId,
            errorDetail = errorDetail,
        )

    private fun groupNamesJson(names: List<String>): String = itemJson.encodeToString(names)

    private fun unsupportedKind(reason: String): UnsupportedSource =
        UnsupportedSource.entries.firstOrNull { it.name.equals(reason, ignoreCase = true) }
            ?: UnsupportedSource.UNKNOWN

    /**
     * The sniffer's hostile tags are the enum's names lowercased (`zip_path` → `ZIP_PATH`); a
     * tag that does not map is an internal contract violation, so [first] reports it loudly.
     */
    private fun hostileCap(reason: String): HostileInput =
        HostileInput.entries.first { it.name.equals(reason, ignoreCase = true) }

    private companion object {
        val itemJson = Json
    }
}

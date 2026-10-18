// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadSource
import ch.lkmc.neutrodyne.core.data.importing.RestoreEnqueue
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.Timeout
import java.io.File

/** OPML corpus fixtures under `feeds/src/test/resources/opml/` (TestConventions sets rootDir). */
internal fun opmlFixture(name: String): File =
    File(
        checkNotNull(System.getProperty("neutrodyne.rootDir")) {
            "neutrodyne.rootDir is unset; run tests through Gradle"
        },
        "feeds/src/test/resources/opml/$name.opml",
    ).also { check(it.isFile) { "missing OPML fixture: ${it.absolutePath}" } }

/**
 * A `PayloadSource` whose registrations feed bytes, an endless stream or a failure. `openCount`
 * records whether the provider stream was ever opened — the size pre-check must not.
 */
internal class FakePayloadSource : PayloadSource {
    private val registrations = HashMap<String, Registered>()
    private val failures = HashMap<String, ImportError>()

    fun register(
        uri: String,
        bytes: ByteArray? = null,
        file: File? = null,
        source: Source? = null,
        displayName: String? = null,
        declaredSize: Long? = null,
    ): Registered {
        val registered =
            Registered(
                open = {
                    when {
                        source != null -> source
                        file != null -> FileSystem.SYSTEM.source(file.absolutePath.toPath())
                        else -> Buffer().write(requireNotNull(bytes))
                    }
                },
                displayName = displayName ?: file?.name,
                declaredSize = declaredSize ?: file?.length() ?: bytes?.size?.toLong(),
            )
        registrations[uri] = registered
        return registered
    }

    fun fail(
        uri: String,
        error: ImportError,
    ) {
        failures[uri] = error
    }

    override suspend fun open(uri: String): Outcome<PayloadSource.Opened, ImportError> {
        failures[uri]?.let { return Outcome.Failure(it) }
        val registered = registrations[uri] ?: return Outcome.Failure(ImportError.Unreadable)
        return Outcome.Success(
            PayloadSource.Opened(
                open = {
                    registered.openCount++
                    registered.open()
                },
                displayName = registered.displayName,
                declaredSize = registered.declaredSize,
            ),
        )
    }

    class Registered(
        val open: () -> Source,
        val displayName: String?,
        val declaredSize: Long?,
    ) {
        var openCount = 0
    }
}

/** Never ends — a provider that lies about size must be stopped by the counting copy. */
internal class EndlessSource : Source {
    var produced = 0L
    private val chunk = ByteArray(8 * 1024) { 0x20 }

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val write = minOf(byteCount, chunk.size.toLong()).toInt()
        sink.write(chunk, 0, write)
        produced += write
        return write.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {}
}

// --- Session seeding (repository / fix-up / cleaner tests) -----------------------------------------

/** An `import_item` row; the caller fills `sessionId` (or [seedImportSession] copies it in). */
internal fun importItem(
    ordinal: Int,
    url: String,
    normalizedUrl: String? = url,
    title: String? = "Item $ordinal",
    groups: List<String> = emptyList(),
    selected: Boolean = true,
    kind: ImportItemKind = ImportItemKind.RSS,
    status: ImportItemStatus = ImportItemStatus.PREVIEW,
    podcastId: Long? = null,
    errorDetail: String? = null,
): ImportItemEntity =
    ImportItemEntity(
        sessionId = 0,
        ordinal = ordinal,
        title = title,
        originalUrl = url,
        normalizedUrl = normalizedUrl,
        kind = kind,
        groupNamesJson = Json.encodeToString(groups),
        selected = selected,
        status = status,
        podcastId = podcastId,
        errorDetail = errorDetail,
    )

/** A session row plus its items in file order; returns the new session id. */
internal suspend fun NeutrodyneDatabase.seedImportSession(
    options: ImportOptions = ImportOptions(),
    format: ImportFormat = ImportFormat.OPML,
    state: ImportState = ImportState.PREVIEW,
    createdAt: Long = 0,
    finishedAt: Long? = null,
    payloadPath: String? = null,
    items: List<ImportItemEntity> = emptyList(),
): Long {
    val sessionId =
        importDao()
            .insertSession(
                ImportSessionEntity(
                    createdAt = createdAt,
                    finishedAt = finishedAt,
                    sourceFormat = format,
                    state = state,
                    payloadPath = payloadPath,
                    optionsJson = ImportOptionsJson.encode(options),
                ),
            )
    importDao().insertItems(items.map { it.copy(sessionId = sessionId) })
    return sessionId
}

/**
 * A `FileSystem` whose [onDelete] hook runs inside each `delete` — the point where a
 * racing restore request lands relative to a sweep's or a preview cancel's ownership
 * claim. The hook is synchronous: it must never wait on a second Room transaction while
 * the caller holds the writer lock — the second transaction cannot run then, so waiting
 * would deadlock the test instead of reproducing the race.
 */
internal class HookedDeleteFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    @Volatile
    var onDelete: (() -> Unit)? = null

    override fun delete(
        path: Path,
        mustExist: Boolean,
    ) {
        onDelete?.invoke()
        super.delete(path, mustExist)
    }
}

/**
 * The restore host's claim of a preview session (05 Full backup and restore →
 * `ImportOptions.restoreRequestedAt`): inside one write transaction the row must still
 * exist as `PREVIEW`, then the request is stamped — the shape a real
 * `BackupRepository.restore` has against the cleaner's and cancel's ownership rules.
 * [ImportError.SessionGone] when the row is gone or already past `PREVIEW`.
 */
internal suspend fun NeutrodyneDatabase.requestImportRestore(
    sessionId: Long,
    requestedAt: Long,
): Outcome<Unit, ImportError> =
    withWriteTransaction {
        val dao = importDao()
        val session =
            dao.sessionById(sessionId)
                ?: return@withWriteTransaction Outcome.Failure(ImportError.SessionGone)
        if (session.state != ImportState.PREVIEW) {
            return@withWriteTransaction Outcome.Failure(ImportError.SessionGone)
        }
        val options = session.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
        dao.setOptionsJson(
            sessionId,
            ImportOptionsJson.encode(options.copy(restoreRequestedAt = requestedAt)),
        )
        Outcome.Success(Unit)
    }

/** Records the schedule policy and cancellation of each host request. */
internal class FakeImportWorkScheduler : ImportWorkScheduler {
    val scheduled = mutableListOf<Pair<Long, ImportWorkPolicy>>()
    val cancelled = mutableListOf<Long>()

    /** `(sessionId, auto)` of each [scheduleRestore] call, in order. */
    val restores = mutableListOf<Pair<Long, Boolean>>()

    /** [hasRestoreWork]'s answer. */
    var restoreWork = false

    /** [scheduleRestore]'s acknowledgement. */
    var restoreAck = RestoreEnqueue.ENQUEUED

    var fail = false

    override suspend fun schedule(
        sessionId: Long,
        policy: ImportWorkPolicy,
    ) {
        if (fail) error("scheduler unavailable")
        scheduled += sessionId to policy
    }

    override suspend fun hasRestoreWork(): Boolean {
        if (fail) error("scheduler unavailable")
        return restoreWork
    }

    override suspend fun scheduleRestore(
        sessionId: Long,
        auto: Boolean,
    ): RestoreEnqueue {
        if (fail) error("scheduler unavailable")
        restores += sessionId to auto
        return restoreAck
    }

    override suspend fun cancel(sessionId: Long) {
        if (fail) error("scheduler unavailable")
        cancelled += sessionId
    }
}

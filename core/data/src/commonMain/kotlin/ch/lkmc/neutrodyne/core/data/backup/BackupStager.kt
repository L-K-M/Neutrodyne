// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.MAX_IMPORT_PAYLOAD_BYTES
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.ImportWarning
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupCodecException
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.Source
import okio.blackholeSink
import okio.buffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 05 Validation and preview (M3 B12a): the read half of a backup restore, before B12b's
 * `RestoreRunner`/`BackupRepositoryImpl` orchestration. [inspect] takes an already-acquired,
 * app-private archive `Path` (URI acquisition is B4/C's), verifies it end to end, and hands
 * back a [StagedBackup] — the fixed whitelist names materialised into an owned staging
 * directory, the decoded payloads, and the `BackupPreview` the restore dialog shows.
 *
 * The pipeline: the 50 MiB acquisition cap runs *before* the archive is opened
 * (`MAX_IMPORT_PAYLOAD_BYTES`, shared with the writer); the bound [ArchiveReaderFactory]
 * (`ZipGuard`) validates the central directory and meters every inflated byte under
 * `ArchiveCaps.BACKUP`; `manifest.json` is decoded once — `BackupCodec.decodeManifest` gates
 * `format` and `minReaderVersion` before the v1 decode, so a newer file reports
 * `NewerFormat`, never `Corrupt`. Then every declared entry is streamed exactly once
 * through a SHA-256 + byte check (one archive open, so the total-inflated cap cannot be
 * tripped twice by legitimate bytes) and written to the stage directory under its fixed
 * whitelist name — archive entry names never become filesystem paths. Non-whitelisted
 * archive entries are never opened; they are counted for the preview warning.
 *
 * Structure is not identity: after the hashes pass, `library.json` content is validated
 * field by field (N3 — a `key`, `feedUrl`, `link`, `artworkUrl`, alias or credential
 * origin carrying userinfo, or a credential origin outside the feed `scheme://host[:port]`
 * grammar — `podcastindex`/`sync:*` included — is never accepted from a backup). Malformed
 * identity/request-URL/source fields reject the archive as `Corrupt(library.json)` rather
 * than being silently rewritten — an unknown `source` must not become an arbitrary RSS
 * request, and opaque/versioned keys (`k`/`kv`, `podcastGuid`, `syncId`) are never
 * normalised away.
 *
 * Preview facts come from the decoded content — `manifest.counts` and
 * `includesCredentials` are informational claims a forged file could lie about — plus one
 * read transaction: the Replace dry run reuses `RestoreMerger`'s match ladders through
 * [RestoreMerger.matchedLocalIds] (no copy of the ladder, no writes), and the linked-server
 * host comes from `sync_state`. No Room, settings or secret write happens here.
 *
 * `stagingRoot` is a caller-owned private directory (the import/restore cache lane); the
 * owned staging child is created inside it and the source archive is never modified or
 * deleted — on every failure or cancellation only the owned directory is removed.
 */
@Inject
internal class BackupStager(
    private val db: NeutrodyneDatabase,
    private val readers: ArchiveReaderFactory,
    private val merger: RestoreMerger,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val codec = BackupCodec()

    /**
     * Validates [archive] (05 steps 1–4), materialises the payload entries under a fresh
     * directory inside [stagingRoot] and builds the preview (step 5). On any failure the
     * owned directory is deleted and [archive] is left untouched. The returned
     * [StagedBackup] must be [StagedBackup.close]d — or scoped with [StagedBackup.use].
     */
    suspend fun inspect(
        archive: Path,
        stagingRoot: Path,
    ): Outcome<StagedBackup, BackupError> {
        val size =
            withContext(io) { fileSystem.metadataOrNull(archive)?.size }
                ?: return Outcome.Failure(BackupError.Io)
        if (size > MAX_IMPORT_PAYLOAD_BYTES) return Outcome.Failure(BackupError.TooLarge)

        val dir = stagingRoot / "backup-${Uuid.random()}"
        val staged =
            try {
                withContext(io) { stageAndValidate(archive, dir) }
            } catch (cancel: CancellationException) {
                discard(dir)
                throw cancel
            } catch (rejected: StagerFailure) {
                discard(dir)
                return Outcome.Failure(rejected.error)
            } catch (e: IOException) {
                // Filesystem failures inside the pass (createDirectories, sink) map to Io.
                discard(dir)
                return Outcome.Failure(BackupError.Io)
            } catch (unexpected: Throwable) {
                discard(dir)
                throw unexpected
            }
        try {
            staged.preview = buildPreview(staged)
        } catch (cancel: CancellationException) {
            staged.close()
            throw cancel
        } catch (ioFail: IOException) {
            staged.close()
            return Outcome.Failure(BackupError.Io)
        } catch (t: Throwable) {
            staged.close()
            return Outcome.Failure(BackupError.Storage)
        }
        return Outcome.Success(staged)
    }

    /**
     * Open → manifest gate → declared-entry hash/byte verification + payload
     * materialisation → decode + field validation, all on [io] and before any database
     * read. Throws [StagerFailure] with the mapped [BackupError], [CancellationException]
     * on cancellation, or [IOException].
     */
    private fun stageAndValidate(
        archive: Path,
        dir: Path,
    ): StagedBackup {
        val reader =
            try {
                readers.open(archive, ArchiveCaps.BACKUP)
            } catch (e: ArchiveException) {
                throw when (e.kind) {
                    ArchiveException.Kind.HOSTILE -> StagerFailure(BackupError.TooLarge)
                    ArchiveException.Kind.CORRUPT -> StagerFailure(BackupError.Corrupt("zip"))
                }
            } catch (e: IOException) {
                throw StagerFailure(BackupError.Io)
            }

        return reader.use { archiveReader ->
            val names = archiveReader.entryNames.toHashSet()
            if (BackupCodec.ENTRY_MANIFEST !in names) throw StagerFailure(BackupError.NotABackup)

            val manifest =
                try {
                    archiveReader.open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it) }
                } catch (e: ArchiveException) {
                    throw entryError(BackupCodec.ENTRY_MANIFEST, e)
                } catch (e: BackupCodecException) {
                    throw codecError(BackupCodec.ENTRY_MANIFEST, e)
                }
            checkManifest(manifest)

            // Required names must be both declared (hash-verifiable) and present.
            for (name in REQUIRED_ENTRIES) {
                if (manifest.entries.none { it.name == name } || name !in names) {
                    throw StagerFailure(BackupError.Corrupt(name))
                }
            }
            val createdAtMs =
                try {
                    Instant.parse(manifest.createdAt).toEpochMilliseconds()
                } catch (e: IllegalArgumentException) {
                    throw StagerFailure(BackupError.Corrupt(BackupCodec.ENTRY_MANIFEST))
                }

            fileSystem.createDirectories(dir)
            val ignored = names - manifest.entries.mapTo(HashSet()) { it.name } - BackupCodec.ENTRY_MANIFEST
            for (info in manifest.entries) {
                verifyEntry(archiveReader, names, info, dir)
            }

            // Decode each staged payload and validate the library's identity fields.
            val library = decodeStaged(dir, BackupCodec.ENTRY_LIBRARY, codec::decodeLibrary)
            validate(library)
            val queue = decodeStaged(dir, BackupCodec.ENTRY_QUEUE, codec::decodeQueue)
            val settings = decodeStaged(dir, BackupCodec.ENTRY_SETTINGS, codec::decodeSettings)
            StagedBackup(
                fileSystem = fileSystem,
                io = io,
                codec = codec,
                dir = dir,
                manifest = manifest,
                createdAtMs = createdAtMs,
                ignoredEntries = ignored.size,
                library = library,
                queue = queue,
                settings = settings,
            )
        }
    }

    /** The manifest's `entries` sanity rules before a single payload byte is trusted. */
    private fun checkManifest(manifest: BackupManifest) {
        val seen = HashSet<String>()
        for (info in manifest.entries) {
            // A writer only ever declares payload/OPML names — never `manifest.json`,
            // whose own hash cannot appear inside it — and never a name outside the
            // fixed whitelist, which a reader must not open.
            if (info.name !in DECLARED_ENTRIES || !seen.add(info.name)) {
                throw StagerFailure(BackupError.Corrupt(BackupCodec.ENTRY_MANIFEST))
            }
        }
    }

    /**
     * Streams [info]'s entry once through the capped reader: the inflated byte count and
     * SHA-256 must equal the declaration (05 step 3). Payload names are written to
     * `dir/<name>` in the same pass; anything else (the OPML) is verified and discarded.
     */
    private fun verifyEntry(
        reader: ArchiveReader,
        names: Set<String>,
        info: EntryInfo,
        dir: Path,
    ) {
        if (info.name !in names) {
            throw StagerFailure(BackupError.Corrupt(info.name))
        }
        val stagePath = if (info.name in STAGED_ENTRIES) dir / info.name else null
        val hashing =
            try {
                HashingSource.sha256(reader.open(info.name))
            } catch (e: ArchiveException) {
                throw entryError(info.name, e)
            }
        val bytes =
            try {
                hashing.buffer().use { src ->
                    if (stagePath != null) {
                        fileSystem.sink(stagePath).buffer().use { sink -> src.readAll(sink) }
                    } else {
                        src.readAll(blackholeSink())
                    }
                }
            } catch (e: ArchiveException) {
                throw entryError(info.name, e)
            } catch (e: IOException) {
                throw StagerFailure(BackupError.Io)
            }
        if (bytes != info.bytes || hashing.hash.hex() != info.sha256) {
            throw StagerFailure(BackupError.Corrupt(info.name))
        }
    }

    /** A staged payload's decode; [BackupCodecException] maps to the entry's error. */
    private fun <T> decodeStaged(
        dir: Path,
        name: String,
        decode: (Source) -> T,
    ): T {
        try {
            return fileSystem.source(dir / name).use { decode(it) }
        } catch (e: BackupCodecException) {
            throw codecError(name, e)
        } catch (e: IOException) {
            throw StagerFailure(BackupError.Io)
        }
    }

    /**
     * N3's content validation (05 Validation): hash-verified bytes are still untrusted —
     * every identity and request-URL field is checked against the existing normalisers,
     * and a malformed one rejects the archive. Feed keys and aliases must already be in
     * `forIdentity` form (`forIdentity("https://$k")` is idempotent only for canonical
     * keys, which also makes userinfo-carrying spellings impossible); a `feedUrl` must be
     * a fetchable http(s) URL without userinfo; `source` must name a real `SourceType` —
     * never defaulted to RSS; a credential `origin` must be a canonical feed origin, so a
     * backup can never overwrite the Podcast Index key or a sync token.
     */
    private fun validate(library: LibraryV1) {
        for (podcast in library.podcasts) {
            if (!isFeedKey(podcast.key)) throw corruptLibrary()
            if (!isPlainUrl(podcast.feedUrl)) throw corruptLibrary()
            if (podcast.aliases.any { !isFeedKey(it) }) throw corruptLibrary()
            if (SourceType.entries.none { it.name == podcast.source }) throw corruptLibrary()
            if (podcast.link?.let { !isPlainUrl(it) } == true) throw corruptLibrary()
            if (podcast.artworkUrl?.let { !isPlainUrl(it) } == true) throw corruptLibrary()
            if (podcast.podcastGuid?.let { EpisodeKeys.canonicalGuid(it) != it } == true) {
                throw corruptLibrary()
            }
            if (podcast.guidAmbiguous?.any { EpisodeKeys.canonicalGuid(it) != it } == true) {
                throw corruptLibrary()
            }
            if (podcast.credentialOrigin?.let { UrlNormalizer.origin(it) != it } == true) {
                throw corruptLibrary()
            }
            if (podcast.syncId?.let { !isUuid(it) } == true) throw corruptLibrary()
        }
        for (group in library.groups) {
            if (!isUuid(group.uuid)) throw corruptLibrary()
        }
        for (credential in library.credentials) {
            if (UrlNormalizer.origin(credential.origin) != credential.origin) throw corruptLibrary()
        }
    }

    private fun corruptLibrary() = StagerFailure(BackupError.Corrupt(BackupCodec.ENTRY_LIBRARY))

    /** True when [value] is exactly its own `forIdentity` form under an https scheme. */
    private fun isFeedKey(value: String): Boolean =
        value.isNotEmpty() && UrlNormalizer.forIdentity("https://$value") == value

    /** A fetchable HTTP(S) URL without userinfo or padding the splitter would strip. */
    private fun isPlainUrl(url: String): Boolean =
        UrlNormalizer.splitUserInfo(url).first == url && UrlNormalizer.forIdentity(url) != null

    /**
     * Canonical UUIDs only: `parse` accepts mixed case but `toString` emits lowercase, and
     * Replace adopts a group uuid verbatim — a case-variant string would pass a match-by-
     * uuid write yet fail every later string-equality lookup, splitting the identity.
     */
    private fun isUuid(value: String): Boolean =
        try {
            Uuid.parse(value).toString() == value
        } catch (e: IllegalArgumentException) {
            false
        }

    /** 05 step 5: content-derived preview counts plus the Replace dry run in one read snapshot. */
    private suspend fun buildPreview(staged: StagedBackup): BackupPreview {
        var played = 0
        var inProgress = 0
        staged.forEachEpisodeLine { line ->
            if (line.pl != null) {
                played++
            } else if (line.st != null) {
                inProgress++
            }
        }

        val snapshot =
            db.withReadTransaction {
                val matched = merger.matchedLocalIds(staged.library())
                LocalFacts(
                    podcastsNotInBackup =
                        db.podcastDao().allForExport().count { it.id !in matched.podcastIds },
                    groupsNotInBackup =
                        db.groupDao().groupsInOrder().count { it.id !in matched.groupIds },
                    linkedServerHost =
                        db
                            .syncStateDao()
                            .get()
                            ?.takeIf { it.linkedAt != null }
                            ?.serverUrl
                            ?.let { UrlNormalizer.origin(it)?.substringAfter("://") },
                )
            }

        val warnings = ArrayList<ImportWarning>(2)
        if (staged.ignoredEntries > 0) warnings += ImportWarning(WARNING_IGNORED_ENTRIES, staged.ignoredEntries)
        if (staged.skippedLines > 0) warnings += ImportWarning(WARNING_SKIPPED_LINES, staged.skippedLines)

        return BackupPreview(
            createdAt = staged.createdAtMs,
            appVersionName = staged.manifest.app.versionName,
            automatic = staged.manifest.kind != BackupKind.MANUAL,
            podcasts = staged.library().podcasts.size,
            groups = staged.library().groups.size,
            played = played,
            inProgress = inProgress,
            upNext = staged.queue().upNext.size,
            settings = staged.settings().values.size,
            includesCredentials = staged.library().credentials.isNotEmpty(),
            localPodcastsNotInBackup = snapshot.podcastsNotInBackup,
            localGroupsNotInBackup = snapshot.groupsNotInBackup,
            warnings = warnings,
            linkedServerHost = snapshot.linkedServerHost,
            writtenOn = staged.manifest.app.platform,
        )
    }

    /** Deletes the owned staging directory; never the source archive. */
    private suspend fun discard(dir: Path) {
        withContext(NonCancellable + io) {
            suspendRunCatching { fileSystem.deleteRecursively(dir, mustExist = false) }
        }
    }

    private fun entryError(
        name: String,
        e: ArchiveException,
    ): StagerFailure =
        StagerFailure(
            if (e.kind == ArchiveException.Kind.HOSTILE) {
                BackupError.TooLarge
            } else {
                BackupError.Corrupt(name)
            },
        )

    private fun codecError(
        name: String,
        e: BackupCodecException,
    ): StagerFailure =
        when (e) {
            is BackupCodecException.NotABackup -> StagerFailure(BackupError.NotABackup)
            is BackupCodecException.NewerFormat -> StagerFailure(BackupError.NewerFormat(e.minReaderVersion))
            is BackupCodecException.TooLarge -> StagerFailure(BackupError.TooLarge)
            is BackupCodecException.Corrupt -> StagerFailure(BackupError.Corrupt(name))
        }

    private class LocalFacts(
        val podcastsNotInBackup: Int,
        val groupsNotInBackup: Int,
        val linkedServerHost: String?,
    )

    /** One validation failure's mapped [BackupError] inside the staging pass. */
    private class StagerFailure(
        val error: BackupError,
    ) : Exception()

    private companion object {
        /** Archive table's required-on-read names besides `manifest.json` itself. */
        val REQUIRED_ENTRIES =
            setOf(
                BackupCodec.ENTRY_LIBRARY,
                BackupCodec.ENTRY_EPISODES,
                BackupCodec.ENTRY_QUEUE,
                BackupCodec.ENTRY_SETTINGS,
            )

        /** Whitelist minus `manifest.json` — the names a manifest may declare. */
        val DECLARED_ENTRIES = BackupCodec.ENTRY_NAMES - BackupCodec.ENTRY_MANIFEST

        /** The payload names materialised into the staging directory. */
        val STAGED_ENTRIES = REQUIRED_ENTRIES

        const val WARNING_IGNORED_ENTRIES = "IGNORED_ENTRIES"
        const val WARNING_SKIPPED_LINES = "SKIPPED_LINES"
    }
}

/**
 * One verified, materialised backup (05 Validation): the `StagedLibrary` the mergers run
 * against plus the [preview] the dialog shows. `episodes.jsonl` reopens from the owned
 * staging file on every [forEachEpisodeLine] pass; [episodeLines] and [skippedLines]
 * retain that pass's codec counts (lines decoded, oversized/undecodable lines skipped) so
 * the preview and B12b's merge summary report what was actually read — never the
 * manifest's claims.
 *
 * [close] deletes only this staging directory on the IO dispatcher, even under caller
 * cancellation; the acquired source archive is out of reach here by construction — the
 * caller owns its lifetime.
 */
internal class StagedBackup(
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher,
    private val codec: BackupCodec,
    private val dir: Path,
    val manifest: BackupManifest,
    override val createdAtMs: Long,
    val ignoredEntries: Int,
    private val library: LibraryV1,
    private val queue: QueueV1,
    private val settings: SettingsV1,
) : StagedLibrary {
    /** The dialog's `BackupPreview`; set by `BackupStager.inspect` before return. */
    lateinit var preview: BackupPreview
        internal set

    /** Lines the last [forEachEpisodeLine] pass decoded. */
    var episodeLines: Int = 0
        private set

    /** Lines the last [forEachEpisodeLine] pass skipped (oversized or undecodable). */
    var skippedLines: Int = 0
        private set

    private var closed = false

    override suspend fun library(): LibraryV1 = library

    override suspend fun queue(): QueueV1 = queue

    override suspend fun settings(): SettingsV1 = settings

    override suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit) {
        var decoded = 0
        var skipped = 0
        // The staged file reopens per pass on the IO dispatcher; [block] runs inside that
        // context, so a suspending caller (the merger's write transactions) still leaves it.
        withContext(io) {
            val lines = codec.episodeLines(fileSystem.source(dir / BackupCodec.ENTRY_EPISODES))
            try {
                while (lines.hasNext()) {
                    block(lines.next())
                    decoded++
                }
            } finally {
                lines.close()
            }
            skipped = lines.skippedLines
        }
        episodeLines = decoded
        skippedLines = skipped
    }

    /** Deletes the owned staging directory; never the source archive. Idempotent. */
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable + io) {
            suspendRunCatching { fileSystem.deleteRecursively(dir, mustExist = false) }
        }
    }
}

/** Runs [block] with the staged backup, always closing it afterwards. */
internal suspend fun <T> StagedBackup.use(block: suspend (StagedBackup) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

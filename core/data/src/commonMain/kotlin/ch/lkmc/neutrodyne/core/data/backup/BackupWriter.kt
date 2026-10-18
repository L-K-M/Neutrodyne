// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.exporting.BuiltExport
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.MAX_IMPORT_PAYLOAD_BYTES
import ch.lkmc.neutrodyne.core.database.BackupDao
import ch.lkmc.neutrodyne.core.database.BackupEpisodeRow
import ch.lkmc.neutrodyne.core.database.BackupMemberRow
import ch.lkmc.neutrodyne.core.database.BackupPodcastRow
import ch.lkmc.neutrodyne.core.database.BackupQueueRow
import ch.lkmc.neutrodyne.core.database.BackupSessionRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.backupReadTransaction
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriter
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.EpisodeRefV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.OverridesV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SessionV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.ForwardingSink
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.blackholeSink
import okio.buffer
import okio.utf8Size
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The version-1 backup writer of 05 Writing: `BackupWriter.write(kind, target, includeCredentials)`
 * produces a staged ZIP and atomically moves it onto [target]. The archive is written to
 * `target.tmp` first, so a crash or failure mid-write never touches the previous target; on
 * success the staged file is flushed and `FileSystem.atomicMove` replaces it.
 *
 * Consistency: every database read runs inside ONE [backupReadTransaction] (02 Backup export —
 * WAL gives the whole export one point-in-time snapshot), and `episodes.jsonl` is streamed from
 * [BackupDao.episodeChunk] keyset chunks inside it — never materialised. `library.json`,
 * `queue.json`, `settings.json`, `subscriptions.opml` and `manifest.json` follow after the
 * transaction, in that order, each through a SHA-256 + byte/record metered sink so the manifest
 * describes the exact bytes written. `subscriptions.opml` is built by [OpmlExporter] from the
 * same transaction's rows ("from the in-memory library", 05 Writing), so it cannot disagree
 * with `library.json`.
 *
 * The R1.9 warning counts [preflight] reports are derived from the same rows and the same
 * credential rule the write path uses; the write itself re-derives the numbers from its own
 * in-transaction snapshot, so a warning can only drift when the library or a credential changes
 * between the dialog and the save (05 Writing).
 * Passwords travel only on opt-in and only in manual backups (D33, 05): snapshots never carry
 * credentials or `subscriptions.opml`. Secret I/O stays outside the Room transaction — 05 keeps
 * `SecretStore` calls (Android Keystore, the desktop secrets file) out of Room transactions.
 *
 * `AUTO_SNAPSHOT` writes may also carry a [SnapshotLevel] and a tighter compressed-size cap
 * (05 Snapshot production's retry ladder); every other kind always writes the full manual
 * shape under the shared limits.
 *
 * The single [mutex] is the process-wide backup mutex of 05 Writing: writes serialise here.
 */
@SingleIn(AppScope::class)
@Inject
internal class BackupWriter(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val archives: ArchiveWriterFactory,
    private val readers: ArchiveReaderFactory,
    private val opml: OpmlExporter,
    private val buildInfo: BuildInfo,
    private val clock: Clock,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val codec = BackupCodec()

    /**
     * Writes a [kind] backup to [target]. [includeCredentials] embeds feed passwords in plain
     * text (D33, manual backups only); `credentialOrigin` is written either way so a restore can
     * flag `needsCredentials`. [level] selects 05 Snapshot production's episode-line filter —
     * `AUTO_SNAPSHOT` only, because a non-snapshot write must never silently drop history.
     * [maxCompressedBytes] lets the producer tighten the staged ZIP's publication cap; it never
     * replaces the 50 MiB acquisition limit. Failures leave any previous [target] untouched;
     * format/acquisition limits report [BackupError.TooLarge], failed settings writes
     * [BackupError.Storage].
     */
    suspend fun write(
        kind: BackupKind,
        target: Path,
        includeCredentials: Boolean,
        level: SnapshotLevel = SnapshotLevel.FULL,
        maxCompressedBytes: Long = MAX_IMPORT_PAYLOAD_BYTES,
    ): Outcome<BackupSummary, BackupError> {
        require(kind == BackupKind.AUTO_SNAPSHOT || level == SnapshotLevel.FULL) {
            "history levels apply to AUTO_SNAPSHOT writes; a $kind write carries every line"
        }
        return mutex.withLock { writeLocked(kind, target, includeCredentials, level, maxCompressedBytes) }
    }

    /**
     * The R1.9 warning counts (05 Writing): private feed links in the library and the passwords
     * an opted-in write would embed. Counted over the same `libraryPodcasts` rows and the same
     * `SecretStore.forPodcast != null` rule [write] applies, so on an unchanged library the
     * numbers describe exactly the content the next write produces.
     */
    suspend fun preflight(includePasswords: Boolean): PrivacyCounts {
        val podcasts = db.backupReadTransaction { libraryPodcasts() }
        val privateLinks = podcasts.count { PrivateFeedUrls.looksPrivate(it.feedUrl) }
        val passwords = if (includePasswords) credentials(podcasts).size else 0
        return PrivacyCounts(privateLinks, passwords)
    }

    private suspend fun writeLocked(
        kind: BackupKind,
        target: Path,
        includeCredentials: Boolean,
        level: SnapshotLevel,
        maxCompressedBytes: Long,
    ): Outcome<BackupSummary, BackupError> =
        withContext(io) {
            val staged = stagedPath(target)
            try {
                // A leftover stage from a crashed write must not leak into the new archive.
                fileSystem.delete(staged, mustExist = false)
                Outcome.Success(writeArchive(kind, target, staged, includeCredentials, level, maxCompressedBytes))
            } catch (c: CancellationException) {
                deleteStaged(staged)
                throw c
            } catch (rejected: WriteRejected) {
                deleteStaged(staged)
                Outcome.Failure(rejected.error)
            } catch (rejected: ArchiveException) {
                deleteStaged(staged)
                Outcome.Failure(
                    if (rejected.kind == ArchiveException.Kind.HOSTILE) BackupError.TooLarge else BackupError.Io,
                )
            } catch (t: Throwable) {
                deleteStaged(staged)
                Log.w(TAG, t) { "backup write to ${target.name} failed" }
                Outcome.Failure(BackupError.Io)
            }
        }

    private suspend fun writeArchive(
        kind: BackupKind,
        target: Path,
        staged: Path,
        includeCredentials: Boolean,
        level: SnapshotLevel,
        maxCompressedBytes: Long,
    ): BackupSummary {
        // 05 Writing step 1: the portable snapshot is read once, outside the transaction.
        val settingValues = settingValues()
        val installationId = installationId()
        val includeOpml = kind != BackupKind.AUTO_SNAPSHOT

        val writer = archives.create(staged)
        val read: Read
        try {
            // Step 2: one read transaction — every export row plus the streamed
            // `episodes.jsonl` (the archive's first entry) and the OPML's rows. Queue and
            // session refs come first: `queue.json`'s references protect episode lines at
            // levels ≥ 1, so the protection set exists before the stream starts.
            read =
                db.backupReadTransaction {
                    val queue = queueRefs()
                    val session = playSession()
                    Read(
                        episodes = writeEpisodesEntry(writer, level, protectedRefs(queue, session)),
                        podcasts = libraryPodcasts(),
                        aliases = libraryAliases().groupBy({ it.podcastId }, { it.url }),
                        settings = libraryPodcastSettings().associate { it.podcastId to it.o },
                        ambiguous = libraryAmbiguousGuids().groupBy({ it.podcastId }, { it.guid }),
                        groups = libraryGroups(),
                        groupSettings = libraryGroupSettings().associate { it.groupId to it.o },
                        members = libraryMemberships().groupBy { it.groupId },
                        queue = queue,
                        session = session,
                        opml = if (includeOpml) opml.readSnapshot() else null,
                    )
                }

            // Step 3: secrets outside the transaction, then the remaining entries in order.
            val credentials =
                if (kind == BackupKind.MANUAL && includeCredentials) credentials(read.podcasts) else emptyList()

            val entries = ArrayList<EntryInfo>(ENTRY_SLOTS)
            entries += read.episodes.info
            entries +=
                writeJsonEntry(
                    writer,
                    BackupCodec.ENTRY_LIBRARY,
                    codec.encodeLibrary(libraryV1(read, credentials)),
                    records = read.podcasts.size + read.groups.size + credentials.size,
                )
            entries +=
                writeJsonEntry(
                    writer,
                    BackupCodec.ENTRY_QUEUE,
                    codec.encodeQueue(read.queueV1()),
                    records = read.queue.size + if (read.session != null) 1 else 0,
                )
            entries +=
                writeJsonEntry(
                    writer,
                    BackupCodec.ENTRY_SETTINGS,
                    codec.encodeSettings(SettingsV1(settingValues)),
                    records = settingValues.size,
                )
            if (includeOpml) {
                // groupId is never set here, so `document` cannot return null.
                val built = checkNotNull(opml.document(ExportRequest(), checkNotNull(read.opml)))
                entries += writeOpmlEntry(writer, built)
            }
            // `manifest.json` is written last and lists every earlier entry — its own hash
            // cannot appear inside it (05 Archive).
            val manifest =
                BackupManifest(
                    createdAt = Instant.fromEpochMilliseconds(clock.now()).toString(),
                    kind = kind,
                    app = appInfo(),
                    dbSchemaVersion = NeutrodyneDatabase.VERSION,
                    episodeKeysVersion = EpisodeKeys.VERSION,
                    urlNormalizerVersion = UrlNormalizer.VERSION,
                    installationId = installationId,
                    snapshotLevel = level.wire,
                    includesCredentials = credentials.isNotEmpty(),
                    counts =
                        CountsV1(
                            podcasts = read.podcasts.size,
                            groups = read.groups.size,
                            episodeLines = read.episodes.lines,
                            played = read.episodes.played,
                            inProgress = read.episodes.inProgress,
                            upNext = read.queue.size,
                            settings = settingValues.size,
                        ),
                    entries = entries,
                )
            writeJsonEntry(writer, BackupCodec.ENTRY_MANIFEST, codec.encodeManifest(manifest), records = 0)
            writer.close()
        } catch (t: Throwable) {
            suspendRunCatching { writer.close() }
            throw t
        }

        val bytes = fileSystem.metadataOrNull(staged)?.size ?: throw WriteRejected(BackupError.Io)
        // The producer's cap tightens the publication gate on the closed stage; the
        // 50 MiB acquisition limit still holds, so a looser request can never weaken it.
        if (bytes > minOf(maxCompressedBytes, MAX_IMPORT_PAYLOAD_BYTES)) {
            throw WriteRejected(BackupError.TooLarge)
        }
        validateArchive(staged)

        // Step 4: flush to media, then the atomic rename — the previous target stays intact
        // until the staged archive is complete and passes the restore-side caps.
        fileSystem.openReadWrite(staged).use { it.flush() }
        fileSystem.atomicMove(staged, target)
        return BackupSummary(
            bytes = bytes,
            podcasts = read.podcasts.size,
            episodeLines = read.episodes.lines,
        )
    }

    /** A producer must not replace the last good backup with an archive its reader rejects. */
    private fun validateArchive(path: Path) {
        readers.open(path, ArchiveCaps.BACKUP).use { reader ->
            for (entry in reader.entryNames) {
                reader.open(entry).buffer().use { it.readAll(blackholeSink()) }
            }
        }
    }

    /**
     * `episodes.jsonl`, streamed inside the read transaction: [BackupDao.episodeChunk] keyset
     * pages of [BackupDao.EPISODE_CHUNK] rows encoded line by line — the episode library is
     * never materialised (02 Backup export). [level] drops or slims rows in Kotlin before
     * encoding (02: the query itself does not change); [refs] are the `queue.json` references
     * read ahead of the stream, protected at every level. `ts = 0` lines carry a
     * `stateUpdatedAt` NULL row's no-state sentinel verbatim; `played`/`inProgress` count
     * played lines and started-but-never-played lines among the lines actually written, so
     * `CountsV1` describes the filtered file.
     */
    private suspend fun BackupDao.writeEpisodesEntry(
        writer: ArchiveWriter,
        level: SnapshotLevel,
        refs: Set<ProtectedRef>,
    ): EpisodeWrite {
        val cutoff = clock.now() - OLD_HISTORY_MS
        val metered = MeteredEntry(writer.entry(BackupCodec.ENTRY_EPISODES))
        var lines = 0
        var played = 0
        var inProgress = 0
        var after = 0L
        metered.sink.use { out ->
            while (true) {
                val chunk = episodeChunk(after, BackupDao.EPISODE_CHUNK)
                if (chunk.isEmpty()) break
                for (row in chunk) {
                    val dto = row.toLevelLine(level, cutoff, refs) ?: continue
                    val line = Buffer()
                    codec.writeEpisodeLine(line, dto)
                    // The reader's cap excludes LF. Never publish a line it would skip.
                    if (line.size - 1 > BackupCodec.EPISODE_LINE_MAX_BYTES) {
                        throw WriteRejected(BackupError.TooLarge)
                    }
                    out.writeAll(line)
                    lines++
                    if (row.playedAt != null) played++
                    if (row.playedAt == null && row.startedAt != null) inProgress++
                }
                if (chunk.size < BackupDao.EPISODE_CHUNK) break
                after = chunk.last().id
            }
        }
        return EpisodeWrite(metered.info(BackupCodec.ENTRY_EPISODES, lines), lines, played, inProgress)
    }

    private fun writeJsonEntry(
        writer: ArchiveWriter,
        name: String,
        payload: String,
        records: Int,
    ): EntryInfo {
        val maxBytes =
            when (name) {
                BackupCodec.ENTRY_LIBRARY -> BackupCodec.LIBRARY_MAX_BYTES
                BackupCodec.ENTRY_MANIFEST -> BackupCodec.MANIFEST_MAX_BYTES
                else -> ArchiveCaps.BACKUP.maxEntryBytes
            }
        if (payload.utf8Size() > maxBytes) throw WriteRejected(BackupError.TooLarge)

        val metered = MeteredEntry(writer.entry(name))
        metered.sink.use { it.writeUtf8(payload) }
        return metered.info(name, records)
    }

    /** `subscriptions.opml`: the grouped export layout, always without passwords (05 Archive). */
    private fun writeOpmlEntry(
        writer: ArchiveWriter,
        built: BuiltExport,
    ): EntryInfo {
        val metered = MeteredEntry(writer.entry(BackupCodec.ENTRY_OPML))
        metered.sink.use { OpmlWriter.write(it, built.document, OpmlLayout.GROUPED) }
        return metered.info(BackupCodec.ENTRY_OPML, built.document.feeds.size)
    }

    /**
     * Opted-in credentials (D33): one [CredentialV1] per podcast whose `credentialOrigin` the
     * store still serves. A credential the store cannot read is left out — the same "bare URL"
     * rule OPML export applies (05 Options and warnings).
     */
    private suspend fun credentials(podcasts: List<BackupPodcastRow>): List<CredentialV1> {
        val out = ArrayList<CredentialV1>()
        for (podcast in podcasts) {
            val origin = podcast.credentialOrigin ?: continue
            val secret = secrets.forPodcast(podcast.id) ?: continue
            out += CredentialV1(origin, secret.username, secret.password)
        }
        return out
    }

    /**
     * `settings.json`: every PORTABLE key of [AllSettingKeys] in registry order, typed by its
     * [SettingKey] subclass (05 Settings whitelist). DEVICE keys — `backup.installation_id`
     * included — never leave the device.
     */
    private suspend fun settingValues(): List<SettingValueV1> {
        val snapshot = settings.observePortableSnapshot().first()
        return AllSettingKeys.list.mapNotNull { key ->
            if (key.file != SettingsFile.PORTABLE) return@mapNotNull null
            val value = snapshot[key.name] ?: return@mapNotNull null
            settingValue(key, value)
        }
    }

    private fun settingValue(
        key: SettingKey<*>,
        value: Any,
    ): SettingValueV1? =
        when (key) {
            is SettingKey.Bool -> {
                (value as? Boolean)?.let { SettingValueV1(key.name, TYPE_BOOL, value = JsonPrimitive(it)) }
            }

            is SettingKey.Int32 -> {
                (value as? Int)?.let { SettingValueV1(key.name, TYPE_INT32, value = JsonPrimitive(it)) }
            }

            is SettingKey.Int64 -> {
                (value as? Long)?.let { SettingValueV1(key.name, TYPE_INT64, value = JsonPrimitive(it)) }
            }

            is SettingKey.Float32 -> {
                (value as? Float)?.let { SettingValueV1(key.name, TYPE_FLOAT32, value = JsonPrimitive(it)) }
            }

            is SettingKey.Text -> {
                (value as? String)?.let { SettingValueV1(key.name, TYPE_TEXT, value = JsonPrimitive(it)) }
            }

            is SettingKey.TextSet -> {
                (value as? Set<*>)
                    ?.let { set ->
                        SettingValueV1(key.name, TYPE_TEXT_SET, values = set.filterIsInstance<String>().sorted())
                    }
            }

            is SettingKey.Choice<*> -> {
                (value as? Enum<*>)?.let { SettingValueV1(key.name, TYPE_CHOICE, value = JsonPrimitive(it.name)) }
            }
        }

    /**
     * `backup.installation_id` (05 Snapshot production): the settings doc's "UUID generated on
     * first read" — the writer is that reader. The minted id is used only once persisted: an
     * ephemeral one would tag the next snapshot foreign and the guard would never overwrite it.
     */
    private suspend fun installationId(): String {
        val stored = settings.get(BackupSettingKeys.INSTALLATION_ID)
        if (stored.isNotEmpty()) return stored
        val minted = Uuid.random().toString()
        if (settings.set(BackupSettingKeys.INSTALLATION_ID, minted) is Outcome.Failure) {
            throw WriteRejected(BackupError.Storage)
        }
        return minted
    }

    /** `AppInfoV1`: Android's `apkAbi`, the desktop's `<os>-<arch>` (05's documented values). */
    private fun appInfo(): AppInfoV1 =
        AppInfoV1(
            versionName = buildInfo.versionName,
            versionCode = buildInfo.versionCode.toLong(),
            abi = buildInfo.apkAbi ?: buildInfo.desktop?.let { "${it.os.wire}-${it.arch.wire}" } ?: UNKNOWN_ABI,
            platform =
                when (buildInfo.platform) {
                    BuildInfo.Platform.ANDROID -> "android"
                    BuildInfo.Platform.DESKTOP -> buildInfo.desktop?.os?.wire
                },
        )

    private fun libraryV1(
        read: Read,
        credentials: List<CredentialV1>,
    ): LibraryV1 =
        LibraryV1(
            podcasts = read.podcasts.map { it.toV1(read) },
            groups = read.groups.mapIndexed { rank, group -> group.toV1(rank, read) },
            credentials = credentials,
        )

    private fun BackupPodcastRow.toV1(read: Read): PodcastV1 =
        PodcastV1(
            key = feedKey,
            feedUrl = feedUrl,
            source = sourceType.name,
            aliases = read.aliases[id].orEmpty(),
            // Real GUIDs only travel — a derived GUID is never identity evidence (02).
            podcastGuid = podcastGuid.takeUnless { podcastGuidDerived },
            youtubeChannelId = youtubeChannelId,
            youtubeVariants = youtubeVariants.takeIf { sourceType != SourceType.RSS },
            title = title,
            customTitle = customTitle,
            artworkUrl = artworkUrl,
            link = link,
            subscribedAt = subscribedAt,
            includeInAll = includeInAll,
            episodeOrder = episodeOrder?.name,
            settings = read.settings[id]?.toV1(),
            credentialOrigin = credentialOrigin,
            syncId = syncId,
            guidAmbiguous = read.ambiguous[id],
        )

    private fun PodcastGroupEntity.toV1(
        rank: Int,
        read: Read,
    ): GroupV1 =
        GroupV1(
            uuid = uuid,
            name = name,
            sortOrder = rank,
            orderKey = orderKey,
            colorArgb = colorArgb,
            iconKey = iconKey,
            kind = kind.name,
            rule = ruleJson?.let { Json.parseToJsonElement(it) },
            feedOrder = feedOrder.name,
            playOrder = playOrder.name,
            filterFlags = filterFlags,
            mediaFilter = mediaFilter.name,
            hideOlderThanDays = hideOlderThanDays,
            showAsTab = showAsTab,
            createdAt = createdAt,
            settings = read.groupSettings[id]?.toV1(),
            members =
                read.members[id].orEmpty().map {
                    MemberV1(podcastKey = it.feedKey, addedAt = it.addedAt, orderKey = it.orderKey)
                },
        )

    private fun ScopeOverrides.toV1(): OverridesV1 =
        OverridesV1(
            playbackSpeed = playbackSpeed,
            skipSilence = skipSilence,
            boostDb = boostDb,
            introSkipMs = introSkipMs,
            outroSkipMs = outroSkipMs,
            autoDownload = autoDownload,
            autoDownloadKeepLatest = autoDownloadKeepLatest,
            autoDownloadNetwork = autoDownloadNetwork?.name,
            autoDownloadRequireCharging = autoDownloadRequireCharging,
            deleteAfterPlayed = deleteAfterPlayed?.name,
            includeVideoInAutoDownload = includeVideoInAutoDownload,
            notifyNewEpisodes = notifyNewEpisodes,
            refreshIntervalMinutes = refreshIntervalMinutes,
        )

    private fun Read.queueV1(): QueueV1 =
        QueueV1(
            upNext =
                queue.map {
                    UpNextRefV1(
                        p = it.feedKey,
                        k = it.identityKey,
                        kv = EpisodeKeys.versionOf(it.identityKey),
                        ok = it.orderKey,
                    )
                },
            session = session?.toV1(),
        )

    private fun BackupSessionRow.toV1(): SessionV1 =
        SessionV1(
            current = episodeRef(currentFeedKey, currentIdentityKey),
            contextType = contextType?.name,
            contextGroupUuid = contextGroupUuid,
            contextPodcastKey = contextPodcastKey,
            order = contextOrder.name,
            filterFlags = contextFilterFlags,
            mediaFilter = contextMediaFilter.name,
            minSortDate = contextMinSortDate,
            anchor = episodeRef(anchorFeedKey, anchorIdentityKey),
            anchorSortDate = anchorSortDate,
        )

    /** An `(feedKey, identityKey)` pair becomes a ref only when both halves exist. */
    private fun episodeRef(
        feedKey: String?,
        identityKey: String?,
    ): EpisodeRefV1? {
        if (feedKey == null || identityKey == null) return null
        return EpisodeRefV1(feedKey, identityKey, EpisodeKeys.versionOf(identityKey))
    }

    /**
     * The `(feedKey, identityKey)` refs `queue.json` will carry — every Up next entry plus the
     * play session's `current`, whether or not it is queued — read inside the export
     * transaction before the episode stream so [SnapshotLevel]'s filter protects their lines
     * at every level: a referenced line is never slimmed or dropped, so it still restores or
     * stubs on an empty database (05 Snapshot production; the session `anchor` is a resume
     * hint, not a protected reference).
     */
    private fun protectedRefs(
        queue: List<BackupQueueRow>,
        session: BackupSessionRow?,
    ): Set<ProtectedRef> {
        val refs = HashSet<ProtectedRef>(queue.size + 1)
        for (row in queue) refs += ProtectedRef(row.feedKey, row.identityKey)
        session?.let {
            val feedKey = it.currentFeedKey
            val identityKey = it.currentIdentityKey
            if (feedKey != null && identityKey != null) {
                refs += ProtectedRef(feedKey, identityKey)
            }
        }
        return refs
    }

    /**
     * [level]'s verdict on one export row (05 Snapshot production's level table): `null` drops
     * the line from the archive. `droppablePlayed` is the same set for levels 1 and 2 —
     * played, `pl` older than [cutoff], no position, not favourite, unreferenced. Level 1
     * slims it to [slimmedLine]; level 2 drops it plus old tombstone-only lines; level 3
     * keeps only in-progress, favourite and referenced lines.
     */
    private fun BackupEpisodeRow.toLevelLine(
        level: SnapshotLevel,
        cutoff: Long,
        refs: Set<ProtectedRef>,
    ): EpisodeLineV1? {
        val referenced = ProtectedRef(feedKey, identityKey) in refs
        val position = positionMs
        val played = playedAt
        val hasPosition = position != null && position > 0
        val droppablePlayed =
            !referenced && !hasPosition && isFavorite != true && played != null && played < cutoff
        return when (level) {
            SnapshotLevel.FULL -> {
                toLine()
            }

            SnapshotLevel.SLIM_HISTORY -> {
                if (droppablePlayed) slimmedLine() else toLine()
            }

            SnapshotLevel.DROP_HISTORY -> {
                if (droppablePlayed || droppableTombstone(cutoff, referenced)) null else toLine()
            }

            SnapshotLevel.ACTIVE_ONLY -> {
                if (referenced || isFavorite == true || (playedAt == null && (hasPosition || startedAt != null))) {
                    toLine()
                } else {
                    null
                }
            }
        }
    }

    /**
     * Level 2's second drop set: an unreferenced line whose only state is a download
     * tombstone older than [cutoff]. A played/progress/favourite/duration mark or a live
     * download row makes it more than "tombstone-only".
     */
    private fun BackupEpisodeRow.droppableTombstone(
        cutoff: Long,
        referenced: Boolean,
    ): Boolean {
        val dismissed = downloadDismissedAt
        val position = positionMs
        return !referenced &&
            dismissed != null &&
            dismissed < cutoff &&
            playedAt == null &&
            startedAt == null &&
            lastPlayedAt == null &&
            (playCount ?: 0) == 0 &&
            isFavorite != true &&
            measuredDurationMs == null &&
            !(position != null && position > 0) &&
            !downloaded
    }

    /** Level 1's exact slim shape — `p`, `k`, `kv`, `pl`, `ts` and nothing else (05). */
    private fun BackupEpisodeRow.slimmedLine(): EpisodeLineV1 =
        EpisodeLineV1(
            p = feedKey,
            k = identityKey,
            kv = EpisodeKeys.versionOf(identityKey),
            pl = playedAt,
            ts = stateUpdatedAt ?: 0L,
        )

    /** The row columns map 1:1 onto `EpisodeLineV1` (02 Backup export); `id` is never exported. */
    private fun BackupEpisodeRow.toLine(): EpisodeLineV1 =
        EpisodeLineV1(
            p = feedKey,
            k = identityKey,
            kv = EpisodeKeys.versionOf(identityKey),
            g = guid,
            t = title,
            d = pubDate,
            u = enclosureUrl,
            ty = enclosureType,
            dur = durationMs,
            yt = externalMediaId,
            l = link,
            pl = playedAt,
            pc = playCount ?: 0,
            st = startedAt,
            lp = lastPlayedAt,
            pos = positionMs,
            posAt = positionUpdatedAt,
            posSrc = positionSource?.name,
            fav = isFavorite ?: false,
            dis = downloadDismissedAt,
            dl = downloaded,
            md = measuredDurationMs,
            ts = stateUpdatedAt ?: 0L,
        )

    /** `target`'s sibling stage: `<name>.tmp` in the same directory (05 Writing step 2). */
    private fun stagedPath(target: Path): Path =
        target.parent?.let { it / "${target.name}$STAGED_SUFFIX" } ?: "${target.name}$STAGED_SUFFIX".toPath()

    /** Best-effort stage cleanup — must never mask the original failure. */
    private suspend fun deleteStaged(staged: Path) {
        suspendRunCatching { fileSystem.delete(staged, mustExist = false) }
    }

    /**
     * The rows the one export transaction produces (05 Writing step 2): everything `library.json`,
     * `queue.json` and `subscriptions.opml` are built from, plus [episodes]' write stats.
     */
    private class Read(
        val episodes: EpisodeWrite,
        val podcasts: List<BackupPodcastRow>,
        val aliases: Map<Long, List<String>>,
        val settings: Map<Long, ScopeOverrides>,
        val ambiguous: Map<Long, List<String>>,
        val groups: List<PodcastGroupEntity>,
        val groupSettings: Map<Long, ScopeOverrides>,
        val members: Map<Long, List<BackupMemberRow>>,
        val queue: List<BackupQueueRow>,
        val session: BackupSessionRow?,
        val opml: OpmlExporter.Snapshot?,
    )

    /** `episodes.jsonl`'s [EntryInfo] plus the line counts `CountsV1` reports. */
    private class EpisodeWrite(
        val info: EntryInfo,
        val lines: Int,
        val played: Int,
        val inProgress: Int,
    )

    /** Expected refusals keep their domain error while the shared failure path removes staging. */
    private class WriteRejected(
        val error: BackupError,
    ) : Exception()

    /**
     * One entry's metering chain (05 Writing step 2): bytes written are counted on the way into
     * a `HashingSink.sha256`, so [info] reports the exact inflated byte count and digest the
     * manifest carries. Closing [sink] ends the entry.
     */
    private class MeteredEntry(
        entry: Sink,
    ) {
        private val hashing = HashingSink.sha256(entry)
        private var bytes = 0L
        val sink: BufferedSink =
            object : ForwardingSink(hashing) {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    bytes += byteCount
                    super.write(source, byteCount)
                }
            }.buffer()

        fun info(
            name: String,
            records: Int,
        ): EntryInfo = EntryInfo(name, bytes, hashing.hash.hex(), records)
    }

    private companion object {
        const val TAG = "BackupWriter"
        const val STAGED_SUFFIX = ".tmp"
        const val UNKNOWN_ABI = "unknown"

        /** One slot per archive entry (episodes, library, queue, settings, opml, manifest). */
        const val ENTRY_SLOTS = 6

        /** 05's "older than 365 days" bound for levels 1–2, measured against the write time. */
        const val OLD_HISTORY_MS: Long = 365L * 24 * 60 * 60 * 1000

        // `SettingValueV1.type` is the SettingKey subclass name — literals, never reflection,
        // so the wire contract survives minified builds.
        const val TYPE_BOOL = "Bool"
        const val TYPE_INT32 = "Int32"
        const val TYPE_INT64 = "Int64"
        const val TYPE_FLOAT32 = "Float32"
        const val TYPE_TEXT = "Text"
        const val TYPE_TEXT_SET = "TextSet"
        const val TYPE_CHOICE = "Choice"
    }
}

/**
 * 05 Snapshot production's episode-line levels — the producer asks for one and
 * [BackupWriter.write] applies it inside the export stream. [wire] is the integer the
 * manifest's `snapshotLevel` and `SnapshotStatus.level` carry; the numeric form lives only
 * at those boundaries, never as a magic `Int` inside the writer.
 */
internal enum class SnapshotLevel(
    val wire: Int,
) {
    /** Level 0 — every Backup export line (the manual/scheduled shape; the default). */
    FULL(0),

    /** Level 1 — old played lines without a live reference slim to `p`, `k`, `kv`, `pl`, `ts`. */
    SLIM_HISTORY(1),

    /** Level 2 — those lines drop, and old tombstone-only lines drop. */
    DROP_HISTORY(2),

    /** Level 3 — only in-progress, favourite, queued and current lines remain. */
    ACTIVE_ONLY(3),
}

/** `(feedKey, identityKey)` — the join between `queue.json` refs and episode lines. */
private data class ProtectedRef(
    val p: String,
    val k: String,
)

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.LibraryMerger
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergedRecord
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import ch.lkmc.neutrodyne.feeds.backup.UpNextRefV1
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkPhase
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/** The outcome of a first-link step (10 First-link choices). */
sealed interface FirstLinkResult {
    data class Completed(
        val applied: Int,
    ) : FirstLinkResult

    data class Failed(
        val error: String,
    ) : FirstLinkResult
}

/** The minimum admitted clock (2020-01-01); earlier row timestamps are raised to it. */
const val SYNC_MIN_CLOCK_MS = 1_577_836_800_000L

/** `Hlc`'s counter bits; a raw wall-clock tick is `millis << 16`. */
private const val HLC_COUNTER_BITS = 16

/** The pull's result over the staging file: the records, where the snapshot ends, the max clock. */
private class StagedPull(
    val records: List<RecordDto>,
    val cursor: String?,
    val maxClockPacked: Long,
)

/** One staged episode's park hints; [podcastSyncId] keys them for the parking callback. */
private data class EpisodeParkHints(
    val podcastSyncId: String,
    val guid: String?,
    val enclosureUrl: String?,
    val externalMediaId: String?,
    val recordJson: String,
)

/**
 * 10 Merge / Use the server's library / Use this device's library everywhere. Merge and
 * Use-the-server run 05's `RestoreMerger` through the [LibraryMerger] port: the pull's pages
 * land in a staging file on disk (`sync-link-<deviceId>.jsonl` in the cache directory, so a
 * 50,000-record account never sits in memory), are mapped to the staging directory's four
 * archive-named entries, and merge under the `LINK_*` policies — the one rules table that
 * serves restores and the first link (D92). "Use this device's library everywhere" uploads
 * first, pushes until the outbox drains and prunes last, unchanged.
 *
 * Staging resume (10 Merge step 1): a run interrupted after the pull finds the staging file
 * complete (its end marker carries the cursor) and reuses it instead of pulling again; the
 * merge itself is idempotent (05), so a crash mid-merge restarts from the staged snapshot.
 * The file is deleted once the link completes.
 *
 * After a merge, this class owns 10's bookkeeping: the local rows and the received records
 * seed `sync_clock` (local stamps for local rows, the shared `SyncRecordMerger` deciding the
 * merged state per record, so a local June `playedAt` loses to a server October "unplayed"),
 * the cursor is adopted with capture enabled, and only fields the local side won — or the
 * server lacks — are captured at their own timestamps; server-won fields are never
 * re-stamped. Synced settings arrive as durable effects (`pendingSetting`) that
 * [SyncApplyEffects] applies after the commit.
 */
class FirstLinkMerger(
    private val db: NeutrodyneDatabase,
    private val client: SyncClient,
    private val applier: SyncApplier,
    private val engine: SyncEngine,
    private val libraryMerger: LibraryMerger,
    storagePaths: StoragePaths,
    private val settings: ch.lkmc.neutrodyne.core.domain.SettingsSyncPort,
    private val clock: Clock,
) {
    private val fileSystem: FileSystem = FileSystem.SYSTEM
    private val cacheDir: Path = storagePaths.cacheDir.toPath()
    private val clockStore = SyncClockStore(db)

    suspend fun merge(
        choice: FirstLinkChoice,
        onProgress: (LinkPhase, Int, Int) -> Unit = { _, _, _ -> },
    ): FirstLinkResult {
        val roundId = clock.now()
        return try {
            when (choice) {
                FirstLinkChoice.MERGE -> mergeBoth(roundId, onProgress)
                FirstLinkChoice.USE_SERVER -> useServer(roundId, onProgress)
                FirstLinkChoice.USE_DEVICE -> useDeviceEverywhere(roundId, onProgress)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "merge failed")
        }
    }

    /**
     * Full resync (10 Cursor semantics and resync): pull every page from the beginning, apply it
     * against `sync_clock` and the outbox, then re-assert every known field with its original
     * literal value and complete clock (counter and origin node included). A newer pending local
     * intent stays untouched.
     */
    suspend fun resync(): FirstLinkResult {
        val roundId = clock.now()
        return try {
            val applied = pullAndApply(roundId, { _, _, _ -> })
            reassert()
            FirstLinkResult.Completed(applied)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "resync failed")
        }
    }

    /**
     * 05 Restore while linked, Replace ALL_DEVICES: the restore runner's step 8 runs 10's "Use
     * this device's library everywhere" steps — stage the snapshot, capture the replaced library
     * with fresh clocks, push until the outbox drains, prune by the snapshot cursor, `linkedAt`.
     */
    suspend fun restoreEverywhere(): FirstLinkResult {
        val roundId = clock.now()
        return try {
            // A fresh pull: the prune must bound to a snapshot that covers everything the
            // server holds now, and this run's uploads re-assert every surviving record.
            val pull = pullStaged(roundId, resume = false) { _, _, _ -> }
            adoptPulledState(pull.cursor, pull.maxClockPacked)
            captureLocalLibrary()
            pushOutbox { _, _, _ -> }
            client.reset(DELETE_CONFIRMATION, RESET_MODE_LIBRARY, pull.cursor)
            completeLink()
            FirstLinkResult.Completed(0)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FirstLinkResult.Failed(failure.message ?: "restore-everywhere failed")
        }
    }

    /**
     * The ALL_DEVICES preview (05): pull and stage the server library, then count its podcasts
     * and groups the backup lacks — what the other devices will remove when they next sync.
     */
    suspend fun previewAllDevicesReach(
        backupPodcastKeys: Set<String>,
        backupGroupNames: Set<String>,
    ): Pair<Int, Int> {
        val roundId = clock.now()
        val pull = pullStaged(roundId, resume = true) { _, _, _ -> }
        val backupNameKeys =
            backupGroupNames.mapNotNull { (GroupNames.validate(it) as? GroupNames.Validation.Valid)?.nameKey }.toSet()
        var podcasts = 0
        var groups = 0
        for (dto in pull.records) {
            when (runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull()) {
                SyncCollection.PODCAST -> {
                    val feedUrl =
                        dto.fields["feedUrl"]
                            ?.v
                            ?.jsonPrimitive
                            ?.contentOrNull
                    val key = feedUrl?.let(UrlNormalizer::forIdentity)
                    val live = (dto.fields["subscribed"]?.v as? JsonPrimitive)?.booleanOrNull != false
                    if (live && key != null && key !in backupPodcastKeys) podcasts++
                }

                SyncCollection.GROUP -> {
                    val name =
                        dto.fields["name"]
                            ?.v
                            ?.jsonPrimitive
                            ?.contentOrNull
                    val nameKey = name?.let { (GroupNames.validate(it) as? GroupNames.Validation.Valid)?.nameKey }
                    val live = (dto.fields["deleted"]?.v as? JsonPrimitive)?.booleanOrNull != true
                    if (live && nameKey != null && nameKey !in backupNameKeys) groups++
                }

                else -> {}
            }
        }
        return podcasts to groups
    }

    // --- Merge / Use the server ------------------------------------------------------------------

    private suspend fun mergeBoth(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        onProgress(LinkPhase.DOWNLOADING, 0, 1)
        val pull = pullStaged(roundId, resume = true, onProgress)
        // A fresh node needs its own rows seeded before the record merge decides winners.
        seedLocalRows()
        val staged = mapToStagingDirectory(pull)
        val applied = ArrayList<MergedRecord>()
        val hints = staged.parkHints
        val outcome =
            libraryMerger.mergeStaged(
                stagingDir = staged.dir.toString(),
                policy = MergePolicy.LINK_MERGE,
                onUnmatched = { podcastSyncId, identityKey -> park(podcastSyncId, identityKey, hints) },
                progress = { done, total -> onProgress(LinkPhase.DOWNLOADING, done, total) },
                onApplied = { applied += it },
            )
        if (outcome !is ch.lkmc.neutrodyne.core.common.Outcome.Success<*>) {
            return FirstLinkResult.Failed("mergeStaged failed: $outcome")
        }
        mergeReceivedClocks(pull.records)
        // Step 4: adopt the cursor and clocks before any bootstrap write, so capture is
        // admitted while the local winners are re-stamped with their own times.
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        captureLocalWinners()
        journalPendingSettings(pull.records)
        SyncApplyEffects(db, settings).drainSettings()
        pushOutbox(onProgress)
        completeLink()
        discardStaging()
        return FirstLinkResult.Completed(pull.records.size)
    }

    private suspend fun useServer(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        val pull = pullStaged(roundId, resume = true, onProgress)
        val staged = mapToStagingDirectory(pull)
        val hints = staged.parkHints
        val outcome =
            libraryMerger.mergeStaged(
                stagingDir = staged.dir.toString(),
                policy = MergePolicy.LINK_REPLACE,
                onUnmatched = { podcastSyncId, identityKey -> park(podcastSyncId, identityKey, hints) },
                progress = { done, total -> onProgress(LinkPhase.DOWNLOADING, done, total) },
            )
        if (outcome !is ch.lkmc.neutrodyne.core.common.Outcome.Success<*>) {
            return FirstLinkResult.Failed("mergeStaged failed: $outcome")
        }
        // The server's records are the adopted state: seed verbatim, nothing captured, so
        // later local changes order after them (10 Use the server's library).
        for (dto in pull.records) {
            val coll = runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull() ?: continue
            val rid = runCatching { RecordId.fromWire(coll, dto.id) }.getOrNull()?.rid ?: continue
            val incoming = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: continue
            clockStore.put(coll.wire, rid, RawSyncRecord(incoming))
        }
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        journalPendingSettings(pull.records)
        SyncApplyEffects(db, settings).drainSettings()
        completeLink()
        discardStaging()
        return FirstLinkResult.Completed(pull.records.size)
    }

    private suspend fun useDeviceEverywhere(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): FirstLinkResult {
        // Upload first: stage the server snapshot (never applied), adopt its cursor and clocks,
        // then capture this device's whole library.
        val pull = pullStaged(roundId, resume = true, onProgress)
        adoptPulledState(pull.cursor, pull.maxClockPacked)
        captureLocalLibrary()
        pushOutbox(onProgress)
        // Prune last: the server drops everything this upload did not re-assert, so no device can
        // observe a half-replaced library.
        client.reset(DELETE_CONFIRMATION, RESET_MODE_LIBRARY, pull.cursor)
        completeLink()
        discardStaging()
        return FirstLinkResult.Completed(0)
    }

    // --- The staging pull with resume (10 Merge step 1) --------------------------------------------

    /** `sync-link-<deviceId>.jsonl` in the cache directory (10 Merge step 1). */
    private fun stagingFile(deviceId: String): Path = cacheDir / "sync-link-$deviceId.jsonl"

    /** The mapped staging directory, a sibling of the staging file. */
    private fun stagingDirPath(deviceId: String): Path = cacheDir / "sync-link-$deviceId"

    private suspend fun dbDeviceId(): String =
        checkNotNull(db.syncStateDao().get()?.deviceId) { "the link wrote no device ID before the first-link merge" }

    private suspend fun pullStaged(
        roundId: Long,
        resume: Boolean,
        onProgress: (LinkPhase, Int, Int) -> Unit = { _, _, _ -> },
    ): StagedPull {
        val deviceId = dbDeviceId()
        val file = stagingFile(deviceId)
        if (resume) readStagedPull(file)?.let { return it }

        // The cache directory can legitimately not exist yet on a fresh install.
        fileSystem.createDirectories(cacheDir)
        fileSystem.deleteRecursively(stagingDirPath(deviceId), mustExist = false)
        fileSystem.delete(file, mustExist = false)
        val records = ArrayList<RecordDto>()
        var cursor: String? = null
        var maxClock = 0L
        var pages = 0
        fileSystem.sink(file).buffer().use { sink ->
            while (pages < MAX_PAGES) {
                pages++
                val page = client.changes(cursor, ProtocolLimits.MAX_RECORDS)
                for (dto in page.records) {
                    maxClock = maxOf(maxClock, maxClockOf(dto))
                    records += dto
                    sink.writeUtf8(SyncJson.encodeToString(StagingLine(record = dto))).writeByte('\n'.code)
                }
                cursor = page.cursor
                onProgress(LinkPhase.DOWNLOADING, records.size, records.size.coerceAtLeast(1))
                if (!page.hasMore) break
            }
            sink
                .writeUtf8(
                    SyncJson.encodeToString(
                        StagingLine(
                            end =
                                StagingEnd(
                                    end = true,
                                    cursor = cursor,
                                    maxClock = maxClock,
                                ),
                        ),
                    ),
                ).writeByte('\n'.code)
        }
        return StagedPull(records, cursor, maxClock)
    }

    /** A complete staging file (end marker present) is reused; a partial one is re-pulled. */
    private fun readStagedPull(file: Path): StagedPull? {
        if (!fileSystem.exists(file)) return null
        try {
            val records = ArrayList<RecordDto>()
            var end: StagingEnd? = null
            fileSystem.source(file).buffer().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val decoded = SyncJson.decodeFromString<StagingLine>(line)
                    if (decoded.end != null) {
                        end = decoded.end
                        break
                    }
                    decoded.record?.let(records::add)
                }
            }
            val marker = end ?: return null
            return StagedPull(records, marker.cursor, marker.maxClock)
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun discardStaging() {
        val deviceId = dbDeviceId()
        fileSystem.delete(stagingFile(deviceId), mustExist = false)
        fileSystem.deleteRecursively(stagingDirPath(deviceId), mustExist = false)
    }

    // --- The mapping into the staging directory (10 Merge step 2) ----------------------------------

    private class StagedDirectory(
        val dir: Path,
        val parkHints: Map<String, EpisodeParkHints>,
    )

    private suspend fun mapToStagingDirectory(pull: StagedPull): StagedDirectory {
        val dir = stagingDirPath(dbDeviceId())
        fileSystem.deleteRecursively(dir, mustExist = false)
        fileSystem.createDirectories(dir)
        val codec = BackupCodec(BackupCodec.Mode.STAGING)
        val podcasts = ArrayList<PodcastV1>()
        val groupsByUuid = LinkedHashMap<String, ch.lkmc.neutrodyne.feeds.backup.GroupV1>()
        val membersByGroup = LinkedHashMap<String, MutableList<MemberV1>>()
        val upNext = ArrayList<UpNextRefV1>()
        val settings = ArrayList<SettingValueV1>()
        val parkHints = HashMap<String, EpisodeParkHints>()
        var session: ch.lkmc.neutrodyne.feeds.backup.SessionV1? = null
        // The episodes stream straight into their entry, so a large account never holds
        // its history twice; the library's bounded lists accumulate.
        val episodesSink = fileSystem.sink(dir / BackupCodec.ENTRY_EPISODES).buffer()
        try {
            val keyByPodcastSyncId = HashMap<String, String>()
            for (dto in pull.records) {
                when (runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull()) {
                    SyncCollection.PODCAST -> {
                        mapPodcast(dto)?.let {
                            podcasts += it
                            keyByPodcastSyncId[it.syncId.orEmpty()] =
                                it.key
                        }
                    }

                    SyncCollection.GROUP -> {
                        mapGroup(dto)?.let { groupsByUuid[it.uuid] = it }
                    }

                    SyncCollection.MEMBER -> {
                        mapMember(dto, keyByPodcastSyncId)?.let {
                            membersByGroup.getOrPut(it.first) { mutableListOf() } +=
                                it.second
                        }
                    }

                    SyncCollection.EPISODE -> {
                        mapEpisode(dto, keyByPodcastSyncId)?.let { (line, hints) ->
                            episodesSink.writeUtf8(codec.encodeEpisodeLine(line)).writeByte('\n'.code)
                            hints?.let { parkHints["${it.podcastSyncId}|${line.k}"] = it }
                        }
                    }

                    SyncCollection.UPNEXT -> {
                        mapUpNext(dto, keyByPodcastSyncId)?.let { upNext += it }
                    }

                    SyncCollection.SESSION -> {
                        session = mapSession(dto)
                    }

                    SyncCollection.SETTING -> {
                        mapSetting(dto)?.let { settings += it }
                    }

                    null -> {}
                }
            }
        } finally {
            episodesSink.close()
        }
        val groups =
            groupsByUuid.values.map { group ->
                group.copy(members = membersByGroup[group.uuid].orEmpty())
            }
        val createdAt = clock.now()
        fileSystem.write(dir / BackupCodec.ENTRY_LIBRARY) {
            writeUtf8(codec.encodeLibrary(LibraryV1(podcasts = podcasts, groups = groups)))
        }
        fileSystem.write(dir / BackupCodec.ENTRY_QUEUE) {
            writeUtf8(codec.encodeQueue(QueueV1(upNext = upNext, session = session)))
        }
        fileSystem.write(dir / BackupCodec.ENTRY_SETTINGS) {
            writeUtf8(codec.encodeSettings(SettingsV1(values = settings)))
        }
        fileSystem.write(dir / STAGING_META_ENTRY) { writeUtf8("""{"createdAt":$createdAt}""") }
        return StagedDirectory(dir, parkHints)
    }

    private fun mapPodcast(dto: RecordDto): PodcastV1? {
        val rid =
            (
                runCatching {
                    RecordId.fromWire(
                        SyncCollection.PODCAST,
                        dto.id,
                    )
                }.getOrNull() as? RecordId.Podcast
            )?.id?.value
                ?: return null
        val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return null
        val feedUrl = record.stringField("feedUrl") ?: return null
        val key = UrlNormalizer.forIdentity(feedUrl) ?: return null
        val aliases = record.growSets["feedKeys"].orEmpty().filter { it != key }
        return PodcastV1(
            key = key,
            feedUrl = feedUrl,
            source = record.stringField("sourceType") ?: "RSS",
            aliases = aliases,
            podcastGuid = record.stringField("podcastGuid"),
            youtubeChannelId = record.stringField("youtubeChannelId"),
            title = record.stringField("title") ?: feedUrl,
            customTitle = record.stringField("customTitle"),
            artworkUrl = record.stringField("artworkUrl"),
            link = record.stringField("link"),
            subscribedAt = record.longField("subscribedAt") ?: record.fieldMillis("subscribed") ?: clock.now(),
            includeInAll = record.booleanField("includeInAll") ?: true,
            episodeOrder = record.stringField("episodeOrder"),
            syncId = rid,
            settings = record.overrides(),
        )
    }

    private fun mapGroup(dto: RecordDto): ch.lkmc.neutrodyne.feeds.backup.GroupV1? {
        val rid =
            (runCatching { RecordId.fromWire(SyncCollection.GROUP, dto.id) }.getOrNull() as? RecordId.Group)?.id?.value
                ?: return null
        val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return null
        val name = record.stringField("name") ?: return null
        return ch.lkmc.neutrodyne.feeds.backup.GroupV1(
            uuid = rid,
            name = name,
            sortOrder = 0,
            orderKey = record.stringField("ok"),
            colorArgb = record.intField("colorArgb"),
            iconKey = record.stringField("iconKey"),
            feedOrder = record.stringField("feedOrder") ?: "NEWEST_FIRST",
            playOrder = record.stringField("playOrder") ?: "NEWEST_FIRST",
            filterFlags = record.intField("filterFlags") ?: 0,
            mediaFilter = record.stringField("mediaFilter") ?: "ALL",
            hideOlderThanDays = record.intField("hideOlderThanDays"),
            showAsTab = record.booleanField("showAsTab") ?: true,
            createdAt = record.longField("createdAt"),
            settings = record.overrides(),
        )
    }

    private suspend fun mapMember(
        dto: RecordDto,
        keyByPodcastSyncId: Map<String, String>,
    ): Pair<String, MemberV1>? {
        val id =
            runCatching { RecordId.fromWire(SyncCollection.MEMBER, dto.id) }.getOrNull() as? RecordId.Member
                ?: return null
        val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return null
        val key =
            keyByPodcastSyncId[id.podcast.value] ?: db.podcastDao().bySyncId(id.podcast.value)?.feedKey ?: return null
        return id.group.value to
            MemberV1(podcastKey = key, addedAt = record.longField("addedAt"), orderKey = record.stringField("ok"))
    }

    private suspend fun mapEpisode(
        dto: RecordDto,
        keyByPodcastSyncId: Map<String, String>,
    ): Pair<EpisodeLineV1, EpisodeParkHints?>? {
        val id =
            runCatching { RecordId.fromWire(SyncCollection.EPISODE, dto.id) }.getOrNull() as? RecordId.Episode
                ?: return null
        val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return null
        val p =
            keyByPodcastSyncId[id.podcast.value] ?: db.podcastDao().bySyncId(id.podcast.value)?.feedKey ?: return null
        val fc = LinkedHashMap<String, Long>()
        for (name in STAGED_STATE_CLOCK_FIELDS) {
            record.fields[name]?.c?.let { fc[name] = it.millis }
        }
        val line =
            EpisodeLineV1(
                p = p,
                k = id.key.value,
                kv = 1,
                g = record.match?.guid,
                t = record.match?.title,
                d =
                    record.match?.date?.let {
                        runCatching {
                            kotlin.time.Instant
                                .parse(it)
                                .toEpochMilliseconds()
                        }.getOrNull()
                    },
                u = record.match?.enc,
                yt = record.match?.ytId,
                l = record.match?.link,
                pl = record.longField("playedAt"),
                pc = record.longField("playCount")?.toInt() ?: 0,
                lp = record.longField("lastPlayedAt"),
                pos = (record.fields["pos"]?.v as? JsonObject)?.get("ms")?.jsonPrimitive?.longOrNull,
                posAt = fc["pos"],
                fav = record.booleanField("fav") ?: false,
                md = record.longField("measuredDurationMs"),
                ts = fc.values.filter { it != fc["pos"] }.maxOrNull() ?: 0L,
                fc = fc,
            )
        val hints =
            EpisodeParkHints(
                podcastSyncId = id.podcast.value,
                guid = record.match?.guid,
                enclosureUrl = record.match?.enc?.let(UrlNormalizer::forIdentity),
                externalMediaId = record.match?.ytId,
                recordJson = SyncJson.encodeToString(RecordDto.serializer(), dto),
            )
        return line to hints
    }

    private fun mapUpNext(
        dto: RecordDto,
        keyByPodcastSyncId: Map<String, String>,
    ): UpNextRefV1? {
        val id =
            runCatching { RecordId.fromWire(SyncCollection.UPNEXT, dto.id) }.getOrNull() as? RecordId.Episode
                ?: return null
        val key = keyByPodcastSyncId[id.podcast.value] ?: return null
        return UpNextRefV1(p = key, k = id.key.value)
    }

    private fun mapSession(dto: RecordDto): ch.lkmc.neutrodyne.feeds.backup.SessionV1? {
        val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: return null
        return ch.lkmc.neutrodyne.feeds.backup.SessionV1(
            contextType = record.stringField("contextType"),
            contextGroupUuid = record.stringField("contextGroupUuid"),
            contextPodcastKey = record.stringField("contextPodcastKey"),
            order = record.stringField("order") ?: "NEWEST_FIRST",
        )
    }

    private fun mapSetting(dto: RecordDto): SettingValueV1? {
        val key =
            AllSettingKeys.list.firstOrNull { it.name == (dto.id as? JsonPrimitive)?.contentOrNull } ?: return null
        val value = dto.fields["v"]?.v as? JsonPrimitive ?: return null
        return SettingValueV1(
            key = key.name,
            type = settingWireType(key),
            value = value,
        )
    }

    // --- Seeding and captures (10 Merge steps 4–5) --------------------------------------------------

    /**
     * Stamps this device's current rows into `sync_clock` at their own timestamps (counter 0,
     * this node), so the record merge decides each field by clock and the capture pass knows
     * which fields the local side owns. Groups, members and Up next are seeded too: the
     * design's capture list covers the whole library, not only podcasts and episodes.
     */
    private suspend fun seedLocalRows() {
        val nodeId = nodeId()
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    val fields =
                        mapOf(
                            "feedUrl" to fieldString(podcast.feedUrl, podcast.subscribedAt, nodeId),
                            "subscribed" to fieldBool(true, podcast.subscribedAt, nodeId),
                            "title" to fieldString(podcast.title, podcast.subscribedAt, nodeId),
                        )
                    writeSeed(SyncCollection.PODCAST, podcast.syncId, fields)
                }
                for (group in db.groupDao().groupsInOrder()) {
                    val fields =
                        mapOf(
                            "name" to fieldString(group.name, group.updatedAt, nodeId),
                            "deleted" to fieldBool(false, group.updatedAt, nodeId),
                        )
                    writeSeed(SyncCollection.GROUP, group.uuid, fields)
                    for (member in db.groupDao().membersOf(group.id)) {
                        val podcast = db.podcastDao().byId(member.podcastId) ?: continue
                        val at = member.addedAt
                        writeSeed(
                            SyncCollection.MEMBER,
                            group.uuid + podcast.syncId,
                            mapOf("in" to fieldBool(true, at, nodeId)),
                        )
                    }
                }
                for (entry in db.queueDao().entries()) {
                    val episode = db.episodeDao().byId(entry.episodeId) ?: continue
                    val podcast = db.podcastDao().byId(episode.podcastId) ?: continue
                    writeSeed(
                        SyncCollection.UPNEXT,
                        podcast.syncId + episode.identityKey,
                        mapOf("in" to fieldBool(true, entry.addedAt, nodeId)),
                    )
                }
                for (episode in db.episodeDao().all()) {
                    val podcast = db.podcastDao().byId(episode.podcastId) ?: continue
                    val state = db.episodeStateDao().byEpisode(episode.id)
                    val position = db.positionDao().byEpisode(episode.id)
                    val fields = mutableMapOf<String, FieldState>()
                    if (state != null) {
                        val at = maxOf(state.updatedAt, position?.updatedAt ?: 0L)
                        fields["played"] = fieldBool(state.playedAt != null, at, nodeId)
                        fields["playedAt"] =
                            FieldState(state.playedAt?.let(::JsonPrimitive) ?: JsonNull, stamp(at, nodeId))
                        fields["fav"] = fieldBool(state.isFavorite, at, nodeId)
                        if (state.playCount > 0) fields["playCount"] = fieldLong(state.playCount.toLong(), at, nodeId)
                        state.lastPlayedAt?.let { fields["lastPlayedAt"] = fieldLong(it, at, nodeId) }
                    }
                    if (position != null && position.positionMs > 0) {
                        fields["pos"] =
                            FieldState(
                                JsonObject(buildMap { put("ms", JsonPrimitive(position.positionMs)) }),
                                stamp(position.updatedAt, nodeId),
                            )
                    }
                    if (fields.isNotEmpty()) {
                        writeSeed(SyncCollection.EPISODE, podcast.syncId + episode.identityKey, fields)
                    }
                }
            }
        }
    }

    /**
     * Merges every received record into `sync_clock` through the shared field merge: the
     * local seed stands in for the stored state, so server-won fields keep their received
     * clocks and local-won fields keep the frozen local stamps (10 Merge step 4).
     */
    private suspend fun mergeReceivedClocks(records: List<RecordDto>) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (dto in records) {
                    val coll = runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull() ?: continue
                    val rid = runCatching { RecordId.fromWire(coll, dto.id) }.getOrNull()?.rid ?: continue
                    val incoming = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: continue
                    val stored = clockStore.get(coll.wire, rid)?.state
                    val merged = SyncRecordMerger.merge(stored, null, incoming)
                    clockStore.put(
                        coll.wire,
                        rid,
                        RawSyncRecord(
                            state = merged.record,
                            by = dto.by ?: clockStore.get(coll.wire, rid)?.by,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The capture pass (10 Merge step 5): every field whose clock carries this node — the
     * local-won fields and every field of a record the server lacks — is captured at its own
     * timestamp, the played pair as one literal. Fields the server won carry the server's
     * origin node and are never re-stamped. Records with pending outbox rows are left alone.
     */
    private suspend fun captureLocalWinners() {
        val nodeId = nodeId().value
        for (row in db.syncClockDao().all()) {
            val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull() ?: continue
            if (db.syncOutboxDao().rowsFor(row.coll, listOf(row.rid)).isNotEmpty()) continue
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    val played = raw.state.fields["played"]
                    val playedAt = raw.state.fields["playedAt"]
                    for ((name, field) in raw.state.fields) {
                        val stampClock = field.c ?: continue
                        if (stampClock.nodeId.value != nodeId) continue
                        val at = stampClock.millis
                        if (name == "playedAt") continue
                        if (name == "played") {
                            val literal =
                                JsonObject(
                                    buildMap {
                                        put("played", played?.v ?: JsonNull)
                                        put("playedAt", playedAt?.v ?: JsonNull)
                                    },
                                ).toString()
                            db.syncOutboxDao().captureAt(row.coll, row.rid, "played", at, literal)
                            continue
                        }
                        db.syncOutboxDao().captureAt(row.coll, row.rid, name, at, field.v.toString())
                    }
                }
            }
        }
    }

    /** Journals each synced setting the server has as a durable effect (10 Durable apply effects). */
    private suspend fun journalPendingSettings(records: List<RecordDto>) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (dto in records) {
                    if (runCatching { SyncCollection.fromWire(dto.coll) }.getOrNull() !=
                        SyncCollection.SETTING
                    ) {
                        continue
                    }
                    val key =
                        AllSettingKeys.list.firstOrNull { it.name == (dto.id as? JsonPrimitive)?.contentOrNull }
                            ?: continue
                    val value = dto.fields["v"]?.v ?: continue
                    val record = runCatching { ProtocolValidation.record(dto) }.getOrNull() ?: continue
                    val stored = clockStore.get(SyncCollection.SETTING.wire, key.name)
                    clockStore.put(
                        SyncCollection.SETTING.wire,
                        key.name,
                        RawSyncRecord(
                            state = record,
                            pendingSetting = PendingSetting(value.toString(), record.maxClock?.toString() ?: ""),
                        ),
                    )
                    if (stored?.pendingSetting != null && stored.pendingSetting?.value != value.toString()) {
                        // keep the newer pending marker: the fresh write above is authoritative
                    }
                }
            }
        }
    }

    /** Parks an unmatched episode record for the next ingest (10 Parked state and stubs). */
    private suspend fun park(
        podcastSyncId: String,
        identityKey: String,
        hints: Map<String, EpisodeParkHints>,
    ) {
        val hint = hints["$podcastSyncId|$identityKey"]
        db
            .syncParkedDao()
            .park(
                ch.lkmc.neutrodyne.core.database.SyncParkedEntity(
                    podcastSyncId = podcastSyncId,
                    identityKey = identityKey,
                    guid = hint?.guid,
                    enclosureKey = hint?.enclosureUrl,
                    record = hint?.recordJson ?: "{}",
                    receivedAt = clock.now(),
                ),
            )
    }

    // --- Shared steps ------------------------------------------------------------------------------

    private suspend fun reassert() {
        for (row in db.syncClockDao().all()) {
            val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull() ?: continue
            if (db.syncOutboxDao().rowsFor(row.coll, listOf(row.rid)).isNotEmpty()) continue
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    for ((name, field) in raw.state.fields) {
                        val stampClock = field.c ?: continue
                        db
                            .syncOutboxDao()
                            .captureStored(
                                row.coll,
                                row.rid,
                                name,
                                stampClock.packed,
                                stampClock.nodeId.value,
                                field.v.toString(),
                            )
                    }
                    raw.state.growSets.forEach { (name, values) ->
                        val stampClock = raw.state.maxClock ?: return@forEach
                        db
                            .syncOutboxDao()
                            .captureStored(
                                row.coll,
                                row.rid,
                                name,
                                stampClock.packed,
                                stampClock.nodeId.value,
                                values.sorted().joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
                            )
                    }
                }
            }
        }
    }

    /** Captures every local record of the library with a fresh clock (10 step 3). */
    private suspend fun captureLocalLibrary() {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                for (podcast in db.podcastDao().allForExport()) {
                    db.syncOutboxDao().captureAll(SyncCollection.PODCAST.wire, podcast.syncId)
                }
                for (group in db.groupDao().groupsInOrder()) {
                    db.syncOutboxDao().captureAll(SyncCollection.GROUP.wire, group.uuid)
                    for (member in db.groupDao().membersOf(group.id)) {
                        val podcast = db.podcastDao().byId(member.podcastId) ?: continue
                        db.syncOutboxDao().captureAll(SyncCollection.MEMBER.wire, group.uuid + podcast.syncId)
                    }
                }
                for (entry in db.queueDao().entries()) {
                    val episode = db.episodeDao().byId(entry.episodeId) ?: continue
                    val podcast = db.podcastDao().byId(episode.podcastId) ?: continue
                    db.syncOutboxDao().captureAll(SyncCollection.UPNEXT.wire, podcast.syncId + episode.identityKey)
                }
                db.syncOutboxDao().captureAll(SyncCollection.SESSION.wire, "current")
            }
        }
    }

    /** Pushes until the outbox drains; the server's field merge keeps whichever side is newer. */
    private suspend fun pushOutbox(onProgress: (LinkPhase, Int, Int) -> Unit) {
        val total = db.syncOutboxDao().count()
        if (total == 0) return
        var rounds = 0
        while (db.syncOutboxDao().count() > 0 && rounds < MAX_PUSH_ROUNDS) {
            rounds++
            engine.round(SyncRoundReason.FIRST_LINK)
            onProgress(LinkPhase.UPLOADING, rounds, total.coerceAtLeast(1))
        }
    }

    /** Writes the negotiated cursor and raises the clock past everything seen and now. */
    private suspend fun adoptPulledState(
        cursor: String?,
        maxClockPacked: Long,
    ) {
        db.withWriteTransaction {
            db.syncStateDao().get()?.let { state ->
                db.syncStateDao().upsert(
                    state.copy(
                        enabled = true,
                        cursor = cursor,
                        hlc = maxOf(state.hlc, maxClockPacked, clock.now() shl HLC_COUNTER_BITS),
                    ),
                )
            }
        }
    }

    private suspend fun completeLink() {
        db.withWriteTransaction {
            db.syncStateDao().get()?.let {
                db.syncStateDao().upsert(it.copy(enabled = true, linkedAt = clock.now()))
            }
        }
    }

    private suspend fun nodeId(): NodeId =
        NodeId(
            checkNotNull(db.syncStateDao().get()?.nodeId) {
                "the link wrote no node ID before the first-link merge"
            },
        )

    private suspend fun pullAndApply(
        roundId: Long,
        onProgress: (LinkPhase, Int, Int) -> Unit,
    ): Int {
        var applied = 0
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val page = client.changes(cursor, ProtocolLimits.MAX_RECORDS)
            applied += page.records.size
            applier.applyPage(page.records, roundId)
            cursor = page.cursor
            onProgress(LinkPhase.DOWNLOADING, applied, applied.coerceAtLeast(1))
            if (!page.hasMore) break
        }
        return applied
    }

    private suspend fun writeSeed(
        coll: SyncCollection,
        rid: String,
        fields: Map<String, FieldState>,
    ) {
        // A seed never overwrites a fuller raw record an earlier step already stored.
        if (clockStore.get(coll.wire, rid) != null) return
        clockStore.put(coll.wire, rid, RawSyncRecord(RecordState(coll, fields)))
    }

    private fun stamp(
        atMs: Long,
        nodeId: NodeId,
    ): Hlc = Hlc.of(atMs.coerceAtLeast(SYNC_MIN_CLOCK_MS), 0, nodeId)

    private fun fieldString(
        value: String,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private fun fieldLong(
        value: Long,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private fun fieldBool(
        value: Boolean,
        atMs: Long,
        nodeId: NodeId,
    ): FieldState = FieldState(JsonPrimitive(value), stamp(atMs, nodeId))

    private fun maxClockOf(record: RecordDto): Long =
        record.fields.values
            .mapNotNull { it.c }
            .plus(record.matchClocks.values)
            .maxOfOrNull { runCatching { Hlc.parse(it).packed }.getOrDefault(0L) } ?: 0L

    private companion object {
        /** `link-meta.json` — the pull time; 05's "no manifest" bars validation, not metadata. */
        const val STAGING_META_ENTRY = "link-meta.json"

        const val MAX_PAGES = 10_000
        const val MAX_PUSH_ROUNDS = 100
        const val DELETE_CONFIRMATION = "DELETE"
        const val RESET_MODE_LIBRARY = "library"

        /** The state fields whose clocks ride into the staging `fc` (05 Archive). */
        val STAGED_STATE_CLOCK_FIELDS =
            listOf("played", "fav", "playCount", "lastPlayedAt", "measuredDurationMs", "pos")
    }
}

/** The staging file's line shapes: one record per line, one end marker at the tail. */
@Serializable
private data class StagingLine(
    val record: RecordDto? = null,
    val end: StagingEnd? = null,
)

@Serializable
private data class StagingEnd(
    val end: Boolean = true,
    val cursor: String? = null,
    val maxClock: Long = 0L,
)

private fun RecordState.stringField(name: String): String? =
    (fields[name]?.v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun RecordState.booleanField(name: String): Boolean? = (fields[name]?.v as? JsonPrimitive)?.booleanOrNull

private fun RecordState.longField(name: String): Long? = (fields[name]?.v as? JsonPrimitive)?.longOrNull

private fun RecordState.intField(name: String): Int? = (fields[name]?.v as? JsonPrimitive)?.longOrNull?.toInt()

private fun RecordState.fieldMillis(name: String): Long? = fields[name]?.c?.millis

private fun RecordState.overrides(): ch.lkmc.neutrodyne.feeds.backup.OverridesV1? {
    val overrides =
        ch.lkmc.neutrodyne.feeds.backup.OverridesV1(
            playbackSpeed = stringField("s.playbackSpeed")?.toFloatOrNull(),
            skipSilence = booleanField("s.skipSilence"),
            boostDb = stringField("s.boostDb")?.toFloatOrNull(),
            introSkipMs = longField("s.introSkipMs"),
            outroSkipMs = longField("s.outroSkipMs"),
        )
    return if (
        overrides.playbackSpeed == null &&
        overrides.skipSilence == null &&
        overrides.boostDb == null &&
        overrides.introSkipMs == null &&
        overrides.outroSkipMs == null
    ) {
        null
    } else {
        overrides
    }
}

private fun settingWireType(key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<*>): String =
    when (key) {
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Bool -> "Bool"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Int32 -> "Int32"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Int64 -> "Int64"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Float32 -> "Float32"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Text -> "Text"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.TextSet -> "TextSet"
        is ch.lkmc.neutrodyne.core.model.settings.SettingKey.Choice<*> -> "Choice"
        else -> "Text"
    }

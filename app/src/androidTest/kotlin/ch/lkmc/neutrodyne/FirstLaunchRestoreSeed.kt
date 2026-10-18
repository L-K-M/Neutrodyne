// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.KeyInput
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * The `firstLaunchSeed=restore` fixture of `FirstLaunchRestoreDeviceTest` (05 First-launch
 * restore; M3's GMD row of the 05 test matrix). `NeutrodyneTestRunner.callApplicationOnCreate`
 * runs it before `super` invokes production `NeutrodyneApplication.onCreate` — the only
 * in-process point that still precedes the band-100 database open.
 *
 * The seed drives a *second, discarded* `AndroidAppGraph` against the real storage roots: it
 * opens the real `databases/neutrodyne.db`, writes a small library (two RSS podcasts with
 * played/favourite/position state, two groups, Up next, a play session), then produces
 * `files/backup/auto-snapshot.zip` through the public `BackupRepository.writeSnapshotNow()`
 * — the same writer/ZIP path production uses; no prebuilt archive, no fake producer.
 *
 * To model what Android Auto Backup actually delivers on reinstall, only the database family
 * and the *device* DataStore file are deleted; the snapshot, the payload cache and the
 * *portable* DataStore stay — Auto Backup restores `settings.preferences_pb` itself, which is
 * exactly why the production restore skips SETTINGS. The portable flag is written once before
 * the snapshot ([PORTABLE_VALUE_IN_SNAPSHOT]) and once after ([PORTABLE_VALUE_KEPT]), so the
 * test proves the restore did not re-apply the archived value.
 *
 * `files/first-launch-seed.json` records the snapshot hash/bytes, the minted installation ID
 * and the fixture summary, so the test asserts against the archive that was actually written.
 *
 * A failed seed throws out of `callApplicationOnCreate` — the app never starts and the run
 * can only fail loudly, never silently pass.
 */
internal object FirstLaunchRestoreSeed {
    /** Instrumentation argument (read by both the runner and the test). */
    const val ARGUMENT = "firstLaunchSeed"
    const val VALUE_RESTORE = "restore"

    /** `files/` marker holding the expected hashes and the fixture's identity map. */
    const val MARKER_FILE = "first-launch-seed.json"

    /** The PORTABLE setting flipped around the snapshot to prove SETTINGS is never restored. */
    const val PORTABLE_KEY_NAME = "backup.opml_layout"
    val PORTABLE_VALUE_IN_SNAPSHOT = ExportLayout.GROUPED
    val PORTABLE_VALUE_KEPT = ExportLayout.FLAT

    /** Deterministic timestamps — the restored row must carry these, not restore-time values. */
    private const val T_BASE = 1_730_000_000_000L
    private const val T_STATE_PLAYED = T_BASE + 10_000
    private const val T_POSITION = T_BASE + 20_000
    private const val T_STATE_PLAYED_TWO = T_BASE + 30_000
    private const val T_WRITE = T_BASE + 40_000

    fun isRequested(arguments: Bundle): Boolean = arguments.getString(ARGUMENT) == VALUE_RESTORE

    /**
     * Seeds, snapshots and tears down the pre-start fixture. Runs before production `onCreate`;
     * blocking the launch sequence on the app process's main thread is the point of the seam.
     */
    fun run(app: NeutrodyneApplication) {
        val graph = createGraphFactory<AndroidAppGraph.Factory>().create(app)
        runBlocking(Dispatchers.IO) {
            val opened = graph.databaseOpener.awaitOpen()
            check(opened.created) { "the seed must open a created database" }
            val db = graph.databaseOpener.requireDatabase()
            seedLibrary(db)

            // The snapshot's settings.json must carry the pre-snapshot value; Auto Backup's
            // restored PB keeps the post-snapshot one — the pair proves SETTINGS is skipped.
            checkOutcome(
                "portable setting before snapshot",
                graph.settingsRepository.set(BackupSettingKeys.OPML_LAYOUT, PORTABLE_VALUE_IN_SNAPSHOT),
            )
            checkOutcome(
                "snapshot enable flag",
                graph.settingsRepository.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true),
            )
            check(
                graph.backupRepository.writeSnapshotNow() is Outcome.Success,
            ) { "the seed snapshot write must succeed through the public repository" }
            checkOutcome(
                "portable setting after snapshot",
                graph.settingsRepository.set(BackupSettingKeys.OPML_LAYOUT, PORTABLE_VALUE_KEPT),
            )

            // The writer mints `backup.installation_id` into the DEVICE store at write time —
            // capture it for the marker before the device file is deleted.
            val installationId = graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID)
            check(installationId.isNotEmpty()) { "the written snapshot must mint an installation ID" }

            db.close()
            // The seed scope owns the DataStore actors; joining it is what releases both
            // `*.preferences_pb` files so the production graph can open them cleanly.
            graph.appScope.coroutineContext.job
                .cancelAndJoin()

            val snapshot = File(app.filesDir, "backup/auto-snapshot.zip")
            check(snapshot.isFile && snapshot.length() > 0) { "the seed snapshot must exist on disk" }
            writeMarker(app, snapshot, installationId)

            // Reinstall side: the DB family and the device DataStore are what a fresh install
            // lacks; the snapshot and the portable DataStore are what Auto Backup delivers.
            check(app.deleteDatabase(NeutrodyneDatabase.FILE_NAME)) { "seed database delete failed" }
            check(
                File(app.filesDir, "datastore/${SettingsFile.DEVICE.storeName}.preferences_pb").delete(),
            ) { "the device DataStore file must be deleted" }
            check(
                File(app.filesDir, "datastore/${SettingsFile.PORTABLE.storeName}.preferences_pb")
                    .isFile,
            ) { "the portable DataStore file must survive the simulated reinstall" }

            // WorkManager's Configuration.Provider may already have materialised the production
            // graph during the snapshot write; its opener must still be unopened — band 100 of
            // the real launch does the open that decides OpenResult.created.
            check(
                app.graph.databaseOpener.openState.value is DatabaseOpenState.Pending,
            ) { "the production database must not be opened before its real start-up" }
        }
    }

    /** The library rows, in the order a user would have produced them. */
    private suspend fun seedLibrary(db: NeutrodyneDatabase) {
        val podcastIds =
            Fixture.PODCASTS.associate { spec ->
                spec.syncId = Uuid.random().toString()
                spec.feedKey to
                    db.podcastDao().insertPodcast(
                        PodcastEntity(
                            syncId = spec.syncId,
                            sourceType = SourceType.RSS,
                            feedUrl = spec.feedUrl,
                            feedKey = spec.feedKey,
                            title = spec.title,
                            author = spec.author,
                            link = spec.link,
                            artworkKey = "seed-artwork-${spec.feedKey}",
                            status = PodcastStatus.ACTIVE,
                            initialFetch = false,
                            subscribedAt = T_BASE,
                            guidCoverageSince = T_BASE,
                        ),
                    )
            }

        var lastGroupKey: String? = null
        val groupIds =
            Fixture.GROUPS.associate { spec ->
                lastGroupKey = ProtocolOrderKeys.after(lastGroupKey)
                val name = GroupNames.validate(spec.name) as GroupNames.Validation.Valid
                spec.uuid = Uuid.random().toString()
                val groupId =
                    db.groupDao().insert(
                        PodcastGroupEntity(
                            uuid = spec.uuid,
                            name = name.name,
                            nameKey = name.nameKey,
                            orderKey = lastGroupKey,
                            createdAt = T_BASE,
                            updatedAt = T_BASE,
                        ),
                    )
                var lastMemberKey: String? = null
                for (podcast in spec.members) {
                    lastMemberKey = ProtocolOrderKeys.after(lastMemberKey)
                    db.groupDao().insertMember(
                        PodcastGroupMemberEntity(
                            groupId = groupId,
                            podcastId = podcastIds.getValue(podcast.feedKey),
                            orderKey = lastMemberKey,
                            addedAt = T_BASE,
                        ),
                    )
                }
                spec.name to groupId
            }

        val episodeIds =
            Fixture.EPISODES.associate { spec ->
                val podcastId = podcastIds.getValue(spec.podcast.feedKey)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                EpisodeEntity(
                                    podcastId = podcastId,
                                    identityKey = spec.identityKey,
                                    guid = spec.guid,
                                    title = spec.title,
                                    pubDate = spec.pubDate,
                                    sortDate = spec.pubDate,
                                    feedOrder = spec.feedOrder,
                                    firstSeenAt = T_BASE,
                                    lastSeenAt = T_BASE,
                                    enclosureUrl = spec.enclosureUrl,
                                    enclosureType = spec.enclosureType,
                                    enclosureLength = spec.enclosureLength,
                                    durationMs = spec.durationMs,
                                    link = spec.link,
                                    contentHash = spec.contentHash,
                                ),
                            ),
                        ).single()
                spec.guid to episodeId
            }

        for (spec in Fixture.EPISODES) {
            val episodeId = episodeIds.getValue(spec.guid)
            if (spec.hasState) {
                db.episodeStateDao().upsert(
                    EpisodeStateEntity(
                        episodeId = episodeId,
                        startedAt = spec.startedAt,
                        playedAt = spec.playedAt,
                        playCount = spec.playCount,
                        lastPlayedAt = spec.lastPlayedAt,
                        isFavorite = spec.favorite,
                        measuredDurationMs = spec.measuredDurationMs,
                        updatedAt = spec.stateAt,
                    ),
                )
            }
            if (spec.positionMs > 0) {
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = episodeId,
                        positionMs = spec.positionMs,
                        durationMs = spec.durationMs,
                        positionSource = PositionSource.STREAM,
                        updatedAt = checkNotNull(spec.positionAt),
                    ),
                )
            }
        }

        var lastQueueKey: String? = null
        for (episode in Fixture.QUEUE) {
            lastQueueKey = ProtocolOrderKeys.after(lastQueueKey)
            db.queueDao().insert(
                QueueEntryEntity(
                    episodeId = episodeIds.getValue(episode.guid),
                    orderKey = lastQueueKey,
                    addedAt = T_BASE,
                ),
            )
        }

        val current = episodeIds.getValue(Fixture.SESSION_CURRENT.guid)
        db.playSessionDao().ensureNow(T_WRITE)
        db.playSessionDao().restoreSession(
            currentEpisodeId = current,
            contextType = ContextType.GROUP,
            contextId = groupIds.getValue(Fixture.SESSION_GROUP.name),
            contextOrder = FeedOrder.NEWEST_FIRST,
            contextFilterFlags = 0,
            contextMediaFilter = MediaFilter.ALL,
            contextMinSortDate = null,
            contextAnchorEpisodeId = current,
            contextAnchorSortDate = Fixture.SESSION_CURRENT.pubDate,
            onlyWhenIdle = false,
            now = T_WRITE,
        )
    }

    private fun writeMarker(
        app: NeutrodyneApplication,
        snapshot: File,
        installationId: String,
    ) {
        val marker =
            JSONObject()
                .put("snapshotSha256", sha256Hex(snapshot))
                .put("snapshotBytes", snapshot.length())
                .put("installationId", installationId)
                .put("portableKey", PORTABLE_KEY_NAME)
                .put("portableAfter", PORTABLE_VALUE_KEPT.name)
                .put("podcastCount", Fixture.PODCASTS.size)
                .put("groupCount", Fixture.GROUPS.size)
                .put("episodeCount", Fixture.EPISODES.size)
                .put("queueCount", Fixture.QUEUE.size)
                .put("seededAt", T_WRITE)
        val markerFile = File(app.filesDir, MARKER_FILE)
        markerFile.writeText(marker.toString())
        check(markerFile.isFile && markerFile.length() > 0) { "the seed marker must exist on disk" }
    }

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }

    private fun checkOutcome(
        what: String,
        outcome: Outcome<*, *>,
    ) {
        check(outcome is Outcome.Success) { "$what failed during seeding" }
    }

    /** The fixture every side of the gate agrees on; restored content is compared to this. */
    internal object Fixture {
        val PODCAST_ONE =
            PodcastSpec(
                feedUrl = "https://seed-one.invalid/feed.xml",
                title = "Seed Feed One",
                author = "Seed Author One",
                link = "https://seed-one.invalid/",
            )
        val PODCAST_TWO =
            PodcastSpec(
                feedUrl = "https://seed-two.invalid/feed.xml",
                title = "Seed Feed Two",
                author = "Seed Author Two",
                link = "https://seed-two.invalid/",
            )
        val PODCASTS = listOf(PODCAST_ONE, PODCAST_TWO)

        val EPISODE_PLAYED =
            EpisodeSpec(
                podcast = PODCAST_ONE,
                guid = "seed-episode-one-played",
                title = "Seed Episode Played",
                pubDate = T_BASE - 300_000,
                feedOrder = 2,
                enclosureUrl = "https://seed-one.invalid/ep-played.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 30_000_000L,
                durationMs = 1_800_000L,
                link = "https://seed-one.invalid/ep-played",
                playedAt = T_STATE_PLAYED,
                playCount = 1,
                lastPlayedAt = T_STATE_PLAYED,
                favorite = true,
                measuredDurationMs = 1_799_000L,
            )
        val EPISODE_IN_PROGRESS =
            EpisodeSpec(
                podcast = PODCAST_ONE,
                guid = "seed-episode-one-progress",
                title = "Seed Episode In Progress",
                pubDate = T_BASE - 200_000,
                feedOrder = 1,
                enclosureUrl = "https://seed-one.invalid/ep-progress.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 32_000_000L,
                durationMs = 2_000_000L,
                link = "https://seed-one.invalid/ep-progress",
                startedAt = T_POSITION,
                positionMs = 95_000L,
                positionAt = T_POSITION,
            )
        val EPISODE_QUEUED =
            EpisodeSpec(
                podcast = PODCAST_TWO,
                guid = "seed-episode-two-queued",
                title = "Seed Episode Queued",
                pubDate = T_BASE - 100_000,
                feedOrder = 2,
                enclosureUrl = "https://seed-two.invalid/ep-queued.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 28_000_000L,
                durationMs = 1_600_000L,
                link = "https://seed-two.invalid/ep-queued",
            )
        val EPISODE_PLAYED_TWO =
            EpisodeSpec(
                podcast = PODCAST_TWO,
                guid = "seed-episode-two-played",
                title = "Seed Episode Two Played",
                pubDate = T_BASE - 400_000,
                feedOrder = 1,
                enclosureUrl = "https://seed-two.invalid/ep-played.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 26_000_000L,
                durationMs = 1_500_000L,
                link = "https://seed-two.invalid/ep-played",
                playedAt = T_STATE_PLAYED_TWO,
                playCount = 2,
                lastPlayedAt = T_STATE_PLAYED_TWO,
            )
        val EPISODES =
            listOf(EPISODE_PLAYED, EPISODE_IN_PROGRESS, EPISODE_QUEUED, EPISODE_PLAYED_TWO)

        val GROUP_MAIN = GroupSpec("Seed Group", listOf(PODCAST_ONE, PODCAST_TWO))
        val GROUP_SIDE = GroupSpec("Seed Side", listOf(PODCAST_TWO))
        val GROUPS = listOf(GROUP_MAIN, GROUP_SIDE)

        /** Up next order — the queue's `orderKey` chain, not a date order. */
        val QUEUE = listOf(EPISODE_QUEUED, EPISODE_IN_PROGRESS)
        val SESSION_CURRENT = EPISODE_IN_PROGRESS
        val SESSION_GROUP = GROUP_MAIN
    }

    internal class PodcastSpec(
        val feedUrl: String,
        val title: String,
        val author: String,
        val link: String,
    ) {
        val feedKey: String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

        /** Minted at seed time; the restore adopts a free `syncId` verbatim (D33). */
        var syncId: String = ""
    }

    internal class GroupSpec(
        val name: String,
        val members: List<PodcastSpec>,
    ) {
        /** Minted at seed time; a MERGE restore keeps the backup's `uuid`. */
        var uuid: String = ""
    }

    /**
     * One seeded episode; the restore compares `title`, `guid`, `enclosureUrl`, `enclosureType`,
     * `durationMs`, `pubDate`, `link` plus the state and position columns it carried.
     */
    internal class EpisodeSpec(
        val podcast: PodcastSpec,
        val guid: String,
        val title: String,
        val pubDate: Long,
        val feedOrder: Int,
        val enclosureUrl: String,
        val enclosureType: String,
        val enclosureLength: Long,
        val durationMs: Long,
        val link: String,
        val playedAt: Long? = null,
        val playCount: Int = 0,
        val lastPlayedAt: Long? = null,
        val favorite: Boolean = false,
        val startedAt: Long? = null,
        val measuredDurationMs: Long? = null,
        val positionMs: Long = 0,
        val positionAt: Long? = null,
    ) {
        val identityKey: String =
            EpisodeKeys.keyFor(
                KeyInput(
                    guid = guid,
                    enclosureUrl = enclosureUrl,
                    title = title,
                    pubDate = pubDate,
                    link = link,
                ),
                EpisodeKeys.VERSION,
            )

        /** No `episode_state` row is seeded without at least one real state column. */
        val hasState =
            playedAt != null || playCount > 0 || lastPlayedAt != null || favorite ||
                startedAt != null || measuredDurationMs != null

        /** `episode_state.updatedAt` — the archive's `ts` merge evidence. */
        val stateAt = maxOf(playedAt ?: 0L, lastPlayedAt ?: 0L, startedAt ?: 0L, T_BASE)

        val contentHash = guid.hashCode().toLong()
    }
}

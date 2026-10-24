// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The post-restore assertions shared by `FirstLaunchRestoreDeviceTest` (in-process seed) and
 * `BmgrTransportDeviceTest` (real uninstall/reinstall): the `backup-restore` wait contract,
 * the logical fixture comparison, and the snapshot end-state rule.
 *
 * Random fixture identities arrive as explicit maps — the seeded run passes the in-process
 * `Fixture` values, the transport leg the `bmgrExpected` ones the host carried across the
 * uninstall — so neither side depends on a marker file or test memory surviving.
 */
internal object RestoredLibraryAsserts {
    /** 05's work table — the unique-work name, spelled out so no internal constant is needed. */
    const val RESTORE_WORK_NAME = "backup-restore"

    /** 02 Naming and types: lowercase canonical version-4 UUID. */
    val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    const val RESTORE_TIMEOUT_MS = 180_000L
    const val OPEN_TIMEOUT_MS = 60_000L
    const val POLL_MS = 500L

    /**
     * `backup-restore` carries the public `restore-session-{id}` tag; wait until the real
     * enqueue exists and reaches SUCCEEDED. A retrying work stays ENQUEUED and keeps polling;
     * FAILED/CANCELLED and the deadline fail loudly.
     */
    suspend fun awaitRestoreSucceeded(app: NeutrodyneApplication): Long {
        val workManager = WorkManager.getInstance(app)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESTORE_TIMEOUT_MS)
        while (true) {
            val infos =
                FirstLaunchRestoreChecks.awaitRestoreQuery(
                    workManager.getWorkInfosForUniqueWork(RESTORE_WORK_NAME),
                    deadline - System.nanoTime(),
                )
            FirstLaunchRestoreChecks.restoreSessionId(infos)?.let { return it }
            check(System.nanoTime() < deadline) {
                "backup-restore did not reach SUCCEEDED within ${RESTORE_TIMEOUT_MS} ms" +
                    " (states: ${infos.joinToString { it.state.name }})"
            }
            delay(POLL_MS)
        }
    }

    /** Logical content equality — every seeded row's carried fields, not row counts. */
    suspend fun assertLibraryEqualsFixture(
        app: NeutrodyneApplication,
        seedSyncIds: Map<String, String>,
        backupGroupUuids: Map<String, String>,
    ) {
        val fixture = FirstLaunchRestoreSeed.Fixture
        val db = app.graph.databaseOpener.requireDatabase()

        for (spec in fixture.PODCASTS) {
            val podcast =
                checkNotNull(db.podcastDao().byFeedKey(spec.feedKey)) {
                    "podcast ${spec.feedKey} must be restored"
                }
            assertEquals(spec.feedUrl, podcast.feedUrl)
            assertEquals(spec.title, podcast.title)
            assertEquals(spec.link, podcast.link)
            assertEquals(seedSyncIds.getValue(spec.feedKey), podcast.syncId)
        }

        for (spec in fixture.GROUPS) {
            val group =
                checkNotNull(
                    db.groupDao().groupsInOrder().firstOrNull { it.name == spec.name },
                ) { "group ${spec.name} must be restored" }
            // 05 B10 note: Merge adopts a backup uuid only in Replace; a restored new group
            // gets a fresh canonical v4 uuid, like a user-created one.
            check(backupGroupUuids.getValue(spec.name).isNotEmpty()) {
                "the seed must mint group ${spec.name}'s backup uuid"
            }
            assertNotEquals(backupGroupUuids.getValue(spec.name), group.uuid)
            assertTrue("group ${spec.name} uuid ${group.uuid}", CANONICAL_UUID.matches(group.uuid))
            assertEquals(
                spec.members.map { it.feedKey },
                db.groupDao().membersOf(group.id).map { member ->
                    checkNotNull(db.podcastDao().byId(member.podcastId)).feedKey
                },
            )
        }
        assertEquals(
            fixture.GROUPS.map { it.name },
            db.groupDao().groupsInOrder().map { it.name },
        )

        for (spec in fixture.EPISODES) {
            val podcastId = checkNotNull(db.podcastDao().byFeedKey(spec.podcast.feedKey)).id
            val episode =
                checkNotNull(db.episodeDao().byIdentityKey(podcastId, spec.identityKey)) {
                    "episode ${spec.identityKey} must be restored"
                }
            assertEquals(spec.title, episode.title)
            assertEquals(spec.guid, episode.guid)
            assertEquals(spec.pubDate, episode.pubDate)
            assertEquals(spec.enclosureUrl, episode.enclosureUrl)
            assertEquals(spec.enclosureType, episode.enclosureType)
            assertEquals(spec.durationMs, episode.durationMs)
            assertEquals(spec.link, episode.link)
            // A restored episode is a stub until its podcast's first fetch: `inFeed = false`
            // is the marker that the row came through the restore path, not an ingest.
            assertFalse("a restored episode is a stub (inFeed = 0)", episode.inFeed)

            val state = db.episodeStateDao().byEpisode(episode.id)
            if (spec.hasState) {
                checkNotNull(state) { "episode ${spec.guid} must carry its state row" }
                assertEquals(spec.playedAt, state.playedAt)
                assertEquals(spec.playCount, state.playCount)
                assertEquals(spec.lastPlayedAt, state.lastPlayedAt)
                assertEquals(spec.favorite, state.isFavorite)
                assertEquals(spec.measuredDurationMs, state.measuredDurationMs)
                assertEquals(spec.startedAt, state.startedAt)
            } else {
                assertNull("a state-free line must not gain a state row", state)
            }

            val position = db.positionDao().byEpisode(episode.id)
            if (spec.positionMs > 0) {
                checkNotNull(position) { "episode ${spec.guid} must carry its position" }
                assertEquals(spec.positionMs, position.positionMs)
                assertEquals(spec.durationMs, position.durationMs)
                assertEquals(spec.positionAt, position.updatedAt)
            } else {
                assertNull("a position-free line must not gain a position", position)
            }
        }

        assertEquals(
            "Up next must be restored in archive order",
            fixture.QUEUE.map { it.guid },
            db.queueDao().entries().map { checkNotNull(db.episodeDao().byId(it.episodeId)).guid },
        )

        val playSession = checkNotNull(db.playSessionDao().get())
        val currentId = checkNotNull(playSession.currentEpisodeId) { "the session's current episode must be restored" }
        assertEquals(
            fixture.SESSION_CURRENT.guid,
            checkNotNull(db.episodeDao().byId(currentId)).guid,
        )
        assertEquals(ContextType.GROUP, playSession.contextType)
        val contextGroup = checkNotNull(db.groupDao().groupById(checkNotNull(playSession.contextId)))
        assertEquals(fixture.SESSION_GROUP.name, contextGroup.name)
        assertEquals(currentId, playSession.contextAnchorEpisodeId)
        assertEquals(fixture.SESSION_CURRENT.pubDate, playSession.contextAnchorSortDate)
    }

    /**
     * Success retires `auto-snapshot.zip` to `restored-{epochMs}.zip`; an already-scheduled
     * periodic write may legitimately have replaced it afterwards — accept the matching retired
     * hash or a valid replacement from the current installation. Resample if the writer's
     * cleanup removes a listed retired file.
     */
    suspend fun assertSnapshotEndState(
        app: NeutrodyneApplication,
        seededHash: String,
        seededInstallationId: String,
    ) {
        val backupDir = File(app.filesDir, "backup")
        val auto = File(backupDir, "auto-snapshot.zip")

        FirstLaunchRestoreChecks.assertSnapshotEndState(
            seededHash = seededHash,
            sample = {
                val retired =
                    backupDir
                        .listFiles { file ->
                            file.name.startsWith("restored-") && file.name.endsWith(".zip")
                        }.orEmpty()
                FirstLaunchRestoreChecks.SnapshotSample(
                    retired.map { FirstLaunchRestoreChecks.readRetiredSnapshot(it) },
                    auto.isFile,
                )
            },
            validateReplacement = {
                FirstLaunchRestoreChecks.assertReplacementSnapshot(
                    auto,
                    seededHash,
                    seededInstallationId,
                    app.graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID),
                )
            },
        )
    }
}

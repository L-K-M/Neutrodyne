// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The genuine first-launch restore gate (05 First-launch restore; M3's `FirstLaunchRestoreTest`
 * row of the 05 test matrix — a logical restore on a cold-started app, on GMD API 26 and API 36).
 *
 * `NeutrodyneTestRunner` seeds `files/backup/auto-snapshot.zip` through a throwaway
 * `AndroidAppGraph` before production `onCreate` (see [FirstLaunchRestoreSeed]), then deletes
 * exactly the parts a reinstall loses — the database family and the *device* DataStore file —
 * and keeps what Auto Backup delivers: the snapshot and the portable settings PB. This test
 * then only observes the real cold start: band-100 `DatabaseOpenInitializer` reporting
 * `OpenResult.created`, band-110 `FirstLaunchRestoreInitializer` adopting the snapshot into a
 * `NEUTRODYNE_BACKUP` session and enqueueing `backup-restore`, the real `RestoreWorker`/
 * `RestoreRunner` MERGE of HISTORY and UP_NEXT, and the snapshot's retirement.
 *
 * Caveats kept honest by construction: without `firstLaunchSeed=restore` every case here skips
 * via [assumeTrue] (a skip is never counted as a pass — the seeded scope `first-launch-restore`
 * in `nightly.yml` is the authority); a `RETRY` or a hang can never be misreported (only a
 * `SUCCEEDED` `WorkInfo` carrying a `restore-session-{id}` tag ends the wait); the native fetch
 * that follows a commit may legitimately advance the session to `FETCHING`/`DONE`, so the
 * session-state assertion accepts the whole valid later range instead of racing `COMMITTED`.
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchRestoreDeviceTest {
    private val app: NeutrodyneApplication
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun requireSeededLaunch() {
        assumeTrue(
            "firstLaunchSeed=restore is required; an unseeded run skips instead of proving nothing",
            FirstLaunchRestoreSeed.isRequested(InstrumentationRegistry.getArguments()),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun firstLaunchRestoresTheSeededSnapshot() =
        runBlocking {
            val marker = SeedMarker.read(File(app.filesDir, FirstLaunchRestoreSeed.MARKER_FILE))
            val graph = app.graph

            // Band 100 must have found no database — `created` is the only honest
            // fresh-install signal (05; a DataStore flag would itself have been restored).
            val opened =
                withTimeout(OPEN_TIMEOUT_MS) {
                    graph.databaseOpener.openState
                        .filterIsInstance<DatabaseOpenState.Opened>()
                        .first()
                }
            assertTrue("the production open must report a created database", opened.result.created)

            // The real WorkManager restore — found by its public `restore-session-{id}` tag and
            // required to reach SUCCEEDED; FAILED/CANCELLED are immediate failures, never retried
            // into a pass, and the bounded poll turns a hang into a failure.
            val sessionId = awaitRestoreSucceeded()

            // The public session view carries the exact first-launch contract.
            val session =
                withTimeout(OPEN_TIMEOUT_MS) {
                    graph.importRepository
                        .observeSession(sessionId)
                        .filterNotNull()
                        .first()
                }
            assertEquals(ImportFormat.NEUTRODYNE_BACKUP, session.format)
            assertTrue(session.options.fromAndroidBackup)
            assertEquals(RestoreMode.MERGE, session.options.restoreMode)
            assertEquals(
                setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
                session.options.restoreCategories,
            )
            assertNotNull(session.options.restoreRequestedAt)
            assertNotNull(session.options.committedAt)
            assertTrue(
                "session state must be COMMITTED or a valid later fetch state, was ${session.state}",
                session.state in
                    listOf(ImportState.COMMITTED, ImportState.FETCHING, ImportState.DONE),
            )
            assertEquals(
                "one import_item per archived podcast",
                FirstLaunchRestoreSeed.Fixture.PODCASTS.size,
                session.counts.values.sum(),
            )

            assertLibraryEqualsFixture(app)

            // SETTINGS is intentionally outside the first-launch request: Auto Backup restored
            // the portable PB itself, so the post-snapshot value must be what survives — and a
            // missing PB would still read the (different) default, so this also proves retention.
            assertEquals(
                "the post-snapshot portable value must survive the restore",
                FirstLaunchRestoreSeed.PORTABLE_VALUE_KEPT,
                graph.settingsRepository.get(BackupSettingKeys.OPML_LAYOUT),
            )
            val portable =
                File(
                    app.filesDir,
                    "datastore/${SettingsFile.PORTABLE.storeName}.preferences_pb",
                )
            assertTrue("the portable DataStore file must be retained", portable.isFile && portable.length() > 0)
            // The device file held the minted installationId; a fresh install must never carry it.
            assertNotEquals(
                "the seeded installation ID must not survive the device-store deletion",
                marker.installationId,
                graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID),
            )

            // The adopted cache payload must be byte-identical to the archive the seed wrote —
            // the proof that PayloadStore adopted the real snapshot rather than a derivative.
            val payload = File(app.cacheDir, "import/$sessionId.bin")
            assertTrue("the adopted payload must exist at cache/import/<session>.bin", payload.isFile)
            assertEquals(
                "the adopted payload must hash to the seeded archive",
                marker.snapshotSha256,
                sha256Hex(payload),
            )

            assertSnapshotEndState(marker)
        }

    /**
     * `backup-restore` carries the public `restore-session-{id}` tag; wait until the real
     * enqueue exists and reaches SUCCEEDED. A retrying work stays ENQUEUED and keeps polling;
     * FAILED/CANCELLED and the deadline fail loudly.
     */
    private suspend fun awaitRestoreSucceeded(): Long {
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
    private suspend fun assertLibraryEqualsFixture(app: NeutrodyneApplication) {
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
            assertEquals(spec.syncId, podcast.syncId)
        }

        for (spec in fixture.GROUPS) {
            val group =
                checkNotNull(
                    db.groupDao().groupsInOrder().firstOrNull { it.name == spec.name },
                ) { "group ${spec.name} must be restored" }
            // 05 B10 note: Merge adopts a backup uuid only in Replace; a restored new group
            // gets a fresh canonical v4 uuid, like a user-created one.
            check(spec.uuid.isNotEmpty()) { "the seed must mint group ${spec.name}'s backup uuid" }
            assertNotEquals(spec.uuid, group.uuid)
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
            FirstLaunchRestoreSeed.Fixture.SESSION_CURRENT.guid,
            checkNotNull(db.episodeDao().byId(currentId)).guid,
        )
        assertEquals(ContextType.GROUP, playSession.contextType)
        val contextGroup = checkNotNull(db.groupDao().groupById(checkNotNull(playSession.contextId)))
        assertEquals(FirstLaunchRestoreSeed.Fixture.SESSION_GROUP.name, contextGroup.name)
        assertEquals(currentId, playSession.contextAnchorEpisodeId)
        assertEquals(FirstLaunchRestoreSeed.Fixture.SESSION_CURRENT.pubDate, playSession.contextAnchorSortDate)
    }

    /**
     * Success retires `auto-snapshot.zip` to `restored-{epochMs}.zip`; an already-scheduled
     * periodic write may legitimately have replaced it afterwards — accept the matching retired
     * hash or a valid replacement from the current installation. Resample if the writer's
     * cleanup removes a listed retired file.
     */
    private suspend fun assertSnapshotEndState(marker: SeedMarker) {
        val backupDir = File(app.filesDir, "backup")
        val auto = File(backupDir, "auto-snapshot.zip")

        FirstLaunchRestoreChecks.assertSnapshotEndState(
            seededHash = marker.snapshotSha256,
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
                    marker.snapshotSha256,
                    marker.installationId,
                    app.graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID),
                )
            },
        )
    }

    private fun sha256Hex(file: File): String = FirstLaunchRestoreChecks.sha256Hex(file.readBytes())

    /** The seed's `files/first-launch-seed.json` record. */
    private class SeedMarker(
        val snapshotSha256: String,
        val snapshotBytes: Long,
        val installationId: String,
    ) {
        companion object {
            fun read(file: File): SeedMarker {
                check(file.isFile) { "the seed marker must exist — was the seed skipped?" }
                val json = JSONObject(file.readText())
                return SeedMarker(
                    snapshotSha256 = json.getString("snapshotSha256"),
                    snapshotBytes = json.getLong("snapshotBytes"),
                    installationId = json.getString("installationId"),
                )
            }
        }
    }

    private companion object {
        /** 05's work table — the unique-work name, spelled out so no internal constant is needed. */
        const val RESTORE_WORK_NAME = "backup-restore"

        /** 02 Naming and types: lowercase canonical version-4 UUID. */
        val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val RESTORE_TIMEOUT_MS = 180_000L
        const val OPEN_TIMEOUT_MS = 60_000L
        const val POLL_MS = 500L

        /** Covers the sequential worst case of every bounded wait plus the assertion pass. */
        const val TEST_TIMEOUT_MS = 400_000L
    }
}

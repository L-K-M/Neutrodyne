// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
 * Caveats kept honest by construction: without `firstLaunchSeed=restore` the runner deselects
 * this class; [assumeTrue] also guards execution (neither is a pass; the seeded scope `first-launch-restore`
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
            val sessionId = RestoredLibraryAsserts.awaitRestoreSucceeded(app)

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

            RestoredLibraryAsserts.assertLibraryEqualsFixture(
                app,
                FirstLaunchRestoreSeed.Fixture.PODCASTS.associate { it.feedKey to it.syncId },
                FirstLaunchRestoreSeed.Fixture.GROUPS.associate { it.name to it.uuid },
            )

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
                FirstLaunchRestoreChecks.sha256Hex(payload.readBytes()),
            )

            RestoredLibraryAsserts.assertSnapshotEndState(app, marker.snapshotSha256, marker.installationId)
        }

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
        const val OPEN_TIMEOUT_MS = RestoredLibraryAsserts.OPEN_TIMEOUT_MS

        /** Covers the sequential worst case of every bounded wait plus the assertion pass. */
        const val TEST_TIMEOUT_MS = 400_000L
    }
}

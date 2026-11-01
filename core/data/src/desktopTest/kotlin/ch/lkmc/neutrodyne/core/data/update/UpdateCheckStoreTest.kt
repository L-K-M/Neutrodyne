// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Sink
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckStoreTest {
    private lateinit var root: File

    @Before fun setup() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-update-store")
                .toFile()
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private val snapshot get() =
        UpdateStoreSnapshot(
            manifest(extra = serverJson()),
            TestClock.DEFAULT_NOW,
            TestClock.DEFAULT_NOW,
            TestClock.DEFAULT_NOW + DAY,
            2,
        )

    private fun TestScope.newStore(fileSystem: FileSystem = FileSystem.SYSTEM): UpdateCheckStore =
        UpdateCheckStore(
            storagePathsFor(root),
            fileSystem,
            UpdateManifestParser(updateBuild()),
            StandardTestDispatcher(testScheduler),
        )

    @Test fun missingFileIsNotACheckAndDoesNotCreateAnything() =
        runTest {
            val store =
                newStore()
            assertEquals(Outcome.Success(null), store.read())
            assertFalse(root.resolve("data/updates").exists())
        }

    @Test fun fullWireManifestAndScheduleRoundTripInNoBackupDirectory() =
        runTest {
            val store =
                newStore()
            assertEquals(Outcome.Success(Unit), store.write(snapshot))
            assertEquals(Outcome.Success(snapshot), store.read())
            assertTrue(root.resolve("data/updates/last-check.json").isFile)
            assertFalse(root.resolve("data/updates/last-check.json.tmp").exists())
            assertFalse(root.resolve("config/updates").exists())
            assertFalse(root.resolve("cache/updates").exists())
        }

    @Test fun metadataOnlySnapshotRoundTrips() =
        runTest {
            val store =
                newStore()
            val empty = snapshot.copy(manifest = null, lastCheckAtMs = null, retryAttempt = 0)
            store.write(empty)
            assertEquals(Outcome.Success(empty), store.read())
        }

    @Test fun interruptedRenameKeepsOldManifestAndScheduleTogether() =
        runTest {
            val real =
                newStore()
            real.write(snapshot)
            val interrupted =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ): Unit = throw IOException("Simulated death before rename")
                }
            val store =
                newStore(interrupted)
            val next = snapshot.copy(manifest = manifest("1.0.1"), nextDueAtMs = snapshot.nextDueAtMs + HOUR)
            assertEquals(Outcome.Failure(UpdateStoreError.IO), store.write(next))
            assertEquals(Outcome.Success(snapshot), real.read())
            assertFalse(root.resolve("data/updates/last-check.json.tmp").exists())
        }

    @Test fun interruptedBodyWriteKeepsOldSnapshot() =
        runTest {
            val real =
                newStore()
            real.write(snapshot)
            val interrupted =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun sink(
                        file: Path,
                        mustCreate: Boolean,
                    ): Sink =
                        object : ForwardingSink(super.sink(file, mustCreate)) {
                            override fun write(
                                source: Buffer,
                                byteCount: Long,
                            ) {
                                super.write(source, minOf(20, byteCount))
                                throw IOException("Interrupted cache write")
                            }
                        }
                }
            val store =
                newStore(interrupted)
            assertEquals(
                Outcome.Failure(UpdateStoreError.IO),
                store.write(snapshot.copy(nextDueAtMs = snapshot.nextDueAtMs + HOUR)),
            )
            assertEquals(Outcome.Success(snapshot), real.read())
        }

    @Test fun orphanedStagingFileIsIgnoredAndReplacedOnNextWrite() =
        runTest {
            val store =
                newStore()
            val staged = root.resolve("data/updates/last-check.json.tmp")
            staged.parentFile.mkdirs()
            staged.writeText("{truncated")
            assertEquals(Outcome.Success(null), store.read())
            store.write(snapshot)
            assertEquals(Outcome.Success(snapshot), store.read())
            assertFalse(staged.exists())
        }

    @Test fun cacheLinksAreRevalidatedOnEveryRead() =
        runTest {
            val store =
                newStore()
            store.write(snapshot)
            val file = root.resolve("data/updates/last-check.json")
            val valid = file.readText()
            listOf(
                valid.replace("github.com", "evil.org"),
                valid.replace("/releases/download/", "/x/../releases/download/"),
                valid.replace("/releases/tag/", "/releases/%2e%2e/tag/"),
            ).forEach { bad ->
                file.writeText(bad)
                assertEquals(Outcome.Failure(UpdateStoreError.CORRUPT), store.read())
            }
        }

    @Test fun malformedTruncatedHugeAndInvalidMetadataAreRecoverable() =
        runTest {
            val store =
                newStore()
            store.write(snapshot)
            val file = root.resolve("data/updates/last-check.json")
            val valid = file.readText()
            listOf(
                "",
                "{",
                "null",
                "[]",
                valid.dropLast(10),
                "a".repeat(MAX_UPDATE_MANIFEST_BYTES * 2 + 1),
                valid.replace("\"schema\":1", "\"schema\":2"),
                valid.replace("\"retryAttempt\":2", "\"retryAttempt\":7"),
                valid.replace("\"retryAttempt\":2", "\"retryAttempt\":\"2\""),
                valid.replace("\"firstStartedAtMs\":${snapshot.firstStartedAtMs}", "\"firstStartedAtMs\":-1"),
            ).forEach { bad ->
                file.writeText(bad)
                assertEquals(Outcome.Failure(UpdateStoreError.CORRUPT), store.read())
            }
        }

    @Test fun invalidUtf8CacheIsCorruptRatherThanAnIoFailure() =
        runTest {
            val store = newStore()
            store.write(snapshot)
            root.resolve("data/updates/last-check.json").writeBytes(byteArrayOf(0xff.toByte()))
            assertEquals(Outcome.Failure(UpdateStoreError.CORRUPT), store.read())
        }

    @Test fun readIoFailureIsDistinctFromCorruption() =
        runTest {
            val real =
                newStore()
            real.write(snapshot)
            val broken =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun source(file: Path): okio.Source = throw IOException("Cannot read")
                }
            val store =
                newStore(broken)
            assertEquals(Outcome.Failure(UpdateStoreError.IO), store.read())
        }
}

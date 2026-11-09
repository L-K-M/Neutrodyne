// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.FakeEngineStorePaths
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledEngineStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val clock = TestClock()

    @org.junit.After fun allowCleanup() {
        temporary.root.walkTopDown().forEach { it.setWritable(true, true) }
    }

    @Test fun statusDoesNotExtractAndPreparationPublishesOnlyAfterSuccess() =
        runTest {
            val store = store()
            store.loadStatus()
            assertFalse(paths.root.toFile().exists())
            assertNull(store.hostPrepared()?.libDir)
            assertEquals(EnginePreparation.UNPREPARED, store.preparation.value)
            val prepared = store.prepareBundled()
            assertEquals(prepared.libDir, store.hostPrepared()?.libDir)
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            assertTrue(File(prepared.libDir.toFile(), "yt_dlp/version.py").isFile)
            val versionFile = File(prepared.libDir.toFile(), "yt_dlp/version.py").toPath()
            if (Files.getFileStore(versionFile).supportsFileAttributeView("posix")) {
                assertFalse(OWNER_WRITE in Files.getPosixFilePermissions(versionFile))
            } else {
                assertFalse(versionFile.toFile().canWrite())
            }
            assertTrue(
                paths.root
                    .toFile()
                    .resolve("active.json")
                    .isFile,
            )
            assertTrue(
                paths.root
                    .toFile()
                    .resolve("versions/2026.08.19/yt-dlp")
                    .isFile,
            )
            assertTrue(paths.ytDlpCacheDir.toFile().isDirectory)
        }

    @Test fun successfulCompileSurvivesRestartAndDoesNotReadZip() =
        runTest {
            val first = store().prepareBundled()
            compileFixture(first)
            paths.zip = byteArrayOf() // Marker/active status alone suffices at startup and on repeat prepare.
            val restarted = store()
            restarted.loadStatus()
            assertEquals(EnginePreparation.COMPILED, restarted.preparation.value)
            assertEquals(first, restarted.prepareBundled())
            assertEquals(first.libDir, restarted.hostPrepared()?.libDir)
        }

    @Test fun appAndPythonMinorChangesReextractSources() =
        runTest {
            compileFixture(store().prepareBundled())
            val appUpdate = store(2)
            appUpdate.loadStatus()
            assertNull(appUpdate.hostPrepared()?.libDir)
            val updated = appUpdate.prepareBundled()
            assertTrue(File(updated.libDir.toFile(), "yt_dlp/__init__.py").exists())
            compileFixture(updated)
            paths.pythonMinor = "3.15"
            val minorUpdate = store(2)
            minorUpdate.loadStatus()
            assertEquals(EnginePreparation.UNPREPARED, minorUpdate.preparation.value)
            assertEquals("3.15", minorUpdate.prepareBundled().marker.pythonMinor)
            assertFalse(File(updated.libDir.toFile(), ".compiled-3.14").exists())
        }

    @Test fun newBundleAndReinstallUseOnlyBundledVersion() =
        runTest {
            compileFixture(store().prepareBundled())
            arm(paths, "2026.09.01")
            val next = store(2).prepareBundled()
            assertEquals("2026.09.01", next.marker.engineVersion)
            assertEquals(listOf("2026.09.01"), File(paths.root.toFile(), "versions").list()!!.toList())
            erase(paths.root.toFile())
            val reinstalled = store(2)
            reinstalled.loadStatus()
            assertEquals(next, reinstalled.prepareBundled())
        }

    @Test fun tornActiveAndInterruptedCompileRecoverFromApk() =
        runTest {
            val first = store().prepareBundled()
            File(first.libDir.toFile(), "yt_dlp/__init__.py").delete()
            File(first.libDir.toFile(), ".compiled-3.14").writeText("{torn")
            File(paths.root.toFile(), "active.json").apply {
                setWritable(true, true)
                writeText("{torn")
            }
            File(paths.root.toFile(), "active.json.tmp").writeText("ignored")
            val next = store()
            next.loadStatus()
            assertNull(next.hostPrepared()?.libDir)
            next.prepareBundled()
            assertTrue(File(first.libDir.toFile(), "yt_dlp/__init__.py").isFile)
            assertFalse(File(first.libDir.toFile(), ".compiled-3.14").exists())
            assertFalse(File(paths.root.toFile(), "active.json.tmp").exists())
        }

    @Test fun tornMarkerAndMissingBytecodeNeverReportCompiled() =
        runTest {
            val prepared = store().prepareBundled()
            compileFixture(prepared)
            prepared.libDir.toFile().setWritable(true, true)
            val marker = File(prepared.libDir.toFile(), ".compiled-3.14")
            marker.setWritable(true, true)
            marker.writeText("{}")
            val next = store()
            next.loadStatus()
            assertEquals(EnginePreparation.UNPREPARED, next.preparation.value)
            for (name in listOf("yt_dlp/version.pyc", "yt_dlp_ejs/_version.pyc")) {
                compileFixture(next.prepareBundled())
                val bytecode = File(prepared.libDir.toFile(), name)
                bytecode.parentFile.setWritable(true, true)
                bytecode.setWritable(true, true)
                assertTrue(bytecode.delete())
                next.loadStatus()
                assertNull(next.hostPrepared()?.libDir)
            }
        }

    @Test fun concurrentPreparationsShareOneDirectoryAndNeverPublishPartialTree() =
        runTest {
            val store = store()
            val jobs = List(10) { async { store.prepareBundled() } }
            assertNull(store.hostPrepared()?.libDir)
            val observer =
                async(kotlinx.coroutines.Dispatchers.Default) {
                    while (jobs.any { !it.isCompleted }) {
                        store.hostPrepared()?.libDir?.let { lib ->
                            assertTrue(File(lib.toFile(), "yt_dlp/version.py").isFile)
                            assertTrue(File(lib.toFile(), "yt_dlp_ejs/_version.py").isFile)
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            val results = jobs.awaitAll()
            observer.await()
            assertEquals(1, results.distinct().size)
            File(results.first().libDir.toFile(), "in-progress.pyc").writeText("worker")
            assertEquals(results.first(), store.prepareBundled())
            assertTrue(File(results.first().libDir.toFile(), "in-progress.pyc").exists())
        }

    @Test fun failedHashDoesNotPublishAndRetryRecovers() =
        runTest {
            paths.zip = paths.zip + 0
            val store = store()
            assertFails { store.prepareBundled() }
            assertEquals(EnginePreparation.FAILED, store.preparation.value)
            assertNull(store.hostPrepared()?.libDir)
            assertFalse(File(paths.root.toFile(), "active.json").exists())
            arm(paths)
            assertNotNull(store.prepareBundled())
        }

    @Test fun markerCannotBeWrittenBeforeCompileAndSymlinkRootIsRejected() =
        runTest {
            val prepared = store().prepareBundled()
            assertFails { EngineCompileMarkers.write(prepared) }
            val alternate = temporary.newFolder()
            erase(paths.root.toFile())
            Files.createSymbolicLink(paths.root.toFile().toPath(), alternate.toPath())
            assertFails { store().prepareBundled() }
            assertTrue(alternate.list()!!.isEmpty())
        }

    @Test fun beginTrialReportsTheCandidatesOwnCompileStateAndEndingsRestoreTheSavedOne() =
        runTest {
            val store = store()
            val active = store.prepareBundled()
            compileFixture(active)
            store.loadStatus()
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            val candidateZip = fixtureZip(version = "2026.09.01")
            val candidate = store.stageVersion("2026.09.01", candidateZip, "0.8.0", 4)
            // A staged candidate without its compile marker must not claim COMPILED: the next
            // bind compiles it, so the trial is PREPARING until the host proves otherwise.
            store.beginTrial("2026.09.01")
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            assertEquals(candidate, store.hostPrepared())
            assertEquals(candidate, store.active.value.trial)
            store.clearTrial()
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            assertEquals(active, store.hostPrepared())
            assertNull(store.active.value.trial)
            assertEquals("2026.09.01", store.active.value.staged)
            assertTrue(
                store.active.value.rejected
                    .isEmpty(),
            )
            // A candidate that already carries its marker is served without a compile budget.
            compileFixture(candidate)
            store.beginTrial("2026.09.01")
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            assertEquals(candidate, store.hostPrepared())
            // Abort records the verdict, removes the tree and restores the active preparation.
            store.abortTrial("2026.09.01", EngineRejectReason.SELFTEST_FAILED, 4, clock.now())
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            assertEquals(active, store.hostPrepared())
            assertNull(store.active.value.trial)
            assertFalse(File(paths.root.toFile(), "versions/2026.09.01").exists())
            assertEquals(
                EngineRejectReason.SELFTEST_FAILED,
                store.active.value.rejected
                    .single()
                    .reason,
            )
        }

    @Test fun trialOverAnUncompiledActiveRestoresPreparingOnClear() =
        runTest {
            val store = store()
            val bundled = store.prepareBundled()
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 1)
            store.beginTrial("2026.09.01")
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            store.clearTrial()
            // The active bundle still has no marker: cancellation must not promote it.
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            assertEquals(bundled, store.hostPrepared())
            assertTrue(
                store.active.value.rejected
                    .isEmpty(),
            )
        }

    @Test fun prepareBundledBetweenStagingAndTrialKeepsTheResumableCandidate() =
        runTest {
            val store = store()
            // Fresh boot, no bundled prepare yet: the updater stages while its run waits
            // for engine idle, and the delayed engine-prepare worker fires in between.
            val candidate = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            store.prepareBundled()
            assertTrue(File(candidate.libDir.toFile(), "yt_dlp/version.py").isFile)
            assertEquals("2026.09.01", store.active.value.staged)
            // The run reaches its trial over the kept tree, and a no-verdict cancellation
            // keeps the candidate resumable without any rejection.
            assertEquals(candidate, store.beginTrial("2026.09.01"))
            store.clearTrial()
            assertTrue(File(candidate.libDir.toFile(), "yt_dlp/version.py").isFile)
            assertEquals("2026.09.01", store.active.value.staged)
            assertTrue(
                store.active.value.rejected
                    .isEmpty(),
            )
        }

    @Test fun prepareBundledDuringATrialKeepsTheCandidateAndTheTrialsPreparation() =
        runTest {
            val store = store()
            val active = store.prepareBundled()
            compileFixture(active)
            store.loadStatus()
            val first = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            store.beginTrial("2026.09.01")
            compileFixture(first)
            store.commitTrial(clock.now(), null, false)
            // A second update trials a newer candidate over the downloaded active; the
            // delayed worker's prepareBundled lands while the trial host compiles it.
            val candidate = store.stageVersion("2026.09.15", fixtureZip(version = "2026.09.15"), "0.8.0", 5)
            store.beginTrial("2026.09.15")
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            store.prepareBundled()
            assertTrue(File(candidate.libDir.toFile(), "yt_dlp/version.py").isFile)
            assertEquals(candidate, store.hostPrepared())
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            // The trial still ends honestly: the candidate compiles and commits.
            compileFixture(candidate)
            store.commitTrial(clock.now(), null, false)
            assertEquals("2026.09.15", store.active.value.current)
            assertEquals("2026.09.01", store.active.value.previous)
            assertEquals(candidate, store.hostPrepared())
            assertTrue(
                store.active.value.rejected
                    .isEmpty(),
            )
        }

    @Test fun loadStatusDuringATrialReportsTheServedCandidatesOwnState() =
        runTest {
            val store = store()
            val active = store.prepareBundled()
            compileFixture(active)
            store.loadStatus()
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            val candidate = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            store.beginTrial("2026.09.01")
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            // A prepare worker whose active PING finished late calls loadStatus after
            // beginTrial: the compiled active must not flip the serving trial to COMPILED,
            // or the trial bind would snapshot the 15 s start budget for an uncompiled
            // candidate that owes the 120 s first-compile one.
            store.loadStatus()
            assertEquals(EnginePreparation.PREPARING, store.preparation.value)
            assertEquals(candidate, store.hostPrepared())
            // Once the trial host has written the candidate's marker, the same call
            // reports COMPILED: the state of the tree actually served.
            compileFixture(candidate)
            store.loadStatus()
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            store.clearTrial()
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            assertEquals(active, store.hostPrepared())
        }

    @Test fun latestStageSurvivesPreparationAfterAnAbandonedHigherVersion() =
        runTest {
            val store = store()
            store.prepareBundled()
            for ((sequence, version) in listOf("2026.09.01", "2026.09.15").withIndex()) {
                val candidate =
                    store.stageVersion(
                        version,
                        fixtureZip(version = version),
                        "0.8.0",
                        sequence.toLong() + 1,
                    )
                store.beginTrial(version)
                compileFixture(candidate)
                store.commitTrial(clock.now(), null, false)
            }
            val abandoned = store.stageVersion("2026.11.15", fixtureZip(version = "2026.11.15"), "0.8.0", 3)
            store.beginTrial("2026.11.15")
            store.clearTrial()
            // A later approved manifest may target an older candidate which is still
            // newer than current. The newest staging operation owns the pending tree.
            val replacement = store.stageVersion("2026.11.01", fixtureZip(version = "2026.11.01"), "0.8.0", 4)
            assertEquals("2026.11.01", store.active.value.staged)
            store.prepareBundled()
            assertTrue(File(replacement.libDir.toFile(), "yt_dlp/version.py").isFile)
            assertFalse(abandoned.libDir.toFile().exists())
            assertEquals("2026.09.15", store.active.value.current)
            assertEquals("2026.09.01", store.active.value.previous)
            assertTrue(
                store.active.value.rejected
                    .isEmpty(),
            )
            store.beginTrial("2026.11.01")
            compileFixture(replacement)
            store.commitTrial(clock.now(), null, false)
            assertEquals("2026.11.01", store.active.value.current)
            assertEquals("2026.09.15", store.active.value.previous)
            assertEquals(
                setOf("2026.08.19", "2026.09.15", "2026.11.01"),
                File(paths.root.toFile(), "versions").list()!!.toSet(),
            )
        }

    @Test fun compileProtectionMarksEveryEntryAndRemovalRestoresThem() =
        runTest {
            val store = store()
            store.prepareBundled()
            val candidate = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            compileFixture(candidate)
            val lib = candidate.libDir.toFile()
            // The marker is written last, certifying a source-free tree whose files and
            // directories are all protected against modification.
            assertTrue(File(lib, ".compiled-3.14").isFile)
            assertEquals(candidate.marker, EngineCompileMarkers.stored(candidate.libDir, paths.pythonMinor))
            assertTrue(EngineCompileMarkers.matches(candidate.libDir, candidate.marker))
            val fileStore = Files.getFileStore(lib.toPath())
            lib.walkTopDown().forEach {
                if (fileStore.supportsFileAttributeView("posix")) {
                    val writes = setOf(OWNER_WRITE, GROUP_WRITE, OTHERS_WRITE)
                    assertTrue(
                        Files.getPosixFilePermissions(it.toPath()).none { perm -> perm in writes },
                        "$it kept a write bit",
                    )
                } else if (fileStore.supportsFileAttributeView("dos")) {
                    assertEquals(
                        true,
                        Files.getAttribute(it.toPath(), "dos:readonly", NOFOLLOW_LINKS),
                        "$it carries no read-only marker",
                    )
                } else if (it.isFile) {
                    assertFalse(it.canWrite(), "$it stayed writable")
                }
            }
            // Removal restores write access before deleting, so an abort leaves no tree.
            store.beginTrial("2026.09.01")
            store.abortTrial("2026.09.01", EngineRejectReason.SELFTEST_FAILED, 4, clock.now())
            assertFalse(File(paths.root.toFile(), "versions/2026.09.01").exists())
            assertEquals(listOf("2026.08.19"), File(paths.root.toFile(), "versions").list()!!.toList())
        }

    @Test fun dosStoreProtectionMarksDirectories() {
        assumeTrue(Files.getFileStore(temporary.root.toPath()).supportsFileAttributeView("dos"))
        runTest {
            val store = store()
            store.prepareBundled()
            val candidate = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            compileFixture(candidate)
            val lib = candidate.libDir.toFile()
            // JDK File.setReadOnly/setWritable skip Windows directories entirely, so on a
            // DOS store every directory must carry the platform's read-only marker.
            val directories = lib.walkTopDown().filter { it.isDirectory }.toList()
            assertTrue(directories.size >= 3)
            directories.forEach {
                assertEquals(true, Files.getAttribute(it.toPath(), "dos:readonly", NOFOLLOW_LINKS), "$it")
            }
        }
    }

    @Test fun abortRestoresADosProtectedTree() {
        assumeTrue(Files.getFileStore(temporary.root.toPath()).supportsFileAttributeView("dos"))
        runTest {
            val store = store()
            store.prepareBundled()
            val candidate = store.stageVersion("2026.09.01", fixtureZip(version = "2026.09.01"), "0.8.0", 4)
            // A tree protected by a run that died before writing its marker — files and
            // directories alike — must still lose write protection and be removed.
            candidate.libDir.toFile().walkTopDown().forEach {
                Files.setAttribute(it.toPath(), "dos:readonly", true, NOFOLLOW_LINKS)
            }
            store.beginTrial("2026.09.01")
            store.abortTrial("2026.09.01", EngineRejectReason.SELFTEST_FAILED, 4, clock.now())
            assertFalse(File(paths.root.toFile(), "versions/2026.09.01").exists())
            assertEquals(listOf("2026.08.19"), File(paths.root.toFile(), "versions").list()!!.toList())
        }
    }

    @Test fun genuinePinnedZipExtractsWithShebangAndExpectedInventory() {
        val archive = File("../ytdlp/engine/yt-dlp")
        val target = File(temporary.root, "real-lib")
        BundledZip.extract(archive, target, "2026.08.19", "0.8.0")
        assertEquals(1053, target.walkTopDown().count { it.isFile })
        assertTrue(File(target, "yt_dlp_ejs/yt/solver/lib.min.js").readText().contains("Version: 6.1.4"))
    }

    private fun store(code: Int = 1) = BundledEngineStore(paths, code, clock)

    private val paths by lazy {
        FakeEngineStorePaths().apply {
            root =
                temporary.root
                    .canonicalFile
                    .resolve("ytdlp")
                    .path
                    .toPath()
            ytDlpCacheDir =
                temporary.root
                    .canonicalFile
                    .resolve("cache/yt-dlp")
                    .path
                    .toPath()
            arm(this)
        }
    }

    private fun arm(
        paths: FakeEngineStorePaths,
        version: String = "2026.08.19",
    ) {
        paths.zip = fixtureZip(version = version)
        val digest = MessageDigest.getInstance("SHA-256").digest(paths.zip)
        val hash = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        paths.metadata =
            """
            {"version":"$version", "tag":"$version",
             "sha256":"$hash", "ejsVersion":"0.8.0"}
            """.trimIndent()
    }

    private fun compileFixture(prepared: PreparedEngine) {
        prepared.libDir.toFile().walkTopDown().filter { it.extension == "py" }.forEach {
            File(it.parentFile, "${it.name}c").writeText("fake bytecode, no Python executed")
            assertTrue(it.delete())
        }
        EngineCompileMarkers.write(prepared)
    }

    private fun erase(file: File) {
        file.walkTopDown().forEach { it.setWritable(true, true) }
        file.walkBottomUp().forEach { it.delete() }
    }
}

internal fun fixtureZip(
    extras: Map<String, ByteArray> = emptyMap(),
    version: String = "2026.08.19",
): ByteArray {
    val info = "ORIGIN = 'yt-dlp/yt-dlp'\nCHANNEL = 'stable'\n" + "__version__ = '$version'\n"
    val entries =
        linkedMapOf(
            "__main__.py" to "# inert fixture".toByteArray(),
            "yt_dlp/__init__.py" to "# inert fixture".toByteArray(),
            "yt_dlp/version.py" to info.toByteArray(),
            "yt_dlp_ejs/__init__.py" to "# inert fixture".toByteArray(),
            "yt_dlp_ejs/_version.py" to "__version__ = version = '0.8.0'\n".toByteArray(),
        ) + extras
    return ByteArrayOutputStream()
        .also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}

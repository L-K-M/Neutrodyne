// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.FakeEngineStorePaths
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
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

    @org.junit.After fun allowCleanup() {
        temporary.root.walkTopDown().forEach { it.setWritable(true, true) }
    }

    @Test fun statusDoesNotExtractAndPreparationPublishesOnlyAfterSuccess() =
        runTest {
            val store = store()
            store.loadStatus()
            assertFalse(paths.root.toFile().exists())
            assertNull(store.hostLibDir())
            assertEquals(EnginePreparation.UNPREPARED, store.preparation.value)
            val prepared = store.prepareBundled()
            assertEquals(prepared.libDir, store.hostLibDir())
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
            assertEquals(first.libDir, restarted.hostLibDir())
        }

    @Test fun appAndPythonMinorChangesReextractSources() =
        runTest {
            compileFixture(store().prepareBundled())
            val appUpdate = store(2)
            appUpdate.loadStatus()
            assertNull(appUpdate.hostLibDir())
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
            assertNull(next.hostLibDir())
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
                assertNull(next.hostLibDir())
            }
        }

    @Test fun concurrentPreparationsShareOneDirectoryAndNeverPublishPartialTree() =
        runTest {
            val store = store()
            val jobs = List(10) { async { store.prepareBundled() } }
            assertNull(store.hostLibDir())
            val observer =
                async(kotlinx.coroutines.Dispatchers.Default) {
                    while (jobs.any { !it.isCompleted }) {
                        store.hostLibDir()?.let { lib ->
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
            assertNull(store.hostLibDir())
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

    @Test fun genuinePinnedZipExtractsWithShebangAndExpectedInventory() {
        val archive = File("../ytdlp/engine/yt-dlp")
        val target = File(temporary.root, "real-lib")
        BundledZip.extract(archive, target, "2026.08.19", "0.8.0")
        assertEquals(1053, target.walkTopDown().count { it.isFile })
        assertTrue(File(target, "yt_dlp_ejs/yt/solver/lib.min.js").readText().contains("Version: 6.1.4"))
    }

    private fun store(code: Int = 1) = BundledEngineStore(paths, code)

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

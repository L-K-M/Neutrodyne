// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.model.DownloadError
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.IOException
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFilesTest {
    @Test
    fun unavailableOldRootCanPrepareWithoutOpeningItsPart() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                rig.roots.available.remove("old")
                rig.roots.selected = "new"
                rig.part().withOpen { part ->
                    val prepared = assertIs<Prepared.Go>(rig.transfer.prepare(rig.row, part))
                    assertEquals("new", prepared.rootId)
                    assertEquals(0, prepared.plan.offset)
                }
            }
        }

    @Test
    fun closeReleasesHandleInCancelledCoroutine() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
                val job =
                    launch {
                        val part = rig.part()
                        try {
                            part.length()
                            opened.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            part.close()
                        }
                    }
                opened.await()
                job.cancel()
                job.join()
                rig.faults.fake.checkNoOpenFiles()
            }
        }

    @Test
    fun sourceRefreshSelectsRankedAudioAndCarriesIntegrity() =
        transferTest { part ->
            val alternate =
                ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity(
                    episodeId = 1,
                    ordinal = 1,
                    type = "audio/mp4",
                    integrityType = "sri",
                    integrityValue = "sha256-AAAA",
                    sourcesJson = """[{"uri":"https://new.example/audio"}]""",
                )
            source =
                source.copy(
                    isVideo = true,
                    audioAlternates = listOf(alternate.copy(ordinal = 0, sourcesJson = "broken"), alternate),
                )
            val prepared = assertIs<Prepared.Go>(transfer.prepare(row, part))
            assertEquals("https://new.example/audio", prepared.sourceRef)
            assertEquals("sha256-AAAA", prepared.plan.integrity)
            roots.fileLimit = 4095
            assertIs<Prepared.Fail>(transfer.prepare(row.copy(totalBytes = 4096), part))
        }

    @Test
    fun partLengthIsWrittenBytesAndCannotPreallocate() =
        transferTest { part ->
            part.append(mediaBytes(), 1234)
            assertEquals(1234, part.length())
            assertFailsWith<IllegalArgumentException> { part.truncate(2000) }
            part.truncate(321)
            part.sync()
            assertContentEquals(mediaBytes().copyOf(321), part.contents())
        }

    @Test
    fun traversalAbsolutePathsAndSymlinksAreRejected() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                with(rig) {
                    for (path in listOf("../x", "/x", "a//b", "a/./b", "a/../b", "C:x", "a\\b", "a\u0000b")) {
                        assertFailsWith<IllegalArgumentException>(path) { files.openPart("old", path) }
                    }
                    faults.createSymlink("/old/link".toPath(), "/new".toPath())
                    files.openPart("old", "link/outside").withOpen {
                        assertFailsWith<IOException> { it.length() }
                    }
                    assertTrue(files.list("old", "").isEmpty())
                }
            }
        }

    @Test
    fun syncCloseRenameThenCrashAdoptionNeedsNoHttpRequest() =
        transferTest { part ->
            val plan = plan(part)
            part.append(mediaBytes(), 4096)
            val complete = assertIs<TransferResult.Complete>(verification.verify(part, 4096, "audio/mpeg", null))
            faults.operations.clear()
            val final = verification.finish(part, source, plan, complete)
            assertEquals(listOf("sync", "close", "move"), faults.operations)
            val restarted = files.openPart("old", DownloadPaths.part(1))
            restarted.withOpen {
                val adopted = assertIs<Prepared.Adopted>(transfer.prepare(row.copy(totalBytes = 4096), it))
                assertEquals(final.relativePath, adopted.relativePath)
                assertEquals(4096, adopted.complete.total)
                assertTrue(adopted.finalUri.contains("%20"))
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun renameFailureRetainsPartAndNeverAdoptsWrongLengthOrHtml() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                with(rig) {
                    val part = part()
                    val plan = plan(part)
                    part.append(mediaBytes(), 4096)
                    val complete = assertIs<TransferResult.Complete>(verification.verify(part, 4096, null, null))
                    faults.moveFailure = IOException("unmounted during rename")
                    assertFailsWith<IOException> { verification.finish(part, source, plan, complete) }
                    assertEquals(4096, files.stat("old", DownloadPaths.part(1))!!.bytes)
                    faults.moveFailure = null
                    files.delete("old", DownloadPaths.part(1))
                    files.createDirectories("old", "Podcast [p2]")
                    files
                        .openPart(
                            "old",
                            "Podcast [p2]/old [e1].mp3",
                        ).withOpen { it.append("<html>".encodeToByteArray(), 6) }
                    assertNull(verification.adopt(row.copy(totalBytes = 4096), source, null))
                    assertNull(verification.adopt(row.copy(totalBytes = 6), source, null))
                }
            }
        }

    @Test
    fun processDeathDropsTornTailBeforeResuming() =
        transferTest { part ->
            val bytes = mediaBytes(100_000)
            part.append(bytes, bytes.size)
            val plan = plan(part, row.copy(etag = "\"one\"", lastStopReason = DownloadStopReasons.PROCESS_DEATH))
            assertEquals(100_000 - 65_536L, plan.offset)
            assertEquals(plan.offset, part.length())
            assertContentEquals(bytes.copyOf(plan.offset.toInt()), part.contents())
        }

    @Test
    fun rootChangeCopiesAndSyncsBeforeDeletingOldPart() =
        transferTest { part ->
            part.append(mediaBytes(), 2048)
            roots.selected = "new"
            val prepared = assertIs<Prepared.Go>(transfer.prepare(row.copy(etag = "\"one\"", totalBytes = 4096), part))
            assertEquals("new", prepared.rootId)
            assertEquals(2048, prepared.plan.offset)
            assertEquals(4096 + 200 * 1024 * 1024L, roots.required)
            assertNull(files.stat("old", DownloadPaths.part(1)))
            files
                .openPart(
                    "new",
                    prepared.tempPath,
                ).withOpen { assertContentEquals(mediaBytes().copyOf(2048), it.contents()) }
        }

    @Test
    fun failedRootCopyKeepsOriginalAndUnavailableOldRootStartsFresh() =
        transferTest { part ->
            part.append(mediaBytes(), 2048)
            roots.selected = "new"
            roots.failure = StorageFailure.FULL
            val wait = assertIs<Prepared.Wait>(transfer.prepare(row.copy(etag = "\"one\""), part))
            assertEquals(DownloadError.STORAGE_FULL, wait.error)
            assertEquals(false, wait.countsAsAttempt)
            assertEquals(2048, part.length())
            roots.failure = null
            roots.available.remove("old")
            val prepared = assertIs<Prepared.Go>(transfer.prepare(row.copy(etag = "\"one\""), part))
            assertEquals(0, prepared.plan.offset)
            assertEquals("new", prepared.rootId)
        }

    @Test
    fun unavailableCurrentRootWaitsAndLeavesExistingBytes() =
        transferTest { part ->
            part.append(mediaBytes(), 2048)
            roots.selected = "new"
            roots.available.remove("new")
            val result = assertIs<Prepared.Wait>(transfer.prepare(row, part))
            assertEquals(DownloadError.STORAGE_UNAVAILABLE, result.error)
            assertEquals(2048, part.length())
        }

    @Test
    fun verificationRejectsWrongLengthAndReplacedHead() =
        transferTest { part ->
            part.append(mediaBytes(), 4096)
            assertEquals(
                DownloadError.SIZE_MISMATCH,
                assertIs<TransferResult.Transient>(verification.verify(part, 5000, null, null)).error,
            )
            part.truncate(0)
            part.append("<html>".encodeToByteArray(), 6)
            assertEquals(
                DownloadError.NOT_MEDIA,
                assertIs<TransferResult.Permanent>(verification.verify(part, 6, "audio/mpeg", null)).error,
            )
        }

    @Test
    fun unicodeReservedNamesAndByteBudgetsStayPortable() {
        assertEquals("a [p1]", DownloadPaths.component("a\uDB40\uDD00x", "[p1]", 10))
        assertEquals("a [p1]", DownloadPaths.component("a\uD834\uDD65x", "[p1]", 10))
        assertEquals("é [p1]", DownloadPaths.component("e\u0301", "[p1]"))
        assertEquals("Untitled [p1]", DownloadPaths.component(" ..\n ", "[p1]"))
        for (name in listOf("CON", "con. Daily", "PRN", "AUX", "NUL", "COM1", "COM¹", "LPT9", "LPT³")) {
            assertTrue(DownloadPaths.component(name, "[p1]").startsWith('_'))
        }
        val name = DownloadPaths.component(" A/B\\C:*?\"<>|\u0001\u007f\u2028\u2029 D ..", "[p1]")
        assertEquals("A B C D [p1]", name)
        for (text in listOf("😀".repeat(100), "漢字".repeat(100), "a\u200d".repeat(100), "x\ufe0f".repeat(100))) {
            val result = DownloadPaths.component(text, "[e123].mp3")
            assertTrue(result.encodeToByteArray().size <= 100)
            assertTrue(result.endsWith(" [e123].mp3"))
            assertTrue('\ufffd' !in result)
        }
    }

    @Test
    fun pathsReusePodcastFolderAndRespectWindowsWholePath() =
        runBlocking<Unit> {
            TransferRig().use { rig ->
                with(rig) {
                    files.createDirectories("old", "Old title [p2]")
                    val reused =
                        DownloadPaths.finalPath(
                            files,
                            ResolvedRoot("old", "/old"),
                            2,
                            "New title",
                            1,
                            "Episode",
                            "2026-10-19",
                            "mp3",
                        )
                    assertTrue(reused.startsWith("Old title [p2]/2026-10-19 "))
                    val root = ResolvedRoot("new", "C:/" + "r".repeat(115))
                    val path =
                        DownloadPaths.finalPath(
                            files,
                            root,
                            2,
                            "p".repeat(300),
                            1,
                            "t".repeat(300),
                            "2026-10-19",
                            "mp3",
                            PathPlatform.WINDOWS,
                        )
                    assertTrue(root.absolutePath.length + 1 + path.length <= 259)
                    assertTrue(path.substringAfter('/').startsWith("2026-10-19 "))
                    assertTrue(path.endsWith(" [e1].mp3"))
                    assertFailsWith<IllegalArgumentException> {
                        DownloadPaths.finalPath(
                            files,
                            root.copy(absolutePath = "C:/" + "r".repeat(230)),
                            2,
                            "Podcast",
                            1,
                            "Episode",
                            "2026-10-19",
                            "mp3",
                            PathPlatform.WINDOWS,
                        )
                    }
                }
            }
        }

    @Test
    fun desktopDefaultRootIsCreatedOnDemandWithoutAllocation() =
        runBlocking<Unit> {
            val parent = Files.createTempDirectory("w3-downloads")
            try {
                val dirs = AppDirs(parent, parent, parent, parent, parent, parent.resolve("Downloads"))
                val roots = DesktopStorageRoots(dirs, JournalTestStore(), { emptyList() })
                assertEquals("dir:default", roots.current())
                assertNull(roots.resolve("dir:unsupported"))
                assertNotNull(roots.resolve("dir:default"))
                assertNull(roots.ensureSpace("dir:default", 0))
                assertEquals(StorageFailure.FULL, roots.ensureSpace("dir:default", Long.MAX_VALUE))
                assertTrue(roots.list().single().available)
                assertTrue(
                    PlatformLocalUris()
                        .fileUri(
                            parent.resolve("é #file.mp3").toString(),
                        ).contains("%C3%A9%20%23file.mp3"),
                )
            } finally {
                parent.toFile().deleteRecursively()
            }
        }
}

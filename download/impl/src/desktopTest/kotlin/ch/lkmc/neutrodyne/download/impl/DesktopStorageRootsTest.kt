// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStorageRootsTest {
    @Test fun registersRealPathIdempotentlyAndPersistsCurrentWithoutRootId() =
        rootsTest { f ->
            val selected = f.parent.resolve("chosen")
            val registered = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(selected.toString())).root
            assertTrue(registered.rootId.matches(Regex("dir:[0-9a-f]{8}")))
            assertTrue(registered.available)
            assertNotNull(registered.freeBytes)
            assertEquals(selected.toRealPath().toString(), registered.path)
            val again = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(selected.resolve(".").toString())).root
            assertEquals(registered.rootId, again.rootId)
            assertEquals(registered.path, again.path)
            f.roots.setCurrent(registered.rootId, f.store)
            assertEquals(registered.rootId, f.roots.current())
            assertEquals(registered.path, f.store.get(DesktopSettingKeys.DOWNLOADS_DIR))
            assertEquals(DownloadSettingKeys.ROOT_ID.default, f.store.get(DownloadSettingKeys.ROOT_ID))
            assertTrue(Files.list(selected).use { it.findAny().isEmpty })
            val reloaded = DesktopStorageRoots(f.dirs, f.store, { f.rows })
            assertEquals(registered.rootId, reloaded.current())
            assertEquals(registered.path, reloaded.resolve(registered.rootId)?.absolutePath)
        }

    @Test fun defaultFolderKeepsStableIdAndEmptyPathSetting() =
        rootsTest { f ->
            val result = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(f.dirs.downloadsDefault.toString()))
            assertEquals("dir:default", result.root.rootId)
            assertTrue(f.store.get(DownloadSettingKeys.DESKTOP_ROOTS).isEmpty())
            f.roots.setCurrent("dir:default", f.store)
            assertEquals("", f.store.get(DesktopSettingKeys.DOWNLOADS_DIR))
            assertEquals("dir:default", f.roots.current())
        }

    @Test fun rejectsFilesRelativePathsAndPrivateAppDirectoriesWithoutChangingCurrent() =
        rootsTest { f ->
            val file = Files.writeString(f.parent.resolve("file"), "keep")
            for (path in listOf(file.toString(), "relative/path")) {
                assertEquals(
                    FolderRootResult.Rejected(FolderRejectReason.NOT_A_DIRECTORY),
                    f.roots.registerFolderRoot(path),
                )
            }
            for (path in listOf(f.dirs.cache, f.dirs.state, f.install)) {
                val nested = path.resolve("new/downloads")
                assertEquals(
                    FolderRootResult.Rejected(FolderRejectReason.INSIDE_APP_DIRECTORIES),
                    f.roots.registerFolderRoot(nested.toString()),
                )
                assertFalse(Files.exists(nested))
            }
            if ("posix" in f.parent.fileSystem.supportedFileAttributeViews()) {
                val nested = Files.createDirectories(f.install.resolve("nested"))
                val alias = Files.createSymbolicLink(f.parent.resolve("alias"), nested)
                assertEquals(
                    FolderRootResult.Rejected(FolderRejectReason.INSIDE_APP_DIRECTORIES),
                    f.roots.registerFolderRoot(alias.resolve("..").toString()),
                )
            }
            assertEquals("keep", Files.readString(file))
            assertEquals("dir:default", f.roots.current())
            assertTrue(f.store.get(DownloadSettingKeys.DESKTOP_ROOTS).isEmpty())
        }

    @Test fun unavailableRegisteredFolderIsNotRecreatedAndCanBeRepointedByFiles() =
        rootsTest { f ->
            val selected = f.parent.resolve("old")
            val id = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(selected.toString())).root.rootId
            f.roots.setCurrent(id, f.store)
            f.rows = (1L..3L).map { f.row(it, id) }
            for (row in f.rows) {
                val file = selected.resolve(checkNotNull(row.relativePath))
                Files.createDirectories(file.parent)
                Files.write(file, mediaBytes())
            }
            val reattached = f.parent.resolve("another-letter")
            Files.move(selected, reattached)
            assertNull(f.roots.resolve(id))
            assertFalse(Files.exists(selected))
            val result = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(reattached.toString()))
            assertEquals(id, result.root.rootId)
            assertEquals(reattached.toString(), f.store.get(DesktopSettingKeys.DOWNLOADS_DIR))
            assertEquals(id, f.roots.current())
            assertNotNull(f.roots.resolve(id))
            assertEquals(1, f.store.get(DownloadSettingKeys.DESKTOP_ROOTS).size)
        }

    @Test fun tooFewMatchingFilesDoesNotRepointRootAndPruneRetainsUsedAndCurrent() =
        rootsTest { f ->
            val old = f.parent.resolve("old")
            val oldId = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(old.toString())).root.rootId
            Files.delete(old)
            f.rows = (1L..4L).map { f.row(it, oldId) }
            val candidate = f.parent.resolve("candidate")
            val file = candidate.resolve(checkNotNull(f.rows[0].relativePath))
            Files.createDirectories(file.parent)
            Files.write(file, mediaBytes())
            val newId = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(candidate.toString())).root.rootId
            assertTrue(oldId != newId)
            f.roots.setCurrent(newId, f.store)
            val unusedId =
                assertIs<FolderRootResult.Ok>(
                    f.roots.registerFolderRoot(f.parent.resolve("unused").toString()),
                ).root.rootId
            f.roots.prune(setOf(oldId))
            assertEquals(
                setOf(oldId, newId),
                f.store
                    .get(DownloadSettingKeys.DESKTOP_ROOTS)
                    .map {
                        it.substringBefore('|')
                    }.toSet(),
            )
            assertNull(f.roots.resolve(unusedId))
            assertEquals(
                setOf("dir:default", oldId, newId),
                f.roots
                    .list()
                    .map { it.rootId }
                    .toSet(),
            )
        }

    @Test fun packagedLauncherProtectsWholeInstallation() =
        rootsTest { f ->
            val previous = System.getProperty("jpackage.app-path")
            try {
                for (relative in listOf("bin/Neutrodyne", "Neutrodyne.app/Contents/MacOS/Neutrodyne")) {
                    val launcher = f.install.resolve(relative)
                    Files.createDirectories(launcher.parent)
                    Files.writeString(launcher, "launcher")
                    System.setProperty("jpackage.app-path", launcher.toString())
                    val roots = DesktopStorageRoots(f.dirs, f.store, { emptyList() })
                    val inside = if (relative.startsWith("bin/")) f.install else f.install.resolve("Neutrodyne.app")
                    assertEquals(
                        FolderRootResult.Rejected(FolderRejectReason.INSIDE_APP_DIRECTORIES),
                        roots.registerFolderRoot(inside.resolve("downloads").toString()),
                    )
                }
            } finally {
                if (previous ==
                    null
                ) {
                    System.clearProperty("jpackage.app-path")
                } else {
                    System.setProperty("jpackage.app-path", previous)
                }
            }
        }

    @Test fun realSameStoreMoveUsesAtomicRename() =
        rootsTest { f ->
            val other = f.parent.resolve("other")
            val id = assertIs<FolderRootResult.Ok>(f.roots.registerFolderRoot(other.toString())).root.rootId
            val files = OkioDownloadFileSystem(f.roots)
            assertTrue(files.sameStore("dir:default", id))
            Files.writeString(f.dirs.downloadsDefault.resolve("file.mp3"), "audio")
            files.moveBetweenRoots("dir:default", "file.mp3", id, "file.mp3")
            assertNull(files.stat("dir:default", "file.mp3"))
            assertEquals("audio", Files.readString(other.resolve("file.mp3")))
        }

    private fun rootsTest(block: suspend (RootFixture) -> Unit) =
        runBlocking<Unit> {
            val parent = Files.createTempDirectory("m6b-roots")
            try {
                block(RootFixture(parent))
            } finally {
                parent.toFile().deleteRecursively()
            }
        }
}

private class RootFixture(
    val parent: Path,
) {
    val dirs =
        AppDirs(
            parent.resolve("data"),
            parent.resolve("config"),
            parent.resolve("cache"),
            parent.resolve("state"),
            parent.resolve("state/logs"),
            parent.resolve("data/Downloads"),
        )
    val install = parent.resolve("install")
    val store = JournalTestStore()
    var rows = emptyList<DownloadEntity>()
    val roots = DesktopStorageRoots(dirs, store, { rows }, install)

    fun row(
        id: Long,
        root: String,
    ) = DownloadEntity(
        id,
        DownloadLane.MANUAL,
        DownloadState.COMPLETED,
        priority = 100,
        requestedAt = 0,
        sourceKind = SourceKind.RSS_ENCLOSURE,
        sourceRef = "https://example.com/$id.mp3",
        rootId = root,
        allowMetered = true,
        relativePath = "Podcast [p1]/Episode [e$id].mp3",
        totalBytes = 4096,
    )
}

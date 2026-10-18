// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopOpenHandlerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun permissionDeniedImportFileOffersUnreadableRecovery() =
        runTest {
            assumeTrue(
                temp.root
                    .toPath()
                    .fileSystem
                    .supportedFileAttributeViews()
                    .contains("posix"),
            )
            val file = temp.newFile("protected.opml").toPath()
            val permissions = Files.getPosixFilePermissions(file)
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
            try {
                assumeTrue("Read denial requires an unprivileged process", !Files.isReadable(file))
                val imports = FakeImportRepository()
                var rejected = 0
                val handler = DesktopOpenHandler(backgroundScope, { imports }, {}, { rejected++ })
                val failures = mutableListOf<ImportError>()
                backgroundScope.launch { handler.failures.toList(failures) }
                handler.afterFirstFrame()
                handler.submit(listOf(file.toString()))
                runCurrent()

                assertThat(failures).containsExactly(ImportError.Unreadable)
                assertThat(rejected).isEqualTo(0)
                assertThat(imports.calls).isEmpty()
            } finally {
                Files.setPosixFilePermissions(file, permissions)
            }
        }

    @Test fun deniedParentFolderOffersUnreadableRecoveryWhenStatFails() =
        runTest {
            assumeTrue(
                temp.root
                    .toPath()
                    .fileSystem
                    .supportedFileAttributeViews()
                    .contains("posix"),
            )
            val folder = temp.newFolder("protected").toPath()
            val file = Files.createFile(folder.resolve("list.opml"))
            val permissions = Files.getPosixFilePermissions(folder)
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"))
            try {
                assumeTrue("Read denial requires an unprivileged process", !Files.isReadable(file))
                val imports = FakeImportRepository()
                var rejected = 0
                val handler = DesktopOpenHandler(backgroundScope, { imports }, {}, { rejected++ })
                val failures = mutableListOf<ImportError>()
                backgroundScope.launch { handler.failures.toList(failures) }
                handler.afterFirstFrame()
                handler.submit(listOf(file.toString()))
                runCurrent()

                assertThat(failures).containsExactly(ImportError.Unreadable)
                assertThat(rejected).isEqualTo(0)
                assertThat(imports.calls).isEmpty()
            } finally {
                Files.setPosixFilePermissions(folder, permissions)
            }
        }

    @Test fun localhostFileUriWithEscapedNameCreatesAnImportSession() =
        runTest {
            val file = temp.newFile("Meine Hörspiele #1 + 100%.opml").toPath()
            val localUri = "file://localhost" + file.toUri().rawPath
            val imports = FakeImportRepository()
            val routes = mutableListOf<Route>()
            var rejected = 0
            val handler = DesktopOpenHandler(backgroundScope, { imports }, routes::add, { rejected++ })
            handler.afterFirstFrame()
            handler.submit(listOf(localUri))
            runCurrent()

            assertThat(imports.sessions.value).hasSize(1)
            assertThat(
                imports.sessions.value.values
                    .single()
                    .sourceName,
            ).isEqualTo(file.fileName.toString())
            assertThat(routes).containsExactly(Route.Navigate(LibraryKey, listOf(ImportKey("1"))))
            assertThat(rejected).isEqualTo(0)
        }

    @Test fun escapedUriRawAndRelativePathsCreateSessionsAfterReadiness() =
        runTest {
            val imports = FakeImportRepository()
            val routes = mutableListOf<Route>()
            val handler =
                DesktopOpenHandler(backgroundScope, { imports }, routes::add, { error("Rejected valid path") })
            val file = temp.newFile("日本語 Hörspiele #1 + 100%.opml").toPath()
            handler.submit(
                listOf(file.toUri().toASCIIString(), file.toString(), file.fileName.toString()),
                temp.root.toPath(),
            )
            runCurrent()
            assertThat(imports.sessions.value).isEmpty()
            handler.afterFirstFrame()
            runCurrent()

            assertThat(imports.sessions.value).hasSize(3)
            assertThat(
                imports.sessions.value.values
                    .map { it.sourceName },
            ).containsExactlyElementsIn(List(3) { file.fileName.toString() })
            assertThat(routes).hasSize(1)
        }

    @Test fun remoteAndMalformedFileUrisAreRejectedWithoutImporting() =
        runTest {
            val file = temp.newFile("list.opml").toPath()
            val localUri = "file://localhost" + file.toUri().rawPath
            val inputs =
                listOf(
                    "file://fileserver/share/list.opml",
                    "file://user@localhost" + file.toUri().rawPath,
                    "file://localhost:8080" + file.toUri().rawPath,
                    "$localUri?query=1",
                    "$localUri#fragment",
                    "file:relative.opml",
                    "file:///bad%zz.opml",
                )
            val imports = FakeImportRepository()
            var rejected = 0
            val handler = DesktopOpenHandler(backgroundScope, { imports }, {}, { rejected++ })
            handler.afterFirstFrame()
            handler.submit(inputs)
            runCurrent()

            assertThat(rejected).isEqualTo(inputs.size)
            assertThat(imports.calls).isEmpty()
        }

    @Test fun dropFilesCreateSeparateSessionsAndOnlyFirstNavigates() =
        runTest {
            val imports = FakeImportRepository()
            val routes = mutableListOf<Route>()
            val handler =
                DesktopOpenHandler(backgroundScope, { imports }, routes::add, { error("Rejected valid file") })
            handler.afterFirstFrame()
            val files = listOf("a.opml", "b.XML", "backup.zip").map { temp.newFile(it).toPath() }
            handler.submit(files.map { it.toUri().toString() })
            runCurrent()
            assertThat(imports.observeOpenSessions().first()).hasSize(3)
            assertThat(routes).containsExactly(Route.Navigate(LibraryKey, listOf(ImportKey("1"))))
            assertThat(
                imports.sessions.value.values.map {
                    it.sourceName
                },
            ).containsExactly("a.opml", "b.XML", "backup.zip").inOrder()
        }

    @Test fun droppedUrlTextRoutesToAddSheet() =
        runTest {
            val routes = mutableListOf<Route>()
            val handler =
                DesktopOpenHandler(
                    backgroundScope,
                    { error("URL must not create import") },
                    routes::add,
                    { error("Rejected URL") },
                )
            handler.afterFirstFrame()
            handler.submit(listOf("https://example.com/feed.xml"))
            runCurrent()
            assertThat(routes).containsExactly(Route.Push(AddPodcastKey("https://example.com/feed.xml")))
        }

    @Test fun mixedDropCreatesLaterSessionsWithoutStackingPreviews() =
        runTest {
            val imports = FakeImportRepository()
            val routes = mutableListOf<Route>()
            val handler = DesktopOpenHandler(backgroundScope, { imports }, routes::add, {})
            handler.afterFirstFrame()
            handler.submit(
                listOf(
                    "feed:https://example.com/rss",
                    temp.newFile("a.opml").absolutePath,
                    temp.newFile("b.zip").absolutePath,
                ),
            )
            runCurrent()
            assertThat(imports.sessions.value).hasSize(2)
            assertThat(routes).containsExactly(Route.Push(AddPodcastKey("feed:https://example.com/rss")))
        }

    @Test fun moreThanTwentyInputsAreTruncated() =
        runTest {
            val imports = FakeImportRepository()
            val handler = DesktopOpenHandler(backgroundScope, { imports }, {}, {})
            handler.afterFirstFrame()
            val file = temp.newFile("a.opml").absolutePath
            handler.submit(List(25) { file })
            runCurrent()
            assertThat(imports.sessions.value).hasSize(20)
        }

    @Test fun directoriesOtherTypesMissingAndOversizedFilesAreRejected() =
        runTest {
            val imports = FakeImportRepository()
            var rejected = 0
            val handler = DesktopOpenHandler(backgroundScope, { imports }, {}, { rejected++ })
            handler.afterFirstFrame()
            val huge = temp.newFile("large.opml")
            java.io.RandomAccessFile(huge, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            handler.submit(
                listOf(
                    temp.newFolder("directory.opml").absolutePath,
                    temp.newFile("a.json").absolutePath,
                    temp.root.resolve("missing.xml").absolutePath,
                    huge.absolutePath,
                ),
            )
            runCurrent()
            assertThat(rejected).isEqualTo(4)
            assertThat(imports.calls).isEmpty()
        }

    @Test fun inputsBeforeDatabaseAndFirstFrameStayQueuedInOrder() =
        runTest {
            val imports = FakeImportRepository()
            var resolutions = 0
            val handler =
                DesktopOpenHandler(backgroundScope, {
                    resolutions++
                    imports
                }, {}, {})
            val cwd = temp.root.toPath()
            temp.newFile("first.opml")
            temp.newFile("second.xml")
            handler.submit(listOf("first.opml"), cwd)
            handler.submit(listOf("second.xml"), cwd)
            runCurrent()
            assertThat(resolutions).isEqualTo(0)
            handler.afterFirstFrame()
            runCurrent()
            assertThat(
                imports.sessions.value.values
                    .map { it.sourceName },
            ).containsExactly("first.opml", "second.xml").inOrder()
        }

    @Test fun everyExternalLinkRowUsesTheSharedRouter() =
        runTest {
            val routes = mutableListOf<Route>()
            val handler =
                DesktopOpenHandler(
                    backgroundScope,
                    { error("Link must not import") },
                    routes::add,
                    { error("Rejected link") },
                )
            handler.afterFirstFrame()
            val urls =
                listOf(
                    "feed:https://example.com/rss",
                    "podcast:https://example.com/rss",
                    "pcast://example.com/rss",
                    "itpc://example.com/rss",
                    "neutrodyne://subscribe?url=https://example.com/rss",
                    "https://podcasts.apple.com/us/podcast/id1",
                )
            for (url in urls) handler.submit(listOf(url))
            runCurrent()
            assertThat(routes).containsExactlyElementsIn(urls.map { Route.Push(AddPodcastKey(it)) }).inOrder()
        }

    @Test fun oversizedAndUnrecognisedTextNeverRoutesOrCreates() =
        runTest {
            val imports = FakeImportRepository()
            val routes = mutableListOf<Route>()
            var rejected = 0
            val handler = DesktopOpenHandler(backgroundScope, { imports }, routes::add, { rejected++ })
            handler.afterFirstFrame()
            handler.submit(
                listOf("https://example.com/" + "x".repeat(4096), "not a URL", "neutrodyne://open/settings/backup"),
            )
            runCurrent()
            assertThat(rejected).isEqualTo(3)
            assertThat(routes).isEmpty()
            assertThat(imports.calls).isEmpty()
        }
}

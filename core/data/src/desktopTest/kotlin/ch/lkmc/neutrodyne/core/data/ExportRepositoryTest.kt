// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.data.exporting.ExportFilesCleaner
import ch.lkmc.neutrodyne.core.data.exporting.ExportRepositoryImpl
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ExportRepositoryImpl` + `OpmlExporter` over a real `TestDb` and the real `OpmlWriter`
 * (05 Testing, M3 AC9/AC10 data legs): document content and warning counts, the private
 * `cache/export/` file, `saveTo` delegation and the stale-file sweep.
 */
class ExportRepositoryTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val secrets = FakeSecretStore()
    private val fileSystem = FileSystem.SYSTEM
    private val root = File("build/tmp/m3-export").resolve(UUID.randomUUID().toString())
    private val destination = RecordingExportDestination()
    private val repo =
        ExportRepositoryImpl(
            exporter = OpmlExporter(db, secrets, clock, Dispatchers.Default),
            destination = destination,
            storagePaths = storagePathsFor(root),
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )
    private val exportDir = root.resolve("cache").resolve("export")

    @BeforeTest fun setUp() {
        root.mkdirs()
    }

    @AfterTest fun close() {
        db.close()
        root.deleteRecursively()
    }

    // --- Document content (grouped, flat, single-group) ---------------------------------------------

    @Test fun groupedExportWritesFoldersMembersAndUngroupedOnce() =
        runTest {
            seedLibrary()
            val prepared = prepared()
            val text = read(prepared)

            assertTrue("<title>Neutrodyne subscriptions</title>" in text)
            assertTrue("04 Oct 2026" in text)
            // Empty groups round-trip as self-closing folders (05 Structure).
            assertTrue("<outline text=\"fiction\" title=\"fiction\"/>" in text)
            // Nested under the primary group; `category` lists every group in group order.
            assertTrue("<outline text=\"tech\" title=\"tech\"" in text)
            assertTrue("\n      <outline type=\"rss\" text=\"ATP\"" in text)
            assertTrue("category=\"tech,news\"" in text)
            // Each feed appears exactly once; ungrouped feeds follow the folders at top level.
            assertEquals(1, occurrences(text, "xmlUrl=\"https://atp.fm/rss\""))
            assertTrue("\n    <outline type=\"rss\" text=\"Solo\"" in text)
            assertEquals(3, prepared.feeds)
            assertEquals(0, prepared.privateLinks)
            assertEquals(0, prepared.passwords)
        }

    @Test fun flatExportKeepsCategoriesButNoFolders() =
        runTest {
            seedLibrary()
            val prepared = prepared(ExportRequest(layout = ExportLayout.FLAT))
            val text = read(prepared)

            assertFalse("<outline text=\"tech\"" in text)
            // Every feed at top level with `category` kept (05 Structure).
            assertTrue("\n    <outline type=\"rss\" text=\"ATP\"" in text)
            assertTrue("category=\"tech,news\"" in text)
            assertEquals(3, prepared.feeds)
        }

    @Test fun singleGroupExportContainsOnlyThatGroup() =
        runTest {
            seedLibrary()
            val news = db.groupDao().groupByNameKey("news")!!.id
            val prepared = prepared(ExportRequest(groupId = news))
            val text = read(prepared)

            assertEquals("neutrodyne-news-2026-10-04.opml", prepared.fileName)
            assertTrue("<title>Neutrodyne: news</title>" in text)
            assertTrue("<outline text=\"news\"" in text)
            assertFalse("<outline text=\"tech\"" in text)
            // `category` names the exported group only, even for a member of two groups.
            assertTrue("category=\"news\"" in text)
            assertFalse("category=\"news,tech\"" in text)
            assertEquals(2, prepared.feeds)
        }

    @Test fun groupFileNameSlugDropsNonAlphanumerics() =
        runTest {
            val groupId = group("Sci Fi & Fantasy!", "a")
            member(groupId, podcast("https://example.com/sff", "SFF"))
            val prepared = prepared(ExportRequest(groupId = groupId))
            assertEquals("neutrodyne-sci-fi-fantasy-2026-10-04.opml", prepared.fileName)
        }

    // --- Options: YouTube, passwords, private-link count -------------------------------------------

    @Test fun youtubeRowsFollowIncludeYouTube() =
        runTest {
            val groupId = group("yt", "a")
            member(
                groupId,
                podcast(
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCxyz",
                    "Channel",
                    SourceType.YOUTUBE_CHANNEL,
                ) { copy(youtubeChannelId = "UCxyz", youtubeVariants = 5) },
            )
            val withYt = prepared()
            val text = read(withYt)
            assertTrue("nd:source=\"youtube\"" in text)
            assertTrue("nd:ytVariants=\"UULF,UULV\"" in text)
            assertTrue("htmlUrl=\"https://www.youtube.com/channel/UCxyz\"" in text)

            // With YouTube excluded the feed is dropped and its group becomes an empty folder.
            val without = prepared(ExportRequest(includeYouTube = false))
            val excluded = read(without)
            assertFalse("youtube" in excluded)
            assertTrue("<outline text=\"yt\" title=\"yt\"/>" in excluded)
            assertEquals(0, without.feeds)
        }

    @Test fun passwordsRequireOptInAndPercentEncode() =
        runTest {
            val podcastId = podcast("https://example.com/rss", "Private")
            credential(podcastId, "user name", "p@ss:word")

            val optedOut = prepared()
            assertFalse("user%20name" in read(optedOut))
            assertEquals(0, optedOut.passwords)

            val optedIn = prepared(ExportRequest(includePasswords = true))
            val text = read(optedIn)
            assertTrue("xmlUrl=\"https://user%20name:p%40ss%3Aword@example.com/rss\"" in text)
            assertEquals(1, optedIn.passwords)
            // Userinfo always looks private: the written URL feeds the R1.9 count.
            assertEquals(1, optedIn.privateLinks)
        }

    @Test fun unreadableCredentialExportsWithoutUserinfo() =
        runTest {
            // No stored credential at all: `forPodcast` reads nothing, the URL stays bare.
            podcast("https://example.com/rss", "Lost")
            val prepared = prepared(ExportRequest(includePasswords = true))
            assertTrue("xmlUrl=\"https://example.com/rss\"" in read(prepared))
            assertEquals(0, prepared.passwords)
        }

    @Test fun privateLinksCountTokenUrls() =
        runTest {
            podcast("https://patreon.com/rss/abc", "Patreon")
            podcast("https://example.com/feed?token=abc123", "Token")
            podcast("https://example.com/plain", "Plain")
            assertEquals(2, prepared().privateLinks)
        }

    // --- prepare/saveTo legs (AC10: no destination write in prepare) --------------------------------

    @Test fun prepareWritesOnlyUnderExportCache() =
        runTest {
            seedLibrary()
            val prepared = prepared()

            assertTrue(destination.saves.isEmpty())
            assertEquals(listOf("export"), root.resolve("cache").list()?.toList())
            assertEquals(listOf(prepared.fileName), exportDir.list()?.toList())
            assertEquals("text/x-opml", prepared.mimeType)
            assertEquals("content://test/export/${prepared.fileName}", prepared.shareUri)
        }

    @Test fun missingGroupFailsAndWritesNothing() =
        runTest {
            val outcome = repo.prepare(ExportRequest(groupId = 999L))
            assertEquals(Outcome.Failure(ExportError.Io), outcome)
            assertFalse(exportDir.exists())
        }

    @Test fun saveToPassesThePreparedFileToTheDestination() =
        runTest {
            seedLibrary()
            val prepared = prepared()
            assertIs<Outcome.Success<Unit>>(repo.saveTo(prepared, "content://saf/tree/doc"))

            val (source, uri) = destination.saves.single()
            assertEquals(exportPath(prepared.fileName), source)
            assertEquals("content://saf/tree/doc", uri)
        }

    @Test fun saveToPropagatesDestinationFailure() =
        runTest {
            seedLibrary()
            val prepared = prepared()
            destination.saveOutcome = Outcome.Failure(ExportError.DestinationUnavailable)
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                repo.saveTo(prepared, "content://saf/tree/doc"),
            )
        }

    @Test fun saveToFailsWhenTheCacheFileIsGone() =
        runTest {
            seedLibrary()
            val prepared = prepared()
            File(exportDir, prepared.fileName).delete()
            assertEquals(
                Outcome.Failure(ExportError.Io),
                repo.saveTo(prepared, "content://saf/tree/doc"),
            )
        }

    @Test fun saveToRefusesNamesOutsideTheExportDir() =
        runTest {
            val forged = PreparedExport("../escape.opml", "text/x-opml", null, 0, 0, 0)
            assertEquals(Outcome.Failure(ExportError.Io), repo.saveTo(forged, "file:///tmp/x"))
            assertTrue(destination.saves.isEmpty())
        }

    // --- ExportFilesCleaner --------------------------------------------------------------------------

    @Test fun cleanerRemovesOnlyStaleExportFiles() =
        runTest {
            val stale =
                File(exportDir, "old.opml").apply {
                    parentFile?.mkdirs()
                    writeText("stale")
                    setLastModified(clock.now() - 25 * 3_600_000L)
                }
            val fresh =
                File(exportDir, "new.opml").apply {
                    writeText("fresh")
                    // The clock is fixed: pin the fresh file's mtime so a real wall clock
                    // behind the test clock can never age it past the cutoff.
                    setLastModified(clock.now())
                }
            val outside =
                File(root, "cache/other/old.txt").apply {
                    parentFile?.mkdirs()
                    writeText("other")
                    setLastModified(clock.now() - 25 * 3_600_000L)
                }

            val cleaner = ExportFilesCleaner(storagePathsFor(root), fileSystem, clock)
            assertEquals(300, cleaner.order)
            cleaner.run()

            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertTrue(outside.exists())
        }

    // --- Fixtures ------------------------------------------------------------------------------------

    private suspend fun prepared(request: ExportRequest = ExportRequest()): PreparedExport =
        assertIs<Outcome.Success<PreparedExport>>(repo.prepare(request)).value

    private fun exportPath(fileName: String): Path = exportDir.resolve(fileName).path.toPath()

    private fun read(prepared: PreparedExport): String = fileSystem.read(exportPath(prepared.fileName)) { readUtf8() }

    private suspend fun seedLibrary() {
        val tech = group("tech", "a") { copy(colorArgb = 0xFF2196F3.toInt(), iconKey = "memory") }
        val news = group("news", "b")
        group("fiction", "c")
        val atp = podcast("https://atp.fm/rss", "Accidental Tech Podcast") { copy(customTitle = "ATP") }
        val daily = podcast("https://feeds.example.com/daily", "The Daily")
        podcast("https://example.com/rss", "Some Show") { copy(customTitle = "Solo") }
        member(tech, atp)
        member(news, atp)
        member(news, daily)
    }

    private suspend fun group(
        name: String,
        orderKey: String,
        block: PodcastGroupEntity.() -> PodcastGroupEntity = { this },
    ): Long = db.groupDao().insert(groupEntity(name = name, orderKey = orderKey).block())

    private suspend fun member(
        groupId: Long,
        podcastId: Long,
        orderKey: String = "a",
    ) = db.groupDao().insertMember(memberEntity(groupId, podcastId, orderKey))

    private suspend fun podcast(
        url: String,
        title: String,
        sourceType: SourceType = SourceType.RSS,
        block: PodcastEntity.() -> PodcastEntity = { this },
    ): Long =
        db.podcastDao().insertPodcast(
            podcastEntity(feedUrl = url, feedKey = url, title = title) {
                copy(sourceType = sourceType).block()
            },
        )

    private suspend fun credential(
        podcastId: Long,
        username: String,
        password: String,
    ) {
        // The fake resolves `forPodcast` through podcastCredentialIds — no `credential`
        // row or `podcast.credentialId` link needed (the real store joins on the same id).
        val id = secrets.put("https://example.com", username, password.toCharArray())!!
        secrets.podcastCredentialIds.value = mapOf(podcastId to id)
    }

    private fun occurrences(
        text: String,
        needle: String,
    ): Int = text.split(needle).size - 1

    private class RecordingExportDestination(
        var shareUriValue: String? = "content://test/export/",
        var saveOutcome: Outcome<Unit, ExportError> = Outcome.Success(Unit),
    ) : ExportDestination {
        val saves = mutableListOf<Pair<Path, String>>()

        override fun shareUri(fileName: String): String? = shareUriValue?.let { "$it$fileName" }

        override suspend fun save(
            source: Path,
            destinationUri: String,
        ): Outcome<Unit, ExportError> {
            saves += source to destinationUri
            return saveOutcome
        }
    }
}

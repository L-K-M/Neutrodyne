// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.importing.ImportDocumentWarning
import ch.lkmc.neutrodyne.feeds.importing.ImportDocuments
import ch.lkmc.neutrodyne.feeds.importing.ImportGroup
import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.opml.FolderRef
import ch.lkmc.neutrodyne.feeds.opml.OpmlDocument
import ch.lkmc.neutrodyne.feeds.opml.OpmlEntry
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The `ImportDocuments.fromOpml` golden previews (05 Testing, M3 acceptance 1): every OPML corpus
 * fixture reads through the cascade and adapts to its `<name>.import.golden.json`. The focused
 * cases pin the Group-mapping rules goldens make hard to see — wrapper and gPodder exclusions,
 * document-order proposals, empty-folder handling.
 */
class ImportDocumentsTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private val json = Json { explicitNulls = false }

    private val serializable: (OpmlReadResult) -> JsonElement = { result ->
        when (result) {
            is OpmlReadResult.Ok -> {
                json.encodeToJsonElement(ImportDocuments.fromOpml(result.document))
            }

            is OpmlReadResult.Failed -> {
                JsonObject(mapOf("failed" to JsonPrimitive(result.failure.name)))
            }
        }
    }

    @Test
    fun everyFixtureMatchesItsImportGolden() {
        for (fixture in Goldens.fixtures(AREA, EXTENSION)) {
            val result = reader.read({ Buffer().write(fixture.readBytes()) })
            val name = fixture.name.removeSuffix(EXTENSION)
            Goldens.assertMatches("$name.import.golden.json", result, serializable, AREA)
        }
    }

    /** A container folder holding every feed is proposed, excluded, with the wrapper reason. */
    @Test
    fun wrapperFolderIsProposedButExcluded() {
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example.com/rss", folderId = 0)),
                folders = listOf(FolderRef(id = 0, name = "feeds")),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(
            listOf(ImportGroup("feeds", excludedReason = ImportGroup.EXCLUDED_WRAPPER)),
            import.groups,
        )
        assertEquals(listOf("feeds"), import.entries[0].groupNames)
    }

    /** A folderless export named like a container word is no wrapper when feeds sit beside it. */
    @Test
    fun topLevelFeedsKillTheWrapper() {
        val doc =
            OpmlDocument(
                entries =
                    listOf(
                        entry(0, "https://a.example.com/rss", folderId = 0),
                        entry(1, "https://b.example.com/rss", folderId = null),
                    ),
                folders = listOf(FolderRef(id = 0, name = "feeds")),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(listOf<ImportGroup>(ImportGroup("feeds")), import.groups)
    }

    /** A foreign container is proposed as an excluded wrapper even without direct feeds. */
    @Test
    fun foreignWrapperWithoutDirectFeedsIsProposedButExcluded() {
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example.com/rss", folderId = 1)),
                folders =
                    listOf(
                        FolderRef(id = 0, name = "Outer"),
                        FolderRef(id = 1, name = "Inner", parentId = 0),
                    ),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(
            listOf(ImportGroup("Outer", excludedReason = ImportGroup.EXCLUDED_WRAPPER), ImportGroup("Inner")),
            import.groups,
        )
        assertEquals(listOf("Inner"), import.entries.single().groupNames)
    }

    @Test
    fun foreignNonWrapperFoldersWithoutDirectFeedsStaySkipped() {
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example/rss", folderId = 2)),
                folders =
                    listOf(
                        FolderRef(id = 0, name = "Outer"),
                        FolderRef(id = 1, name = "Middle", parentId = 0),
                        FolderRef(id = 2, name = "Inner", parentId = 1),
                        FolderRef(id = 3, name = "Empty"),
                    ),
                mode = ParseMode.STRICT,
            )

        assertEquals(
            listOf(ImportGroup("Outer", excludedReason = ImportGroup.EXCLUDED_WRAPPER), ImportGroup("Inner")),
            ImportDocuments.fromOpml(doc).groups,
        )
    }

    /** Neutrodyne files propose every folder so empty groups and their order round-trip. */
    @Test
    fun neutrodyneEmptyFoldersAreProposed() {
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example.com/rss", folderId = 1)),
                folders =
                    listOf(
                        FolderRef(id = 0, name = "Empty"),
                        FolderRef(id = 1, name = "Kept"),
                    ),
                neutrodyne = true,
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(listOf(ImportGroup("Empty"), ImportGroup("Kept")), import.groups)
    }

    /** gPodder's content-type sections are proposed but excluded when the head says gPodder. */
    @Test
    fun gpodderSectionsAreExcluded() {
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example.com/rss", folderId = 0)),
                folders = listOf(FolderRef(id = 0, name = "Audio")),
                headTitle = "gPodder subscriptions",
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(
            listOf(ImportGroup("Audio", excludedReason = ImportGroup.EXCLUDED_GPODDER_SECTION)),
            import.groups,
        )
    }

    /**
     * A folder name over the 40-code-point cap is proposed truncated, the same rule the
     * classifier applies to entry group names (05 Group mapping), so the member's truncated
     * nameKey still resolves to a proposal instead of silently losing the folder's grouping.
     * The surrogate pair past the cut point proves the prefix is code-point-safe.
     */
    @Test
    fun overlongFolderNameIsProposedTruncated() {
        val name = "🎧".repeat(20) + "a".repeat(21) // 41 code points
        val doc =
            OpmlDocument(
                entries = listOf(entry(0, "https://a.example.com/rss", folderId = 0)),
                folders = listOf(FolderRef(id = 0, name = name)),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(listOf(ImportGroup("🎧".repeat(20) + "a".repeat(20))), import.groups)
        assertEquals(listOf(name), import.entries[0].groupNames)
        assertEquals(
            listOf(ImportDocumentWarning(ImportDocumentWarning.TRUNCATED_NAMES, 1)),
            import.warnings,
        )
    }

    @Test
    fun duplicateTruncatedNamesCountOnlyAddedProposals() {
        val prefix = "a".repeat(40)
        val name = prefix + "first"
        val doc =
            OpmlDocument(
                entries =
                    listOf(
                        entry(0, "https://a.example/rss", folderId = 0, categories = listOf(name, prefix + "second")),
                        entry(1, "https://b.example/rss", folderId = 1),
                    ),
                folders = listOf(FolderRef(id = 0, name = name), FolderRef(id = 1, name = name)),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)

        assertEquals(listOf(ImportGroup(prefix)), import.groups)
        assertEquals(
            listOf(ImportDocumentWarning(ImportDocumentWarning.TRUNCATED_NAMES, 1)),
            import.warnings,
        )
    }

    /**
     * A Neutrodyne export of a pending podcast carries only `text` (05's writer emits
     * `text = COALESCE(customTitle, title)`), so `text` is the custom title and `title`
     * stays null. Foreign files keep the title-or-text fallback (gPodder descriptions).
     */
    @Test
    fun neutrodyneTextWithoutTitleIsCustomTitle() {
        val doc =
            OpmlDocument(
                entries = listOf(OpmlEntry(ordinal = 0, url = "https://a.example.com/rss", text = "ATP")),
                folders = emptyList(),
                neutrodyne = true,
                mode = ParseMode.STRICT,
            )
        val ours = ImportDocuments.fromOpml(doc).entries.single()
        assertEquals(null, ours.title)
        assertEquals("ATP", ours.customTitle)

        val foreign = ImportDocuments.fromOpml(doc.copy(neutrodyne = false)).entries.single()
        assertEquals("ATP", foreign.title)
        assertEquals(null, foreign.customTitle)
    }

    /** Category-only names follow the folders, deduplicated on the normalized name key. */
    @Test
    fun categoryOnlyNamesFollowFoldersInOrder() {
        val doc =
            OpmlDocument(
                entries =
                    listOf(
                        entry(
                            0,
                            "https://a.example.com/rss",
                            folderId = 0,
                            categories = listOf("Tech", "tech", "Audio"),
                        ),
                    ),
                folders = listOf(FolderRef(id = 0, name = "Kept")),
                mode = ParseMode.STRICT,
            )
        val import = ImportDocuments.fromOpml(doc)
        assertEquals(
            listOf(ImportGroup("Kept"), ImportGroup("Tech"), ImportGroup("Audio")),
            import.groups,
        )
        assertEquals(listOf("Tech", "tech", "Audio", "Kept"), import.entries[0].groupNames)
    }

    private fun entry(
        ordinal: Int,
        url: String,
        folderId: Int?,
        categories: List<String> = emptyList(),
    ): OpmlEntry =
        OpmlEntry(
            ordinal = ordinal,
            url = url,
            text = "Feed $ordinal",
            folderId = folderId,
            categories = categories,
        )

    private companion object {
        const val AREA = "opml"
        const val EXTENSION = ".opml"
    }
}

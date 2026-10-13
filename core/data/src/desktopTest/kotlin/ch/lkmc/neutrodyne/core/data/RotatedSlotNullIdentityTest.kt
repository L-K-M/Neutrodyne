// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.IngestResult
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParseWarning
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D98 rotated-slot preservation: a `g:X` row whose stored GUID rotated away belongs to a
 * different episode's content, so an unproven `X` (UNKNOWN or OBSERVED — never
 * KNOWN_INDEPENDENT) must not claim it blind. When the item's enclosure identity is
 * unusable — the normalizer rejects some parser-accepted hosts (leading-zero and
 * trailing-dot numerics) — pass 1's foreign-owner check cannot arbitrate, and the claim
 * must take the conservative insert-or-drop path instead of transferring state.
 *
 * The rotation sequence is ordinary: Alpha (`g:x`) and Beta (`g:y`) coexist, then both
 * items carry `y` (ambiguous), leaving Alpha's row keyed `g:x` while its stored GUID is
 * `y`. Beta then returns alone carrying `x`. The covered cases keep `x` unproven through
 * incomplete-document observations (partial flag, paging window, archive document,
 * ITEMS_TRUNCATED, OLDER_PAGE); the legacy cases model a V1-migrated feed.
 */
internal class RotatedSlotNullIdentityTest {
    private suspend fun preservesForeignShell(
        host: String,
        covered: Boolean,
        partial: Boolean,
        reopen: Boolean,
        window: String = "complete",
    ) {
        val clock = TestClock()
        val dir = Files.createTempDirectory("rotation-")
        var db = TestDb.file(dir, clock = clock)
        try {
            val id =
                seedPodcast(
                    db,
                    feedUrl = "https://example.test/feed",
                    guidCoverageSince = if (covered) clock.now() else null,
                )
            val a =
                parsedEpisode(
                    0,
                    guid = "x",
                    title = "Alpha",
                    pubDate = clock.now() - DAY,
                    enclosureUrl = "http://$host/a.mp3",
                )
            val b =
                parsedEpisode(
                    1,
                    guid = "y",
                    title = "Beta",
                    pubDate = clock.now() - DAY,
                    enclosureUrl = "http://$host/b.mp3",
                )

            suspend fun ingest(
                items: List<ParsedEpisode>,
                initial: Boolean = false,
            ): IngestResult =
                newIngestor(db, clock).ingest(
                    dueFeedOf(db, id),
                    parsedFeed(
                        items = items,
                        paging =
                            when (window) {
                                "next" -> Paging(next = "https://example.test/next")
                                "archive" -> Paging(fhArchive = true)
                                else -> Paging()
                            },
                        warnings =
                            if (window == "truncated") {
                                listOf(ParseWarning(WarningCode.ITEMS_TRUNCATED))
                            } else {
                                emptyList()
                            },
                    ),
                    IngestContext(
                        if (window == "older") {
                            IngestMode.OLDER_PAGE
                        } else if (initial) {
                            IngestMode.INITIAL
                        } else {
                            IngestMode.REFRESH
                        },
                        partial,
                        fetchMeta(),
                    ),
                )

            ingest(listOf(a, b), initial = true)
            val aBefore = assertNotNull(db.episodeDao().byIdentityKey(id, "g:x"))
            val bBefore = assertNotNull(db.episodeDao().byIdentityKey(id, "g:y"))
            db.episodeStateDao().upsert(episodeStateEntity(aBefore.id, playedAt = clock.now() - 1000))
            db.episodeStateDao().upsert(episodeStateEntity(bBefore.id).copy(isFavorite = true))

            // Reachable rotation: `y` becomes shared (KNOWN_AMBIGUOUS); Alpha's row keeps the
            // occupied `g:x` key while its current GUID becomes `y`; Beta rekeys to a
            // fallback. `x` loses its last carrier yet stays unproven — UNKNOWN on the
            // legacy feed, or OBSERVED on the covered feed's incomplete documents.
            ingest(listOf(a.copy(guid = "y"), b))
            val rotatedShell = assertNotNull(db.episodeDao().byId(aBefore.id))
            assertEquals("g:x", rotatedShell.identityKey)
            assertEquals("y", rotatedShell.guid)
            assertNotEquals("g:x", assertNotNull(db.episodeDao().byId(bBefore.id)).identityKey)
            assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, db.ingestDao().guidKnowledge(id)["y"])
            assertNull(db.ingestDao().guidKnowledge(id)["x"])
            if (covered) assertTrue("x" in db.ingestDao().recordedGuids(id))

            if (reopen) {
                db.close()
                db = TestDb.file(dir, clock = clock)
            }

            // Beta's own row still carries Beta's enclosure; Beta returns carrying the erased `x`.
            ingest(listOf(b.copy(guid = "x", feedOrder = 0)))

            val afterA = assertNotNull(db.episodeDao().byId(aBefore.id))
            val afterB = assertNotNull(db.episodeDao().byId(bBefore.id))
            assertEquals(
                a.primaryEnclosure!!.url,
                afterA.enclosureUrl,
                "Beta's rotated GUID must not claim Alpha's shell",
            )
            assertEquals("Alpha", afterA.title)
            assertEquals(
                clock.now() - 1000,
                assertNotNull(db.episodeStateDao().byEpisode(aBefore.id)).playedAt,
            )
            assertTrue(assertNotNull(db.episodeStateDao().byEpisode(bBefore.id)).isFavorite)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyLeadingZeroRotationPreservesA() =
        runTest { preservesForeignShell("127.000.000.001", covered = false, partial = false, reopen = false) }

    @Test
    fun legacyTrailingDotRotationAcrossRestartPreservesA() =
        runTest { preservesForeignShell("127.0.0.1.", covered = false, partial = false, reopen = true) }

    @Test
    fun coveredPartialLeadingZeroRotationPreservesA() =
        runTest { preservesForeignShell("127.000.000.001", covered = true, partial = true, reopen = true) }

    @Test
    fun coveredPagedTrailingDotRotationPreservesA() =
        runTest { preservesForeignShell("127.0.0.1.", covered = true, partial = false, reopen = true, window = "next") }

    @Test
    fun coveredArchiveLeadingZeroRotationPreservesA() =
        runTest {
            preservesForeignShell(
                "127.000.000.001",
                covered = true,
                partial = false,
                reopen = true,
                window = "archive",
            )
        }

    @Test
    fun coveredTruncatedLeadingZeroRotationPreservesA() =
        runTest {
            preservesForeignShell(
                "127.000.000.001",
                covered = true,
                partial = false,
                reopen = true,
                window = "truncated",
            )
        }

    @Test
    fun coveredOlderPageTrailingDotRotationPreservesA() =
        runTest {
            preservesForeignShell(
                "127.0.0.1.",
                covered = true,
                partial = false,
                reopen = true,
                window = "older",
            )
        }

    @Test
    fun legacyDecimalRotationControlPreservesA() =
        runTest { preservesForeignShell("127.0.0.1", covered = false, partial = false, reopen = true) }

    private companion object {
        const val DAY = 86_400_000L
    }
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.paging.PagingSource
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.importItemEntity
import ch.lkmc.neutrodyne.core.testing.database.importSessionEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `ImportDao` (02 import_session/import_item, Import commit; 05 OPML import): the cascade,
 * progress counts, paged preview filtering, selection and status writes, and the seven-day
 * session cleanup.
 */
class ImportDaoTest {
    private val db = TestDb.inMemory()
    private val dao = db.importDao()

    @AfterTest fun close() = db.close()

    @Test fun deleteSessionCascadesItsItems() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            val other = dao.insertSession(importSessionEntity())
            dao.insertItems((0..2).map { importItemEntity(session, it) } + importItemEntity(other, 0))

            dao.deleteSession(session)

            assertNull(dao.sessionById(session))
            assertEquals(emptyList(), dao.itemsOf(session))
            assertEquals(1, dao.itemsOf(other).size, "the other session's items survive")
        }

    @Test fun sessionWritesUpdateTheObservedRow() =
        runTest {
            val session = dao.insertSession(importSessionEntity(createdAt = 10))
            dao.setPayloadPath(session, "import/$session.bin")
            dao.setSourceName(session, "feeds.opml")
            dao.setOptionsJson(session, """{"importGroups":false}""")
            dao.setWarningsJson(session, """[{"code":"SALVAGED"}]""")

            assertNotNull(dao.sessionById(session)).let {
                assertEquals("import/$session.bin", it.payloadPath)
                assertEquals("feeds.opml", it.sourceName)
                assertEquals("""{"importGroups":false}""", it.optionsJson)
                assertEquals("""[{"code":"SALVAGED"}]""", it.warningsJson)
            }

            dao.observeSession(session).test {
                assertEquals(ImportState.PREVIEW, awaitItem()?.state)
                dao.setState(session, ImportState.COMMITTED)
                assertEquals(ImportState.COMMITTED, awaitItem()?.state)
                dao.setState(session, ImportState.FETCHING)
                assertEquals(ImportState.FETCHING, awaitItem()?.state)
                dao.markDone(session, finishedAt = 99)
                awaitItem().let {
                    assertEquals(ImportState.DONE, it?.state)
                    assertEquals(99, it?.finishedAt)
                }
                // A guarded write with the same value is a no-op (02 DAO rule 2).
                dao.setState(session, ImportState.DONE)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun openAndActiveSessionsFollowState() =
        runTest {
            val preview = dao.insertSession(importSessionEntity(createdAt = 3))
            val fetching = dao.insertSession(importSessionEntity(createdAt = 1, state = ImportState.FETCHING))
            val committed = dao.insertSession(importSessionEntity(createdAt = 2, state = ImportState.COMMITTED))
            dao.insertSession(importSessionEntity(createdAt = 4, state = ImportState.DONE))
            dao.insertSession(importSessionEntity(createdAt = 5, state = ImportState.CANCELLED))

            assertEquals(
                listOf(fetching, committed, preview),
                dao.observeOpenSessions().first().map { it.id },
                "PREVIEW, COMMITTED and FETCHING, oldest first",
            )
            assertEquals(listOf(fetching, committed), dao.activeSessions().map { it.id })
        }

    @Test fun progressAndTalliesCountByStatus() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            dao.insertItems(
                listOf(
                    importItemEntity(session, 0),
                    importItemEntity(session, 1, selected = false),
                    importItemEntity(session, 2, status = ImportItemStatus.QUEUED),
                    importItemEntity(session, 3, status = ImportItemStatus.QUEUED),
                    importItemEntity(session, 4, status = ImportItemStatus.SUBSCRIBED),
                    importItemEntity(session, 5, status = ImportItemStatus.INVALID_URL, selected = false),
                    importItemEntity(
                        session,
                        6,
                        kind = ImportItemKind.YOUTUBE,
                        status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                        selected = false,
                    ),
                ),
            )

            assertEquals(
                mapOf(
                    ImportItemStatus.PREVIEW to 2,
                    ImportItemStatus.QUEUED to 2,
                    ImportItemStatus.SUBSCRIBED to 1,
                    ImportItemStatus.INVALID_URL to 1,
                    ImportItemStatus.YOUTUBE_UNSUPPORTED_YET to 1,
                ),
                dao.observeProgress(session).first().associate { it.status to it.count },
            )

            assertEquals(
                ImportItemTallies(itemCount = 7, selectedCount = 4, youtubeCount = 1),
                dao.observeItemTallies(session).first(),
            )
            assertEquals(2, dao.queuedCount(session))
        }

    @Test fun pagedItemsFiltersStatusesAcrossPageBoundaries() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            // Interleave statuses so the filter, not the ordinal, decides membership.
            val statuses =
                listOf(
                    ImportItemStatus.PREVIEW,
                    ImportItemStatus.INVALID_URL,
                    ImportItemStatus.PREVIEW,
                    ImportItemStatus.QUEUED,
                    ImportItemStatus.PREVIEW,
                    ImportItemStatus.INVALID_URL,
                    ImportItemStatus.PREVIEW,
                    ImportItemStatus.QUEUED,
                    ImportItemStatus.PREVIEW,
                )
            dao.insertItems(
                statuses.mapIndexed { i, status -> importItemEntity(session, i, status = status) },
            )
            val wanted = listOf(ImportItemStatus.PREVIEW, ImportItemStatus.QUEUED)
            val expected = statuses.mapIndexedNotNull { i, status -> i.takeIf { status in wanted } }

            suspend fun page(
                statuses: List<ImportItemStatus>,
                params: PagingSource.LoadParams<Int>,
            ): PagingSource.LoadResult.Page<Int, ImportItemEntity> {
                val result = dao.pagedItems(session, statuses).load(params)
                assertIs<PagingSource.LoadResult.Page<Int, ImportItemEntity>>(result)
                return result
            }

            val first =
                page(wanted, PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false))
            assertEquals(expected.subList(0, 2), first.data.map { it.ordinal })

            // Pages join at the boundary with no duplicate or skipped ordinals.
            val seen = first.data.toMutableList()
            var nextKey = first.nextKey
            while (nextKey != null) {
                val next =
                    page(
                        wanted,
                        PagingSource.LoadParams.Append(key = nextKey, loadSize = 2, placeholdersEnabled = false),
                    )
                seen += next.data
                nextKey = next.nextKey
            }
            assertEquals(expected, seen.map { it.ordinal })

            // 05's ALL filter passes every status.
            val all =
                page(
                    ImportItemStatus.entries,
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
                )
            assertEquals(statuses.indices.toList(), all.data.map { it.ordinal })
        }

    @Test fun selectionWritesToggleRowsSetsAndFilters() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            dao.insertItems(
                (0..5).map {
                    // ALREADY_SUBSCRIBED rows are never selectable (05).
                    importItemEntity(
                        session,
                        it,
                        status = if (it == 5) ImportItemStatus.ALREADY_SUBSCRIBED else ImportItemStatus.PREVIEW,
                        selected = it != 5,
                    )
                },
            )

            dao.setSelected(session, ordinal = 0, selected = false)
            assertEquals(false, dao.itemsOf(session)[0].selected)

            // A Kotlin-resolved ordinal set (a group's members).
            dao.setSelectedOrdinals(session, ordinals = (1..3).toList(), selected = false)
            assertEquals(
                listOf(false, false, false, false, true, false),
                dao.itemsOf(session).map { it.selected },
            )

            // "Select none" then "select all" of the preview filter leaves other statuses alone.
            dao.setSelectedByStatus(session, listOf(ImportItemStatus.PREVIEW), selected = false)
            assertEquals(List(6) { false }, dao.itemsOf(session).map { it.selected })
            dao.setSelectedByStatus(session, listOf(ImportItemStatus.PREVIEW), selected = true)
            assertEquals(
                listOf(true, true, true, true, true, false),
                dao.itemsOf(session).map { it.selected },
                "ALREADY_SUBSCRIBED is not part of the filter",
            )
        }

    @Test fun itemStatusWrites() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            dao.insertItems((0..4).map { importItemEntity(session, it) })
            val podcastId = db.podcastDao().insertPodcast(podcastEntity())

            // Commit step 5: item → QUEUED with its podcast.
            dao.markQueued(session, ordinal = 0, podcastId = podcastId)
            dao.itemsOf(session)[0].let {
                assertEquals(ImportItemStatus.QUEUED, it.status)
                assertEquals(podcastId, it.podcastId)
            }

            // A fetch outcome: final status plus errorDetail; null clears it.
            dao.setItemStatus(session, ordinal = 0, status = ImportItemStatus.FETCH_FAILED, errorDetail = "OFFLINE")
            dao.itemsOf(session)[0].let {
                assertEquals(ImportItemStatus.FETCH_FAILED, it.status)
                assertEquals("OFFLINE", it.errorDetail)
                assertEquals(podcastId, it.podcastId, "podcastId is never rewritten")
            }
            dao.setItemStatus(session, ordinal = 0, status = ImportItemStatus.SUBSCRIBED, errorDetail = null)
            assertNull(dao.itemsOf(session)[0].errorDetail)

            // Retry re-queues a set and clears their errors.
            dao.setItemStatus(session, ordinal = 1, status = ImportItemStatus.AUTH_REQUIRED, errorDetail = "HTTP_AUTH")
            dao.requeueItems(session, ordinals = listOf(0, 1))
            assertEquals(
                listOf(ImportItemStatus.QUEUED, ImportItemStatus.QUEUED),
                dao.itemsByOrdinals(session, listOf(0, 1)).map { it.status },
            )
            assertNull(dao.itemsByOrdinals(session, listOf(1)).single().errorDetail)

            // Commit's redaction of originalUrl.
            dao.setItemOriginalUrl(session, ordinal = 2, originalUrl = "https://u:***@host/feed")
            assertEquals("https://u:***@host/feed", dao.itemsOf(session)[2].originalUrl)

            // Remove deletes the rows the session created.
            dao.deleteItems(session, ordinals = listOf(3, 4))
            assertEquals(listOf(0, 1, 2), dao.itemsOf(session).map { it.ordinal })

            // The commit and fetch-loop reads.
            assertEquals(listOf(2), dao.selectedItems(session).map { it.ordinal })
            assertEquals(
                listOf(0, 1),
                dao.itemsByStatus(session, listOf(ImportItemStatus.QUEUED)).map { it.ordinal },
            )
        }

    @Test fun expiredSessionsSelectsFinishedAndAbandonedOnly() =
        runTest {
            val cutoff = 1_000L
            val done =
                dao.insertSession(importSessionEntity(createdAt = 10, state = ImportState.DONE, finishedAt = 500))
            val cancelled = dao.insertSession(importSessionEntity(createdAt = 20, state = ImportState.CANCELLED))
            val abandoned = dao.insertSession(importSessionEntity(createdAt = 30))
            // Boundary and live states never expire: DONE at the cutoff, fresh PREVIEW,
            // old COMMITTED and FETCHING.
            dao.insertSession(importSessionEntity(createdAt = 10, state = ImportState.DONE, finishedAt = cutoff))
            dao.insertSession(importSessionEntity(createdAt = cutoff))
            dao.insertSession(importSessionEntity(createdAt = 10, state = ImportState.COMMITTED))
            dao.insertSession(importSessionEntity(createdAt = 10, state = ImportState.FETCHING))
            dao.setPayloadPath(done, "import/$done.bin")

            val expired = dao.expiredSessions(cutoff)
            assertEquals(
                setOf(done, cancelled, abandoned),
                expired.map { it.id }.toSet(),
                "old DONE/CANCELLED/PREVIEW expire; fresh and COMMITTED/FETCHING never do",
            )
            assertEquals("import/$done.bin", expired.single { it.id == done }.payloadPath)
            assertNull(expired.single { it.id == abandoned }.payloadPath)

            dao.deleteSession(done)
            assertEquals(setOf(cancelled, abandoned), dao.expiredSessions(cutoff).map { it.id }.toSet())
        }

    @Test fun ordinalWritesChunkBeyondOneThousandIds() =
        runTest {
            val session = dao.insertSession(importSessionEntity())
            val count = 1_100
            dao.insertItems((0 until count).map { importItemEntity(session, it) })

            // Three chunks of ordinals must all land — one statement can bind ≤ 500 ids.
            dao.setSelectedOrdinals(session, (0 until count).toList(), selected = false)
            assertEquals(0, dao.itemsOf(session).count { it.selected })

            dao.requeueItems(session, (0 until count).toList())
            assertEquals(count, dao.queuedCount(session))
            assertEquals(count, dao.itemsByStatus(session, listOf(ImportItemStatus.QUEUED)).size)

            dao.deleteItems(session, (0 until count - 50).toList())
            assertEquals(50, dao.itemsOf(session).size)
        }
}

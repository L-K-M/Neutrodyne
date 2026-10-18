// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportItemFilter
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.SourceGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The fake's own session logic: create/confirm/cancel transitions, the derived counts,
 * selection rules and the 05 filter → status table.
 */
class FakeImportRepositoryTest {
    private fun item(
        ordinal: Int,
        status: ImportItemStatus,
        kind: ImportItemKind = ImportItemKind.RSS,
        selected: Boolean = false,
        podcastId: Long? = null,
    ) = ImportItemView(
        ordinal = ordinal,
        title = "Title $ordinal",
        host = "host$ordinal.example",
        kind = kind,
        status = status,
        selected = selected,
        selectable = status == ImportItemStatus.PREVIEW,
        groupNames = emptyList(),
        isPrivate = false,
        errorDetail = null,
        podcastId = podcastId,
    )

    private suspend fun FakeImportRepository.createWith(vararg sessionItems: ImportItemView): Long {
        createItems = sessionItems.toList()
        return assertIs<Outcome.Success<Long>>(create(ImportSource("content://x", "feeds.opml"))).value
    }

    @Test fun createSeedsAPreviewSessionAndDerivesCounts() =
        runTest {
            val repo = FakeImportRepository()
            val id =
                repo.createWith(
                    item(0, ImportItemStatus.PREVIEW, selected = true),
                    item(1, ImportItemStatus.PREVIEW),
                    item(2, ImportItemStatus.ALREADY_SUBSCRIBED, podcastId = 5L),
                    item(3, ImportItemStatus.PREVIEW, kind = ImportItemKind.YOUTUBE, selected = true),
                )

            val session = repo.observeSession(id).first()!!
            assertEquals(ImportState.PREVIEW, session.state)
            assertEquals("feeds.opml", session.sourceName)
            assertEquals(
                mapOf(ImportItemStatus.PREVIEW to 3, ImportItemStatus.ALREADY_SUBSCRIBED to 1),
                session.counts,
            )
            assertEquals(2, session.selectedCount)
            assertEquals(1, session.youtubeCount)
            assertEquals(listOf(id), repo.observeOpenSessions().first().map { it.id })
        }

    @Test fun setSelectedOnlyTouchesPreviewItems() =
        runTest {
            val repo = FakeImportRepository()
            val id =
                repo.createWith(
                    item(0, ImportItemStatus.PREVIEW, selected = true),
                    item(1, ImportItemStatus.PREVIEW),
                    item(2, ImportItemStatus.ALREADY_SUBSCRIBED),
                )

            repo.setSelected(id, null, false)
            assertEquals(0, repo.observeSession(id).first()!!.selectedCount)

            repo.setSelected(id, setOf(1), true)
            assertEquals(
                mapOf(0 to false, 1 to true, 2 to false),
                repo.items.value
                    .getValue(id)
                    .associate { it.ordinal to it.selected },
            )
        }

    @Test fun confirmQueuesSelectedPreviewItemsAndFailsOnAGoneSession() =
        runTest {
            val repo = FakeImportRepository()
            val id =
                repo.createWith(
                    item(0, ImportItemStatus.PREVIEW, selected = true),
                    item(1, ImportItemStatus.PREVIEW),
                )

            assertEquals(Outcome.Success(Unit), repo.confirm(id))
            val session = repo.observeSession(id).first()!!
            assertEquals(ImportState.COMMITTED, session.state)
            assertEquals(true, session.options.committedAt != null)
            assertEquals(
                listOf(ImportItemStatus.QUEUED, ImportItemStatus.PREVIEW),
                repo.items.value
                    .getValue(id)
                    .map { it.status },
            )
            assertEquals(Outcome.Failure(ImportError.SessionGone), repo.confirm(999L))
        }

    @Test fun filterMappingMatchesTheSessionTable() =
        runTest {
            val repo = FakeImportRepository()
            val statuses = ImportItemStatus.entries.toList()
            val id = repo.createWith(*statuses.mapIndexed { i, s -> item(i, s) }.toTypedArray())

            // asSnapshot collects the flow to completion; feed it one emission.
            suspend fun ordinals(filter: ImportItemFilter) =
                flowOf(repo.pagedItems(id, filter).first()).asSnapshot().map { it.status }

            assertEquals(statuses, ordinals(ImportItemFilter.ALL))
            assertEquals(listOf(ImportItemStatus.PREVIEW), ordinals(ImportItemFilter.NEW_ONLY))
            assertEquals(
                listOf(
                    ImportItemStatus.DUPLICATE_IN_FILE,
                    ImportItemStatus.INVALID_URL,
                    ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                ),
                ordinals(ImportItemFilter.NOT_IMPORTED),
            )
            assertEquals(
                listOf(
                    ImportItemStatus.NOT_A_FEED,
                    ImportItemStatus.NO_MEDIA,
                    ImportItemStatus.AUTH_REQUIRED,
                    ImportItemStatus.GONE,
                    ImportItemStatus.FETCH_FAILED,
                ),
                ordinals(ImportItemFilter.NEEDS_ATTENTION),
            )

            // Past PREVIEW, unselected PREVIEW items also count as not imported (05).
            repo.sessions.value =
                repo.sessions.value.mapValues { it.value.copy(state = ImportState.FETCHING) }
            assertEquals(
                listOf(
                    ImportItemStatus.PREVIEW,
                    ImportItemStatus.DUPLICATE_IN_FILE,
                    ImportItemStatus.INVALID_URL,
                    ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                ),
                ordinals(ImportItemFilter.NOT_IMPORTED),
            )
        }

    @Test fun stalePreviewChangesPreserveWorkerProgressAndParseMetadata() =
        runTest {
            val repo = FakeImportRepository()
            val id = repo.createWith(item(0, ImportItemStatus.PREVIEW, selected = true))
            val previewOptions = repo.observeSession(id).first()!!.options

            repo.confirm(id)
            val committed = repo.sessions.value.getValue(id)
            val workerOptions =
                committed.options.copy(
                    fromAndroidBackup = true,
                    restoreRequestedAt = 123L,
                    laneAttempts = 2,
                    sourceGroups = listOf(SourceGroup("news", "News", null, null, null)),
                    attemptMarkerAt = 456L,
                )
            repo.sessions.value = mapOf(id to committed.copy(options = workerOptions))

            // A stale screen must not roll back a commit or clear a queued restore.
            repo.updateOptions(id) { previewOptions.copy(importGroups = false) }

            assertEquals(
                workerOptions,
                repo.observeSession(id).first()!!.options,
            )
        }

    @Test fun previewChangesPreserveParsedGroupsAndSnapshotOrigin() =
        runTest {
            val repo = FakeImportRepository()
            val id = repo.createWith(item(0, ImportItemStatus.PREVIEW))
            val preview = repo.sessions.value.getValue(id)
            val options =
                preview.options.copy(
                    fromAndroidBackup = true,
                    sourceGroups = listOf(SourceGroup("news", "News", null, null, null)),
                    itemCustomTitles = mapOf(1 to "Parser Title"),
                )
            repo.sessions.value = mapOf(id to preview.copy(options = options))

            repo.updateOptions(id) { preview.options.copy(importGroups = false) }

            assertEquals(
                options.copy(importGroups = false),
                repo.observeSession(id).first()!!.options,
            )
        }

    @Test fun requestedRestoreCannotBeChangedByPreviewOptions() =
        runTest {
            val repo = FakeImportRepository()
            val id = repo.createWith(item(0, ImportItemStatus.PREVIEW))
            val preview = repo.sessions.value.getValue(id)
            val requested = preview.options.copy(restoreRequestedAt = 123L)
            repo.sessions.value = mapOf(id to preview.copy(options = requested))

            repo.updateOptions(id) { preview.options.copy(notifyNewEpisodes = true) }

            assertEquals(requested, repo.observeSession(id).first()!!.options)
        }

    @Test fun previewChangesCannotForgeWorkerProgress() =
        runTest {
            val repo = FakeImportRepository()
            val id = repo.createWith(item(0, ImportItemStatus.PREVIEW))
            val preview = repo.sessions.value.getValue(id)
            val options =
                preview.options.copy(itemCustomTitles = mapOf(1 to "Parser Title"))
            repo.sessions.value = mapOf(id to preview.copy(options = options))

            repo.updateOptions(id) {
                it.copy(
                    notifyNewEpisodes = true,
                    fromAndroidBackup = true,
                    restoreRequestedAt = 123L,
                    laneAttempts = 2,
                    sourceGroups = listOf(SourceGroup("news", "News", null, null, null)),
                    itemCustomTitles = mapOf(9 to "Forged Title"),
                    committedAt = 456L,
                    attemptMarkerAt = 789L,
                )
            }

            assertEquals(
                options.copy(notifyNewEpisodes = true),
                repo.observeSession(id).first()!!.options,
            )
        }

    @Test fun cancelDeletesAPreviewSessionAndCancelsALaterOne() =
        runTest {
            val repo = FakeImportRepository()
            val preview = repo.createWith(item(0, ImportItemStatus.PREVIEW))
            repo.cancel(preview)
            assertNull(repo.observeSession(preview).first())
            assertEquals(emptyList(), repo.observeOpenSessions().first())

            val committed = repo.createWith(item(0, ImportItemStatus.PREVIEW, selected = true))
            repo.confirm(committed)
            repo.cancel(committed)
            assertEquals(ImportState.CANCELLED, repo.observeSession(committed).first()!!.state)
            assertEquals(emptyList(), repo.observeOpenSessions().first())
        }

    @Test fun retryRequeuesAttentionItemsAndReopensDone() =
        runTest {
            val repo = FakeImportRepository()
            val id =
                repo.createWith(
                    item(0, ImportItemStatus.FETCH_FAILED),
                    item(1, ImportItemStatus.NO_MEDIA),
                    item(2, ImportItemStatus.SUBSCRIBED, podcastId = 7L),
                )
            repo.sessions.value =
                repo.sessions.value.mapValues { it.value.copy(state = ImportState.DONE) }

            repo.retry(id, setOf(0, 1, 2))
            assertEquals(
                listOf(ImportItemStatus.QUEUED, ImportItemStatus.QUEUED, ImportItemStatus.SUBSCRIBED),
                repo.items.value
                    .getValue(id)
                    .map { it.status },
            )
            assertEquals(ImportState.FETCHING, repo.observeSession(id).first()!!.state)
        }

    @Test fun removeDropsOnlyItemsTheSessionCreated() =
        runTest {
            val repo = FakeImportRepository()
            val id =
                repo.createWith(
                    item(0, ImportItemStatus.QUEUED, podcastId = 9L),
                    item(1, ImportItemStatus.NOT_A_FEED, podcastId = 10L),
                    item(2, ImportItemStatus.ALREADY_SUBSCRIBED, podcastId = 5L),
                    item(3, ImportItemStatus.MERGED, podcastId = 6L),
                    item(4, ImportItemStatus.PREVIEW),
                )

            repo.remove(id, setOf(0, 1, 2, 3, 4))
            assertEquals(
                listOf(ImportItemStatus.ALREADY_SUBSCRIBED, ImportItemStatus.MERGED, ImportItemStatus.PREVIEW),
                repo.items.value
                    .getValue(id)
                    .map { it.status },
            )
        }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.model.NewEpisodeLine
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 03's desktop new-episode poster rows (`desktopTest` with a fake `DesktopNotifier`): one
 * notification per run under the stable `new-episodes` id; the unseen set merges across
 * refreshes, caps at 50 and clears on focus or a click; `removePodcasts` re-posts the
 * remainder or cancels. Body shapes per 03: single line, per-group lines with the
 * "and N more" tail, one group's route only when every episode belongs to it.
 */
class DesktopNewEpisodePosterTest {
    private val originalLocale = Locale.getDefault()

    @Before
    fun english() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private val posted = mutableListOf<DesktopNotification>()
    private val cancelled = mutableListOf<String>()
    private val notifier =
        object : DesktopNotifier {
            override suspend fun post(n: DesktopNotification) {
                posted += n
            }

            override fun cancel(id: String) {
                cancelled += id
            }
        }
    private val poster = DesktopNewEpisodePoster(notifier)

    @Test
    fun aSingleEpisodeShowsPodcastAndEpisodeAndRoutesToIt() =
        runBlocking {
            poster.post(listOf(notice(groupUuid = "g1", groupName = "Tech", episodes = listOf(line(11, 1, "Podcast A", "Episode 1")))))

            val item = posted.single()
            assertThat(item.id).isEqualTo("new-episodes")
            assertThat(item.title).isEqualTo("Podcast A")
            assertThat(item.body).isEqualTo("Episode 1")
            assertThat(item.route).isEqualTo("neutrodyne://open/episode/11")
        }

    @Test
    fun refreshesMergeIntoOneReplacingNotificationWithGroupLines() =
        runBlocking {
            poster.post(
                listOf(
                    notice("g1", "Tech", listOf(line(11, 1, "Podcast A", "E1"), line(12, 2, "Podcast B", "E2"))),
                    notice(null, null, listOf(line(13, 3, "Podcast C", "E3"))),
                ),
            )
            poster.post(listOf(notice("g1", "Tech", listOf(line(14, 1, "Podcast A", "E4")))))

            assertThat(posted).hasSize(2)
            val merged = posted.last()
            assertThat(merged.id).isEqualTo("new-episodes")
            assertThat(merged.title).isEqualTo("4 new episodes")
            assertThat(merged.body)
                .isEqualTo("Tech: 3 — Podcast A, Podcast B\nOther podcasts: 1 — Podcast C")
            // Episodes of two groups: no route, a click only shows the window.
            assertThat(merged.route).isNull()
            assertThat(cancelled).isEmpty()
        }

    @Test
    fun aSingleGroupAcrossRefreshesRoutesToTheGroup() =
        runBlocking {
            poster.post(listOf(notice("g1", "Tech", listOf(line(11, 1, "Podcast A", "E1")))))
            poster.post(listOf(notice("g1", "Tech", listOf(line(12, 2, "Podcast B", "E2")))))

            assertThat(posted.last().route).isEqualTo("neutrodyne://open/group/g1")
        }

    @Test
    fun moreThanFourGroupsGetAnAndMoreTail() =
        runBlocking {
            val notices =
                (1..6).map { g ->
                    notice("g$g", "Group $g", listOf(line(g.toLong() * 100, g.toLong(), "Podcast $g", "E$g")))
                }
            poster.post(notices)

            val body = posted.single().body
            assertThat(body.lines()).hasSize(5)
            assertThat(body.lines().last()).isEqualTo("and 2 more")
        }

    @Test
    fun theUnseenSetCapsAtFiftyNewestLines() =
        runBlocking {
            poster.post(listOf(notice("g1", "Tech", (1..60).map { line(it.toLong(), 1, "P", "E$it") })))

            val merged = posted.single()
            assertThat(merged.title).isEqualTo("50 new episodes")
            // The oldest ten fell off: the body's count names the surviving fifty.
            assertThat(merged.body).contains("Tech: 50 — P")
        }

    @Test
    fun focusClearsTheUnseenSet() =
        runBlocking {
            poster.post(listOf(notice("g1", "Tech", listOf(line(11, 1, "A", "E1")))))
            poster.clearUnseen()
            posted.clear()

            poster.post(listOf(notice("g1", "Tech", listOf(line(12, 2, "B", "E2")))))

            val item = posted.single()
            assertThat(item.title).isEqualTo("B")
            assertThat(item.route).isEqualTo("neutrodyne://open/episode/12")
        }

    @Test
    fun removePodcastsDropsLinesAndRepostsTheRemainder() =
        runBlocking {
            poster.post(
                listOf(
                    notice("g1", "Tech", listOf(line(11, 1, "Podcast A", "E1"))),
                    notice(null, null, listOf(line(12, 2, "Podcast B", "E2"))),
                ),
            )
            posted.clear()

            poster.removePodcasts(listOf(1L))

            val item = posted.single()
            assertThat(item.title).isEqualTo("Podcast B")
            assertThat(cancelled).isEmpty()
        }

    @Test
    fun removePodcastsCancelsWhenNothingRemains() =
        runBlocking {
            poster.post(listOf(notice("g1", "Tech", listOf(line(11, 1, "Podcast A", "E1")))))
            posted.clear()

            poster.removePodcasts(listOf(1L))

            assertThat(posted).isEmpty()
            assertThat(cancelled).containsExactly("new-episodes")
            Unit
        }

    private fun notice(
        groupUuid: String?,
        groupName: String?,
        episodes: List<NewEpisodeLine>,
    ) = NewEpisodeNotice(groupUuid = groupUuid, groupName = groupName, episodes = episodes)

    private fun line(
        episodeId: Long,
        podcastId: Long,
        podcastTitle: String,
        episodeTitle: String,
    ) = NewEpisodeLine(episodeId, podcastId, podcastTitle, episodeTitle, artworkKey = null)
}

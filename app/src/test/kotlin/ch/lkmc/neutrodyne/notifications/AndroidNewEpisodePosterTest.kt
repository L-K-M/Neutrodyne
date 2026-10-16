// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.NewEpisodeLine
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalResourceApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidNewEpisodePosterTest {
    private val application = RuntimeEnvironment.getApplication()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val shadowManager = shadowOf(manager)
    private val clock = TestClock(1_790_000_000_000L)
    private lateinit var db: NeutrodyneDatabase
    private lateinit var poster: AndroidNewEpisodePoster
    private lateinit var previousLocale: Locale
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        setResourceReaderAndroidContext(application)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
        db = TestDb.inMemory(application, clock = clock)
        poster = AndroidNewEpisodePoster(application, db, clock)
        Log.install(LogSink { _, _, message -> logs += message })
    }

    @After
    fun tearDown() {
        db.close()
        Locale.setDefault(previousLocale)
        Log.install()
    }

    @Test
    fun postsOncePerChannelAndRoutesDefaultAndGroupSeparately() =
        runBlocking<Unit> {
            val a = line()
            val b = line()
            val c = line()
            poster.post(listOf(notice(a, b), notice(c, uuid = "tech", name = "Tech")))

            assertThat(manager.activeNotifications.map { it.tag }).containsExactly(DEFAULT, GROUP)
            assertThat(shadowManager.size()).isEqualTo(2)
            assertThat(notification(DEFAULT).channelId).isEqualTo(DEFAULT)
            assertThat(notification(GROUP).channelId).isEqualTo(GROUP)
            assertThat(
                notification(DEFAULT).extras.getLongArray(IDS)!!.toList(),
            ).containsExactly(b.episodeId, a.episodeId).inOrder()
            assertThat(notification(GROUP).extras.getString(Notification.EXTRA_TITLE)).isEqualTo(c.podcastTitle)
            assertThat(notification(GROUP).extras.getString(Notification.EXTRA_TEXT)).isEqualTo(c.episodeTitle)
            assertThat(notification(GROUP).smallIcon.resId).isEqualTo(R.drawable.ic_stat_neutrodyne)
            assertThat(notification(GROUP).category).isEqualTo(Notification.CATEGORY_RECOMMENDATION)
            assertThat(notification(GROUP).`when`).isEqualTo(clock.now())
            assertThat(notification(GROUP).flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
            assertThat(notification(GROUP).flags and Notification.FLAG_ONLY_ALERT_ONCE).isEqualTo(0)
        }

    @Test
    fun channelCreationIsIdempotentAndPreservesUserSettings() =
        runBlocking<Unit> {
            val episode = line()
            poster.post(listOf(notice(episode, uuid = "tech", name = "Tech")))
            val channel = manager.getNotificationChannel(GROUP)
            channel.importance = NotificationManager.IMPORTANCE_LOW
            channel.enableVibration(true)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
            poster.post(listOf(notice(episode, uuid = "tech", name = "A rename belongs to C5")))

            assertThat(manager.notificationChannels.map { it.id }).containsExactly(DEFAULT, GROUP)
            assertThat(manager.notificationChannelGroups.map { it.id }).containsExactly(CHANNEL_GROUP)
            assertThat(manager.getNotificationChannel(DEFAULT).name.toString()).isEqualTo("Other podcasts")
            assertThat(manager.getNotificationChannel(DEFAULT).importance)
                .isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
            assertThat(manager.getNotificationChannel(GROUP).name.toString()).isEqualTo("Tech")
            assertThat(manager.getNotificationChannel(GROUP).importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
            assertThat(manager.getNotificationChannel(GROUP).shouldVibrate()).isTrue()
            assertThat(manager.getNotificationChannel(GROUP).canShowBadge()).isFalse()
        }

    @Test
    fun missingGroupChannelUsesCurrentNameAndDefaultImportance() =
        runBlocking<Unit> {
            poster.post(listOf(notice(line(), uuid = "tech", name = "Current group name")))

            val channel = manager.getNotificationChannel(GROUP)
            assertThat(channel.name.toString()).isEqualTo("Current group name")
            assertThat(channel.group).isEqualTo(CHANNEL_GROUP)
            assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        }

    @Test
    fun permissionDeniedSkipsAndLogsTheNotice() =
        runBlocking<Unit> {
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            poster.post(listOf(notice(line())))

            assertThat(shadowManager.size()).isEqualTo(0)
            assertThat(logs).contains("Skipped 1 new-episode notification: permission denied")
        }

    @Test
    @Config(sdk = [32])
    fun olderAndroidDoesNotRequirePostNotificationsPermission() =
        runBlocking<Unit> {
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            poster.post(listOf(notice(line())))

            assertThat(shadowManager.size()).isEqualTo(1)
        }

    @Test
    fun appNotificationsDisabledSkipsAndLogsEachChannel() =
        runBlocking<Unit> {
            shadowManager.setNotificationsEnabled(false)
            poster.post(listOf(notice(line()), notice(line(), uuid = "tech", name = "Tech")))

            assertThat(shadowManager.size()).isEqualTo(0)
            assertThat(logs).containsExactly(
                "Skipped 1 new-episode notification: notifications disabled",
                "Skipped 1 new-episode notification: notifications disabled",
            )
        }

    @Test
    fun blockedChannelSkipsWithoutBlockingOtherChannels() =
        runBlocking<Unit> {
            manager.createNotificationChannel(
                NotificationChannel(DEFAULT, "Muted", NotificationManager.IMPORTANCE_NONE),
            )
            poster.post(listOf(notice(line()), notice(line(), uuid = "tech", name = "Tech")))

            assertThat(manager.activeNotifications.map { it.tag }).containsExactly(GROUP)
            assertThat(manager.getNotificationChannel(DEFAULT).importance)
                .isEqualTo(NotificationManager.IMPORTANCE_NONE)
            assertThat(logs).contains("Skipped 1 new-episode notification: channel blocked")
        }

    @Test
    fun mergesActiveIdsAfterRestartDeduplicatesAndRetainsNewestFifty() =
        runBlocking<Unit> {
            val episodes = List(55) { line(sortDate = it.toLong()) }
            poster.post(listOf(notice(*episodes.takeLast(40).toTypedArray())))
            val restarted = AndroidNewEpisodePoster(application, db, clock)
            restarted.post(listOf(notice(*episodes.take(30).toTypedArray())))

            val notification = notification(DEFAULT)
            assertThat(shadowManager.size()).isEqualTo(1)
            assertThat(notification.extras.getLongArray(IDS)!!.toList())
                .containsExactlyElementsIn(episodes.takeLast(50).reversed().map { it.episodeId })
                .inOrder()
            assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("50 new episodes")
            assertThat(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() })
                .containsExactlyElementsIn(
                    episodes.takeLast(6).reversed().map { "${it.podcastTitle} — ${it.episodeTitle}" },
                ).inOrder()
            assertThat(notification.extras.getString(Notification.EXTRA_SUB_TEXT)).isEqualTo("Other podcasts")
        }

    @Test
    fun dismissedNotificationsAreNotResurrected() =
        runBlocking<Unit> {
            val first = line()
            poster.post(listOf(notice(first)))
            manager.cancel(DEFAULT, NOTIFICATION_ID)
            val second = line()
            poster.post(listOf(notice(second)))

            assertThat(notification(DEFAULT).extras.getLongArray(IDS)!!.toList()).containsExactly(second.episodeId)
        }

    @Test
    fun removalUpdatesSilentlyThenCancelsAndLeavesOtherChannelsAlone() =
        runBlocking<Unit> {
            val a = line()
            val b = line()
            val c = line()
            poster.post(listOf(notice(a, b), notice(c, uuid = "tech", name = "Tech")))
            val originalWhen = notification(DEFAULT).`when`
            poster = AndroidNewEpisodePoster(application, db, clock)
            clock.nowMs += 1_000
            poster.removePodcasts(listOf(a.podcastId))

            assertThat(notification(DEFAULT).extras.getLongArray(IDS)!!.toList()).containsExactly(b.episodeId)
            assertThat(notification(DEFAULT).extras.getString(Notification.EXTRA_TITLE)).isEqualTo(b.podcastTitle)
            assertThat(notification(DEFAULT).flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
            assertThat(notification(DEFAULT).`when`).isEqualTo(originalWhen)
            assertThat(intent(DEFAULT).dataString).isEqualTo("neutrodyne://open/episode/${b.episodeId}")
            assertThat(notification(GROUP).extras.getLongArray(IDS)!!.toList()).containsExactly(c.episodeId)

            poster.removePodcasts(listOf(b.podcastId))
            assertThat(manager.activeNotifications.map { it.tag }).containsExactly(GROUP)
        }

    @Test
    fun removalCleansUpAfterCascadeAndCancelsWhenPermissionRevoked() =
        runBlocking<Unit> {
            val a = line()
            val b = line()
            poster.post(listOf(notice(a, b)))
            db.podcastDao().deleteCascade(a.podcastId, clock.now())
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            poster.removePodcasts(listOf(a.podcastId))

            assertThat(shadowManager.size()).isEqualTo(0)
        }

    @Test
    fun tapsUseExplicitImmutableEpisodeGroupAndLauncherIntents() =
        runBlocking<Unit> {
            val a = line()
            poster.post(listOf(notice(a)))
            assertThat(intent(DEFAULT).dataString).isEqualTo("neutrodyne://open/episode/${a.episodeId}")
            assertThat(intent(DEFAULT).component!!.className).isEqualTo(MainActivity::class.java.name)
            assertThat(intent(DEFAULT).component!!.packageName).isEqualTo(application.packageName)
            assertThat(notification(DEFAULT).contentIntent.isImmutable).isTrue()

            poster.post(listOf(notice(line(), line(), uuid = "tech", name = "Tech")))
            assertThat(intent(GROUP).dataString).isEqualTo("neutrodyne://open/group/tech")
            assertThat(notification(GROUP).extras.getString(Notification.EXTRA_SUB_TEXT)).isEqualTo("Tech")

            poster.post(listOf(notice(line())))
            assertThat(intent(DEFAULT).action).isEqualTo(Intent.ACTION_MAIN)
            assertThat(intent(DEFAULT).categories).contains(Intent.CATEGORY_LAUNCHER)
            assertThat(intent(DEFAULT).data).isNull()
        }

    @Test
    fun pendingIntentsStayDistinctWhenChannelHashesCollide() =
        runBlocking<Unit> {
            val a = line()
            poster.post(listOf(notice(a, uuid = "Aa", name = "A"), notice(a, uuid = "BB", name = "B")))

            assertThat("new_episodes_Aa".hashCode()).isEqualTo("new_episodes_BB".hashCode())
            assertThat(
                notification("new_episodes_Aa").contentIntent,
            ).isNotEqualTo(notification("new_episodes_BB").contentIntent)
        }

    @Test
    fun resolvesGermanNotificationTextOutsideComposition() =
        runBlocking<Unit> {
            Locale.setDefault(Locale.GERMAN)
            poster.post(listOf(notice(line(), line())))

            assertThat(notification(DEFAULT).extras.getString(Notification.EXTRA_TITLE)).isEqualTo("2 neue Episoden")
            assertThat(notification(DEFAULT).extras.getString(Notification.EXTRA_SUB_TEXT)).isEqualTo("Andere Podcasts")
        }

    private suspend fun line(sortDate: Long? = null): NewEpisodeLine {
        val podcast = db.podcastDao().insertPodcast(podcastEntity(title = "Podcast"))
        val episode =
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(
                            podcastId = podcast,
                            sortDate =
                                sortDate ?: podcast,
                        ),
                    ),
                ).single()
        return NewEpisodeLine(episode, podcast, "Podcast", "Episode 0", null)
    }

    private fun notice(
        vararg episodes: NewEpisodeLine,
        uuid: String? = null,
        name: String? = null,
    ) = NewEpisodeNotice(uuid, name, episodes.toList())

    private fun notification(channel: String) = manager.activeNotifications.single { it.tag == channel }.notification

    private fun intent(channel: String) = shadowOf(notification(channel).contentIntent).savedIntent

    private companion object {
        const val DEFAULT = "new_episodes"
        const val GROUP = "new_episodes_tech"
        const val CHANNEL_GROUP = "grp_new_episodes"
        const val IDS = "nd.episode_ids"
        const val NOTIFICATION_ID = 5000
    }
}

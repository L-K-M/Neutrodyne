// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.CHANNEL_GROUP
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.DEFAULT_CHANNEL
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.GROUP_CHANNEL_PREFIX
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.NOTIFICATION_ID
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidGroupChannelSyncTest {
    private val application = RuntimeEnvironment.getApplication()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val shadowManager = shadowOf(manager)
    private var pending = emptySet<String>()
    private val groups =
        object : GroupRepository by FakeGroupRepository() {
            override suspend fun pendingUndoUuids(): Set<String> = pending
        }
    private lateinit var db: NeutrodyneDatabase
    private lateinit var channels: AndroidGroupChannelSync

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
        db = TestDb.inMemory(application)
        channels = AndroidGroupChannelSync(application, db, { groups }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun ownTrueCreatesAChannelForAnEmptyGroup() =
        runBlocking {
            val id = group(notify = null)
            channels.sync()
            assertThat(channel()).isNull()

            setNotify(id, true)
            channels.sync()

            assertThat(channel().name.toString()).isEqualTo("Tech")
            assertThat(channel().importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
            assertThat(channel().group).isEqualTo(CHANNEL_GROUP)
            assertThat(manager.getNotificationChannelGroup(CHANNEL_GROUP)).isNotNull()
        }

    @Test
    fun renamePreservesEveryChannelSettingAndUpdatesTheName() =
        runBlocking {
            val id = group(notify = true)
            NewEpisodeChannels.ensureChannels(application)
            manager.createNotificationChannel(customizedChannel(GROUP))
            db.groupDao().rename(id, "Science", "science", 1L)
            val before = channel()

            channels.sync()

            assertThat(channel().name.toString()).isEqualTo("Science")
            assertSettings(channel(), before)
        }

    @Test
    fun renamingAnInheritingOrDisabledGroupDoesNotCreateAChannel() =
        runBlocking {
            val id = group(notify = null)
            db.groupDao().rename(id, "Science", "science", 1L)
            channels.sync()
            assertThat(channel()).isNull()

            setNotify(id, false)
            channels.sync()
            assertThat(channel()).isNull()
        }

    @Test
    fun falseAndInheritDeleteTheChannelAndCancelItsNotification() =
        runBlocking {
            val id = group(notify = true)
            channels.sync()
            post(GROUP)
            assertThat(shadowManager.size()).isEqualTo(1)

            setNotify(id, false)
            channels.sync()
            assertThat(channel()).isNull()
            assertThat(shadowManager.size()).isEqualTo(0)

            setNotify(id, true)
            channels.sync()
            assertThat(channel()).isNotNull()
            setNotify(id, null)
            channels.sync()
            assertThat(channel()).isNull()
        }

    @Test
    fun sweepSparesLiveUndoTokensThenDeletesAfterExpiry() =
        runBlocking {
            val id = group(notify = true)
            channels.sync()
            post(GROUP)
            db.groupDao().deleteGroup(id)
            pending = setOf(UUID)

            channels.sync()
            assertThat(channel()).isNotNull()
            assertThat(shadowManager.size()).isEqualTo(1)

            pending = emptySet()
            channels.sync()
            assertThat(channel()).isNull()
            assertThat(shadowManager.size()).isEqualTo(0)
        }

    @Test
    fun undoRestoresTheGroupWithoutChangingTheChannel() =
        runBlocking {
            val row = groupEntity(uuid = UUID, name = "Tech", orderKey = "a")
            val id = db.groupDao().insert(row)
            setNotify(id, true)
            channels.sync()
            val before = channel()
            db.groupDao().deleteGroup(id)
            pending = setOf(UUID)
            channels.sync()

            db.groupDao().insert(row.copy(id = id))
            setNotify(id, true)
            pending = emptySet()
            channels.sync()
            assertSettings(channel(), before)
        }

    @Test
    fun startupSweepCoversProcessDeathAndLeavesOtherChannelsAlone() =
        runBlocking {
            NewEpisodeChannels.ensureChannels(application)
            manager.createNotificationChannel(customizedChannel(GROUP))
            manager.createNotificationChannel(customizedChannel("playback"))
            val default = manager.getNotificationChannel(DEFAULT_CHANNEL)
            val other = manager.getNotificationChannel("playback")

            val initializer = GroupChannelsInitializer { channels }
            assertThat(initializer.order).isEqualTo(140)
            initializer.run()

            assertThat(channel()).isNull()
            assertSettings(manager.getNotificationChannel(DEFAULT_CHANNEL), default)
            assertSettings(manager.getNotificationChannel("playback"), other)
        }

    @Test
    fun uuidChangeCarriesSettingsAndCancelsTheOldNotification() =
        runBlocking {
            NewEpisodeChannels.ensureChannels(application)
            manager.createNotificationChannel(customizedChannel(GROUP))
            post(GROUP)
            val old = channel()
            group(uuid = "new", notify = true)

            channels.onUuidChanged(UUID, "new")

            assertThat(channel()).isNull()
            val transferred = manager.getNotificationChannel(GROUP_CHANNEL_PREFIX + "new")
            assertThat(transferred.name.toString()).isEqualTo("Tech")
            assertThat(transferred.group).isEqualTo(CHANNEL_GROUP)
            assertSettings(transferred, old)
            assertThat(shadowManager.size()).isEqualTo(0)
            assertThat(manager.getNotificationChannel(DEFAULT_CHANNEL)).isNotNull()
        }

    @Test
    fun uuidChangeWithoutAnOldChannelUsesDefaultsOnlyWhenEnabled() =
        runBlocking {
            val id = group(uuid = "new", notify = null)
            channels.onUuidChanged(UUID, "new")
            assertThat(manager.getNotificationChannel(GROUP_CHANNEL_PREFIX + "new")).isNull()

            setNotify(id, true)
            channels.onUuidChanged(UUID, "new")
            assertThat(manager.getNotificationChannel(GROUP_CHANNEL_PREFIX + "new").importance)
                .isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        }

    @Test
    fun sweepPreservesConfiguredDefaultChannelSettings() =
        runBlocking {
            NewEpisodeChannels.ensureChannels(application)
            val default = manager.getNotificationChannel(DEFAULT_CHANNEL)
            default.importance = NotificationManager.IMPORTANCE_LOW
            default.setSound(Uri.parse("content://test/default"), default.audioAttributes)
            default.enableVibration(true)
            default.setShowBadge(false)
            manager.createNotificationChannel(default)
            val before = manager.getNotificationChannel(DEFAULT_CHANNEL)

            channels.sync()
            channels.sync()

            assertSettings(manager.getNotificationChannel(DEFAULT_CHANNEL), before)
        }

    @Test
    fun uuidChangeForADisabledGroupDeletesOnlyTheOldChannel() =
        runBlocking {
            NewEpisodeChannels.ensureChannels(application)
            manager.createNotificationChannel(customizedChannel(GROUP))
            group(uuid = "new", notify = false)

            channels.onUuidChanged(UUID, "new")

            assertThat(channel()).isNull()
            assertThat(manager.getNotificationChannel(GROUP_CHANNEL_PREFIX + "new")).isNull()
            assertThat(manager.getNotificationChannel(DEFAULT_CHANNEL)).isNotNull()
        }

    @Test
    fun sameUuidDoesNotDeleteTheChannel() =
        runBlocking {
            group(notify = true)
            channels.sync()
            channels.onUuidChanged(UUID, UUID)
            assertThat(channel()).isNotNull()
        }

    @Test
    fun sweepIsIdempotentAndExpiryCallbackReconciles() =
        runBlocking {
            val id = group(notify = true)
            channels.sync()
            val before = manager.notificationChannels
            channels.sync()
            assertThat(manager.notificationChannels).containsExactlyElementsIn(before)

            db.groupDao().deleteGroup(id)
            channels.onDeleted(UUID)
            channels.onDeleted(UUID)
            assertThat(channel()).isNull()
            assertThat(manager.getNotificationChannel(DEFAULT_CHANNEL)).isNotNull()
        }

    @Test
    fun initializersResolveThePortOnlyWhenRun() =
        runBlocking {
            val port = FakeGroupChannelSync()
            var resolutions = 0
            val initializer =
                GroupChannelsInitializer {
                    resolutions++
                    port
                }
            assertThat(resolutions).isEqualTo(0)
            initializer.run()
            assertThat(resolutions).isEqualTo(1)
            assertThat(port.calls).containsExactly("sync()")

            val static = NewEpisodeChannelsInitializer(application)
            assertThat(static.order).isEqualTo(10)
            static.run()
            assertThat(manager.getNotificationChannel(DEFAULT_CHANNEL)).isNotNull()
        }

    private suspend fun group(
        uuid: String = UUID,
        notify: Boolean?,
    ): Long {
        val id = db.groupDao().insert(groupEntity(uuid = uuid, name = "Tech", orderKey = "a"))
        setNotify(id, notify)
        return id
    }

    private suspend fun setNotify(
        id: Long,
        notify: Boolean?,
    ) = db.scopeSettingsDao().upsertGroup(PodcastGroupSettingsEntity(id, ScopeOverrides(notifyNewEpisodes = notify)))

    private fun channel() = manager.getNotificationChannel(GROUP)

    private fun post(id: String) {
        manager.notify(
            id,
            NOTIFICATION_ID,
            Notification.Builder(application, id).setSmallIcon(R.drawable.ic_stat_neutrodyne).build(),
        )
    }

    private fun customizedChannel(id: String) =
        NotificationChannel(id, "Old", NotificationManager.IMPORTANCE_HIGH).apply {
            group = CHANNEL_GROUP
            setSound(
                Uri.parse("content://test/sound"),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            lightColor = Color.GREEN
            enableLights(true)
            vibrationPattern = longArrayOf(0, 80, 40, 80)
            enableVibration(true)
            setShowBadge(false)
        }

    private fun assertSettings(
        actual: NotificationChannel,
        expected: NotificationChannel,
    ) {
        assertThat(actual.importance).isEqualTo(expected.importance)
        assertThat(actual.sound).isEqualTo(expected.sound)
        assertThat(actual.audioAttributes).isEqualTo(expected.audioAttributes)
        assertThat(actual.shouldShowLights()).isEqualTo(expected.shouldShowLights())
        assertThat(actual.lightColor).isEqualTo(expected.lightColor)
        assertThat(actual.shouldVibrate()).isEqualTo(expected.shouldVibrate())
        assertThat(actual.vibrationPattern).isEqualTo(expected.vibrationPattern)
        assertThat(actual.canShowBadge()).isEqualTo(expected.canShowBadge())
    }

    private companion object {
        const val UUID = "tech"
        const val GROUP = GROUP_CHANNEL_PREFIX + UUID
    }
}

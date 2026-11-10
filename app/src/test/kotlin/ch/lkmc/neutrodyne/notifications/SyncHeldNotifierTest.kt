// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.lkmc.neutrodyne.INTERNAL_MAIN_ACTIVITY
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 10 Scheduling on Android / 08 Sync notices: a HELD `sync_held` batch surfaces once while the
 * link lives — not before the database's controller exists, not after it, not while the user
 * blocked the channel or the permission is denied.
 *
 * The collector runs on a real single thread (like production's IO dispatcher): the localized
 * text goes through the compose-resources async cache on `Dispatchers.Default`, so a virtual
 * test scheduler could never pump it deterministically. Assertions wait on the shade with a
 * deadline, never a fixed sleep, except where "nothing happens" is the claim — there a settle
 * window is the assertion itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalResourceApi::class)
class SyncHeldNotifierTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val controller = FakeSyncController()
    private var executor = Executors.newSingleThreadExecutor()
    private var io: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private var scope = CoroutineScope(SupervisorJob() + io)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        setResourceReaderAndroidContext(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() {
        scope.cancel()
        io.close()
        executor.shutdown()
    }

    @Test
    fun linkedHeldBatchPostsOnceToTheSyncChannel() =
        runBlocking {
            notifier().run()
            controller.status.value = linked()
            controller.heldChanges.value = listOf(prompt(7))
            awaitShade(1)

            val posted = manager.activeNotifications.single()
            assertEquals(SyncHeldChannels.ID, posted.id)
            val notification = posted.notification
            assertEquals("sync_held", notification.channelId)
            assertEquals("Sync is waiting for you", notification.extras.getString(Notification.EXTRA_TITLE))
            val text = notification.extras.getString(Notification.EXTRA_TEXT)!!
            // The batch summary the banner carries (08): device and counts, never a URL or token.
            assertEquals("Pixel removed 1 podcast and 1 group. Apply here too?", text)
            assertFalse(text.contains("sync.example"))
            assertFalse(text.contains("tok"))
            assertTrue(notification.contentIntent.isImmutable)
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(INTERNAL_MAIN_ACTIVITY, intent.component!!.className)
            assertEquals(app.packageName, intent.component!!.packageName)
            assertEquals(
                Route.Push(SyncSettingsKey),
                IntentRouter.route(RouteInput(RouteInput.Action.VIEW, uri = intent.dataString, internal = true)),
            )
        }

    @Test
    fun nothingPostsUntilLinkedWithHeldRows() =
        runBlocking {
            notifier().run()

            for (
            status in
            listOf(
                SyncStatus.NotConfigured,
                SyncStatus.Reconnect("https://sync.example", "user"),
                SyncStatus.Revoked("https://sync.example"),
                SyncStatus.LinkUnfinished("https://sync.example"),
            )
            ) {
                controller.status.value = status
                controller.heldChanges.value = listOf(prompt(7))
                settle()
                assertTrue(manager.activeNotifications.isEmpty(), "posted under $status")
            }

            controller.status.value = linked()
            controller.heldChanges.value = emptyList()
            settle()
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test
    fun resolvingOrLeavingTheLinkCancelsThePrompt() =
        runBlocking {
            notifier().run()
            controller.status.value = linked()

            controller.heldChanges.value = listOf(prompt(7))
            awaitShade(1)

            // Resolving the batch clears the notice.
            controller.heldChanges.value = emptyList()
            awaitShade(0)

            controller.heldChanges.value = listOf(prompt(8))
            awaitShade(1)

            // Unlinking (status leaves Linked) clears it too.
            controller.status.value = SyncStatus.NotConfigured
            awaitShade(0)
        }

    @Test
    fun sameBatchStaysDownAfterDismissalWhileNewBatchPosts() =
        runBlocking {
            notifier().run()
            controller.status.value = linked()
            controller.heldChanges.value = listOf(prompt(7))
            awaitShade(1)

            // The user swipes it away; unrelated status churn must not resurrect it.
            manager.cancel(SyncHeldChannels.ID)
            controller.status.value = linked(lastSyncAtMs = 1_000, activity = SyncActivity.SYNCING)
            controller.status.value = linked(lastSyncAtMs = 2_000)
            settle()
            assertTrue(manager.activeNotifications.isEmpty())

            controller.heldChanges.value = listOf(prompt(9))
            awaitShade(1)
        }

    @Test
    fun aDifferentProfileWithTheSameBatchIdIsNotDeduped() =
        runBlocking {
            notifier().run()
            controller.status.value = linked()
            controller.heldChanges.value = listOf(prompt(7))
            awaitShade(1)

            // Dismissed, then the same batch id arrives under another account/device — it must
            // still surface (the dedupe key carries the profile, not just the row id).
            manager.cancel(SyncHeldChannels.ID)
            controller.status.value = linked(accountId = "acct-2", deviceId = "dev-2")
            awaitShade(1)
        }

    @Test
    fun deniedPermissionAndBlockedChannelNeverDeliver() =
        runBlocking {
            notifier().run()
            controller.status.value = linked()

            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            controller.heldChanges.value = listOf(prompt(7))
            settle()
            assertTrue(manager.activeNotifications.isEmpty())

            // A refused post was never delivered, so the next real batch still surfaces once
            // the permission exists.
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            controller.heldChanges.value = listOf(prompt(8, podcasts = listOf("A", "B")))
            awaitShade(1)
            assertEquals(
                "Pixel removed 2 podcasts and 1 group. Apply here too?",
                manager.activeNotifications
                    .single()
                    .notification.extras
                    .getString(Notification.EXTRA_TEXT),
            )

            manager.createNotificationChannel(
                NotificationChannel("sync_held", "Blocked", NotificationManager.IMPORTANCE_NONE),
            )
            controller.heldChanges.value = listOf(prompt(9, podcasts = listOf("A", "B", "C")))
            settle()
            // The blocked channel keeps the batch-8 text — batch 9 never reached the shade.
            assertEquals(
                "Pixel removed 2 podcasts and 1 group. Apply here too?",
                manager.activeNotifications
                    .single()
                    .notification.extras
                    .getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun stateThatMovedOnBeforeCollectionPostsNothing() =
        runBlocking {
            // Park the collector thread, then move the whole story past its first
            // observation: held under one link, unlinked, held under another.
            val blocker = CountDownLatch(1)
            executor.execute { blocker.await() }
            notifier().run()
            controller.status.value = linked()
            controller.heldChanges.value = listOf(prompt(7))
            controller.status.value = SyncStatus.NotConfigured
            controller.status.value = linked(serverUrl = "https://other.example", accountId = "b", deviceId = "d2")
            controller.heldChanges.value = listOf(prompt(8, podcasts = listOf("A", "B")))
            blocker.countDown()

            // Only the current state's post may exist: batch 7's stale work under the old
            // profile can neither land nor clear batch 8's notice.
            awaitShade(1)
            assertEquals(
                "Pixel removed 2 podcasts and 1 group. Apply here too?",
                manager.activeNotifications
                    .single()
                    .notification.extras
                    .getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun aCancelledAppScopeCannotPost() =
        runBlocking {
            val blocker = CountDownLatch(1)
            executor.execute { blocker.await() }
            notifier().run()
            controller.status.value = linked()
            controller.heldChanges.value = listOf(prompt(7))
            // The app lifetime ends while the post is still queued behind the blocker.
            scope.cancel()
            blocker.countDown()
            settle()
            assertTrue(manager.activeNotifications.isEmpty())
        }

    // --- plumbing ----------------------------------------------------------------------------

    private fun notifier() = SyncHeldNotifier(app, { controller }, scope, io)

    private fun linked(
        serverUrl: String = "https://sync.example",
        accountId: String = "acct",
        deviceId: String = "dev",
        lastSyncAtMs: Long? = null,
        activity: SyncActivity = SyncActivity.IDLE,
    ) = SyncStatus.Linked(
        serverUrl = serverUrl,
        accountId = accountId,
        deviceId = deviceId,
        username = "user",
        nodeId = "0000000000000001",
        lastSyncAtMs = lastSyncAtMs,
        activity = activity,
        problem = null,
    )

    private fun prompt(
        id: Long,
        device: String? = "Pixel",
        podcasts: List<String> = listOf("Show A"),
        groups: List<String> = listOf("Team"),
    ) = MassChangePrompt(id, device, podcasts, groups)

    /** Waits until the shade shows exactly [count] notifications — the deadline is a hang detector. */
    private fun awaitShade(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.activeNotifications.size != count) {
            check(System.nanoTime() < deadline) { "shade never reached $count notifications" }
            Thread.sleep(10)
        }
    }

    /**
     * For "must not happen" claims: lets any in-flight post land or die. A wrong implementation
     * posts within milliseconds; two hundred is generous headroom, not a race.
     */
    private fun settle() = Thread.sleep(200)
}

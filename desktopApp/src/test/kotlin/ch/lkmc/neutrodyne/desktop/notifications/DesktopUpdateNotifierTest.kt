// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.data.update.UpdatePostResult
import ch.lkmc.neutrodyne.core.testing.updateContractInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class DesktopUpdateNotifierTest {
    private val originalLocale = Locale.getDefault()

    @Before fun english() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test fun dispatchAndCancelUseTheSameStableAppUpdateIdAndRoute() =
        runBlocking {
            val posted = mutableListOf<DesktopNotification>()
            val cancelled = mutableListOf<String>()
            val notifier =
                object : DesktopNotifier {
                    override suspend fun post(n: DesktopNotification) {
                        posted += n
                    }

                    override fun cancel(id: String) {
                        cancelled += id
                    }
                }
            val adapter = DesktopUpdateNotifier(notifier)
            assertEquals(UpdatePostResult.ACCEPTED, adapter.post(updateContractInfo().copy(notes = "First line\nRest")))
            val item = posted.single()
            assertEquals(NotificationKind.APP_UPDATE, item.kind)
            assertEquals("app-update", item.id)
            assertEquals("Neutrodyne 1.0.0 is available", item.title)
            assertEquals("First line", item.body)
            assertEquals("neutrodyne://open/settings/updates", item.route)
            adapter.cancel()
            assertEquals(listOf(item.id), cancelled)
        }
}

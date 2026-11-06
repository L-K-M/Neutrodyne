// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import ch.lkmc.neutrodyne.sync.impl.SyncControllerImpl
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 10 Settings capture in the real Android graph: the controller installs the production recorder
 * while the link is committed and "Sync playback settings" is on, so a local synced-key edit
 * through the production `SettingsRepository` records the literal `v` outbox intent, and a remote
 * apply through the production port does not echo one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsCaptureGraphTest {
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(RuntimeEnvironment.getApplication())

    @Test
    fun aLinkedLocalEditCapturesAndARemoteApplyDoesNotEcho() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                val db = graph.databaseOpener.requireDatabase()
                val controller = graph.syncController as SyncControllerImpl

                // Unlinked: no recorder, so a local edit records nothing (R7.1).
                graph.settingsRepository.set(PlaybackSettingKeys.SPEED, 1.0f)
                assertThat(settingRows(db)).isEmpty()

                // Commit the link, then let the production controller install the recorder.
                graph.settingsRepository.set(SyncSettingKeys.SERVER_URL, "https://sync.example")
                db.withWriteTransaction {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            enabled = true,
                            nodeId = "0000000000000001",
                            hlc = 1L shl 16,
                            linkedAt = 1L,
                            accountId = "acc-1",
                        ),
                    )
                }
                controller.refreshStatus()

                graph.settingsRepository.set(PlaybackSettingKeys.SPEED, 1.5f)
                val linked = settingRows(db)
                assertThat(linked).hasSize(1)
                assertThat(linked.single().rid).isEqualTo(PlaybackSettingKeys.SPEED.name)
                assertThat(linked.single().field).isEqualTo("v")
                assertThat(linked.single().value).isEqualTo("1.5")

                // A remote apply writes DataStore through the production port and records no
                // local intent: the pending row keeps the local value.
                assertThat(graph.settingsSyncPort.applyRemote(PlaybackSettingKeys.SPEED, "1.2")).isTrue()
                assertEquals(1.2f, graph.settingsRepository.get(PlaybackSettingKeys.SPEED), 0.0001f)
                assertThat(settingRows(db).single().value).isEqualTo("1.5")

                // The switch off stops the capture; on resumes it.
                graph.syncController.setSyncPlaybackSettings(false)
                graph.settingsRepository.set(PlaybackSettingKeys.SPEED, 1.3f)
                assertThat(settingRows(db).single().value).isEqualTo("1.5")
                graph.syncController.setSyncPlaybackSettings(true)
                graph.settingsRepository.set(PlaybackSettingKeys.SPEED, 1.4f)
                assertThat(settingRows(db).single().value).isEqualTo("1.4")
            } finally {
                graph.databaseOpener.requireDatabase().close()
                graph.appScope.cancel()
            }
        }

    private suspend fun settingRows(db: NeutrodyneDatabase): List<SyncOutboxEntity> =
        db.syncOutboxDao().rows().filter { it.coll == "setting" }
}

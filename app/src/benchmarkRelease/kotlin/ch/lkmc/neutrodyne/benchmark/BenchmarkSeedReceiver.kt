// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.testing.database.SeedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Explicit shell-only fixture entry point; absent from every published build type. */
class BenchmarkSeedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val graph = (context.applicationContext as NeutrodyneApplication).graph
        // Keep large fixture writes off the main thread while the ordered broadcast waits.
        val pending = goAsync()
        graph.appScope.launch(Dispatchers.IO) {
            try {
                graph.databaseOpener.awaitOpen()
                val db = graph.databaseOpener.requireDatabase()
                val marker = context.filesDir.resolve("benchmark-seeded-v1")
                if (marker.isFile) {
                    pending.resultData = marker.name
                    pending.resultCode = 0
                    return@launch
                }
                check(db.podcastDao().allForExport().isEmpty()) { "Benchmark seeding requires an empty installation" }
                val seeded = SeedDatabase().install(db)
                db.useWriterConnection { connection ->
                    connection.usePrepared("UPDATE episode SET imageUrl = NULL, artworkKey = NULL") { it.step() }
                }
                val artworkDir = context.filesDir.resolve("artwork").apply { mkdirs() }
                for (podcast in seeded.podcasts) {
                    val key = "u-" + podcast.id.toString(16).padStart(40, '0')
                    val hue = (podcast.id * 37 % 360).toFloat()
                    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, .7f, .8f))
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try {
                        val paint =
                            Paint().apply {
                                shader =
                                    LinearGradient(
                                        0f,
                                        0f,
                                        512f,
                                        512f,
                                        color,
                                        android.graphics.Color.WHITE,
                                        Shader.TileMode.CLAMP,
                                    )
                            }
                        Canvas(bitmap).drawRect(0f, 0f, 512f, 512f, paint)
                        artworkDir.resolve("$key.jpg").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    db.useWriterConnection { connection ->
                        connection.usePrepared("UPDATE podcast SET artworkKey = ?, artworkUrl = NULL WHERE id = ?") {
                            it.bindText(1, key)
                            it.bindLong(2, podcast.id)
                            it.step()
                        }
                    }
                    db.artworkDao().upsert(
                        ArtworkEntity(
                            key,
                            localPath = "$key.jpg",
                            width = 512,
                            height = 512,
                            seedArgb = color,
                            avgArgb = color,
                            version = 1,
                            fetchedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // A durable paused item exercises the UI without starting audio or opening a socket.
                val firstEpisode = seeded.episodes.first().id
                db.playSessionDao().write(PlaySessionEntity(currentEpisodeId = firstEpisode, updatedAt = 0))
                check(graph.settingsRepository.set(UpdateSettingKeys.CHECK_ENABLED, false) is Outcome.Success)
                check(graph.settingsRepository.set(UpdateSettingKeys.FIRST_RUN_CHOICE_DONE, true) is Outcome.Success)
                check(graph.settingsRepository.set(AppearanceSettingKeys.DYNAMIC_COLOR, false) is Outcome.Success)
                check(graph.settingsRepository.set(AppearanceSettingKeys.THEME, ThemeMode.LIGHT) is Outcome.Success)
                marker.writeText("300 podcasts; 50000 episodes; 20 groups\n")
                pending.resultData = marker.name
                pending.resultCode = 0
            } catch (error: Exception) {
                pending.resultCode = 1
                pending.resultData = "Benchmark seeding failed: ${error.message}"
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION = "ch.lkmc.neutrodyne.BENCHMARK_SEED"
    }
}

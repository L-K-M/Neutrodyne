// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import ch.lkmc.neutrodyne.youtube.engine.EngineStorePaths
import okio.Path.Companion.toPath
import okio.Source
import okio.source

/** App-process port; :ytx receives a prepared lib directory, never this asset or active.json. */
class AndroidEngineStorePaths(
    context: Context,
) : EngineStorePaths {
    private val application = context.applicationContext
    override val root =
        application.noBackupFilesDir
            .canonicalFile
            .resolve("ytdlp")
            .path
            .toPath()
    override val ytDlpCacheDir =
        application.cacheDir
            .canonicalFile
            .resolve("yt-dlp")
            .path
            .toPath()
    override val pythonMinor = "3.14"

    override fun openBundledZip(): Source = application.assets.open("ytdlp/yt-dlp").source()

    override fun bundledJson(): String =
        application.assets.open("ytdlp/bundled.json").bufferedReader().use { reader ->
            val text = CharArray(MAX_METADATA_CHARS + 1)
            val count = reader.read(text)
            require(count in 1..MAX_METADATA_CHARS) { "Invalid bundled metadata" }
            String(text, 0, count)
        }

    private companion object {
        const val MAX_METADATA_CHARS = 4096
    }
}

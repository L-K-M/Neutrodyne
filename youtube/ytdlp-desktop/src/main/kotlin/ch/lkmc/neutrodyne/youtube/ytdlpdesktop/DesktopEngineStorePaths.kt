// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.youtube.engine.EngineStorePaths
import okio.Path.Companion.toPath
import okio.Source
import okio.source
import java.io.File

/**
 * The desktop engine store layout (11 "Engine store paths"): `versions/` and `active.json`
 * under the app data dir, the yt-dlp cache and the child's sandboxed HOME/TMP under the app
 * cache dir. Bundled inputs (the yt-dlp zip, its manifest metadata, the signing keys and the
 * shim sources) come from [EngineResources] — packaged `common/engine/` resources in a shipped
 * image, `build/engine-resources` on a dev machine.
 */
class DesktopEngineStorePaths(
    dirs: AppDirs,
    private val resources: () -> File?,
    private val runtime: () -> PythonRuntime?,
) : EngineStorePaths {
    private val resourcesDir = dirs.data.resolve("ytdlp").toFile()
    private val cacheDir = dirs.cache.resolve("engine-cache").toFile()

    override val root = resourcesDir.canonicalPath.toPath()
    override val ytDlpCacheDir = cacheDir.resolve("yt-dlp").canonicalPath.toPath()

    /** The child's entire HOME/TMP, cleared by the environment builder (11 "Engine security"). */
    val childHome: File get() = cacheDir.resolve("home")
    val childTmp: File get() = cacheDir.resolve("tmp")

    /** Marker suffix for `.compiled-<minor>`; probed from the runtime, else the shipped target. */
    override val pythonMinor: String
        get() = runtime()?.pythonMinor ?: FALLBACK_MINOR

    /** `<resources>/shim/neutrodyne_ytx`, or null when engine resources are absent. */
    fun shimPackageDir(): File? = resources()?.resolve("shim/neutrodyne_ytx")?.takeIf(File::isDirectory)

    /** `<resources>/cacert.pem` — the packaged CA bundle; absent until the PBS slice ships it. */
    fun cacertFile(): File? = resources()?.resolve("cacert.pem")?.takeIf(File::isFile)

    override fun openBundledZip(): Source = requireResource("ytdlp/yt-dlp").source()

    override fun bundledJson(): String =
        requireResource("ytdlp/bundled.json").bufferedReader().use { reader ->
            val text = CharArray(MAX_METADATA_CHARS + 1)
            val count = reader.read(text)
            require(count in 1..MAX_METADATA_CHARS) { "Invalid bundled metadata" }
            String(text, 0, count)
        }

    override fun manifestKeysText(): String = requireResource("keys/engine-manifest-ed25519.pub").readText()

    override fun upstreamKeyText(): String = requireResource("keys/yt-dlp-release-key.asc").readText()

    private fun requireResource(name: String): File =
        File(
            resources() ?: error("Engine resources are not configured (dev: run syncEngineResources)"),
            name,
        )

    private companion object {
        const val MAX_METADATA_CHARS = 4096

        /** The packaged interpreter's minor; a system probe overrides it through [runtime]. */
        const val FALLBACK_MINOR = "3.14"
    }
}

/**
 * Where the engine's bundled inputs live this launch (11 "Python runtime"): the packaged
 * resource directory Compose merges (`<resources>/engine/`, from `common/engine/` and
 * `<os>-<arch>/engine/`), the dev build's `syncEngineResources` output, or an explicit
 * `neutrodyne.engineResources` override for tests.
 */
object EngineResources {
    fun standard(
        rootDir: () -> File? = { System.getProperty("neutrodyne.rootDir")?.let(::File) },
        packagedResources: () -> File? = {
            System.getProperty("compose.application.resources.dir")?.let(::File)
        },
    ): () -> File? =
        {
            System.getProperty("neutrodyne.engineResources")?.let(::File)?.takeIf(File::isDirectory)
                ?: packagedResources()?.resolve("engine")?.takeIf(File::isDirectory)
                ?: rootDir()
                    ?.resolve("youtube/ytdlp-desktop/build/engine-resources")
                    ?.takeIf(File::isDirectory)
        }
}

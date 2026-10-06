// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.Toolkit
import java.io.File
import kotlin.math.abs

/**
 * The committed brand PNGs of `desktopApp/icons/` (never regenerated; 08 Brand assets). A dev
 * run (`:desktopApp:run`, working directory = the project) reads them from `icons/`; a packaged
 * image reads the copies the build merges into its resources directory
 * (`compose.application.resources.dir`, 11 Resources layout). A missing image disables just the
 * window icon or the tray (logged) — never the start.
 */
internal object WindowIcons {
    private const val TAG = "Window"

    /** Where the Compose launcher points at the merged resources of a packaged image. */
    private const val RESOURCES_PROPERTY = "compose.application.resources.dir"

    private const val DEV_ICONS_DIR = "icons"
    private const val WINDOW_ICON = "png/neutrodyne-256.png"

    /** macOS draws the monochrome template image (11 Tray icon row). */
    private const val TRAY_TEMPLATE = "tray/neutrodyne-template.png"
    private const val TRAY_PREFIX = "tray/neutrodyne-tray-"
    private const val TRAY_SUFFIX = ".png"

    /** 11's tray sizes; the one closest to the system's tray icon size is used. */
    private val TRAY_SIZES_PX = listOf(16, 22, 32)
    private const val FALLBACK_TRAY_SIZE_PX = 22

    fun windowIconPainter(): Painter? = painter(WINDOW_ICON)

    fun trayIconPainter(): Painter? =
        painter(if (AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS) TRAY_TEMPLATE else trayIconName())

    /** The primary screen once per start; null under headless AWT (smoke without a display). */
    fun primaryScreen(): Screen? =
        runCatching {
            val bounds =
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .bounds
            Screen(
                x = bounds.x,
                y = bounds.y,
                width = bounds.width,
                height = bounds.height,
                dpi = Toolkit.getDefaultToolkit().screenResolution,
            )
        }.getOrNull()

    private fun trayIconName(): String {
        if (!SystemTray.isSupported()) return "$TRAY_PREFIX$FALLBACK_TRAY_SIZE_PX$TRAY_SUFFIX"
        val size = SystemTray.getSystemTray().trayIconSize.width
        val nearest = TRAY_SIZES_PX.minBy { abs(it - size) }
        return "$TRAY_PREFIX$nearest$TRAY_SUFFIX"
    }

    private fun painter(relative: String): Painter? {
        val file = locate(relative)
        if (file == null) {
            Log.w(TAG) { "brand image $relative not found; continuing without it" }
            return null
        }
        return file
            .inputStream()
            .use { stream -> runCatching { BitmapPainter(loadImageBitmap(stream)) }.getOrNull() }
    }

    private fun locate(relative: String): File? {
        System.getProperty(RESOURCES_PROPERTY)?.let { root ->
            File(root, "$DEV_ICONS_DIR/$relative").takeIf(File::isFile)?.let { return it }
        }
        return File(DEV_ICONS_DIR, relative).takeIf(File::isFile)
    }
}

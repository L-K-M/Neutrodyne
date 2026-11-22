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

    /**
     * The tray icon as an AWT image — the Windows notification channel's fallback icon
     * (11 Notifications) picks the same PNG the window tray uses.
     */
    fun trayImage(): java.awt.Image? =
        locate(trayIconName())
            ?.takeIf(File::isFile)
            ?.let { file -> Toolkit.getDefaultToolkit().createImage(file.readBytes()) }

    /**
     * The primary screen once per start, in user-space units; null under headless AWT (smoke
     * without a display). `GraphicsConfiguration.bounds` is device space and the default
     * transform maps user space to device space, so the inverse transform gives the rectangle
     * Compose's density-independent window state expects. The physical DPI is deliberately not
     * used: on macOS it is not the display scale and applying it double-counts the transform.
     * The transform is assumed to carry the OS scale, which holds on Windows and macOS and on
     * most modern Linux JDKs; a noninvertible transform returns null rather than falling back
     * to device pixels, which would size the window larger than the display.
     */
    fun primaryScreen(): Screen? =
        runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val config = device.defaultConfiguration
            val userBounds =
                config.defaultTransform
                    .createInverse()
                    .createTransformedShape(config.bounds)
                    .bounds
            Screen(
                id = device.getIDstring(),
                x = userBounds.x,
                y = userBounds.y,
                width = userBounds.width,
                height = userBounds.height,
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

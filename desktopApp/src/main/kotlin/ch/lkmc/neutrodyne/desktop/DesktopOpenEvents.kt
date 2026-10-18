// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.Log
import java.awt.Desktop

/** Installs callbacks only; file and scheme registrations remain MD2. */
internal fun installDesktopOpenEvents(handler: DesktopOpenHandler): AutoCloseable {
    val desktop =
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()
            ?: return AutoCloseable { }
    val files = desktop.isSupported(Desktop.Action.APP_OPEN_FILE)
    val uris = desktop.isSupported(Desktop.Action.APP_OPEN_URI)
    try {
        if (files) desktop.setOpenFileHandler { event -> handler.submit(event.files.map { it.path }) }
        if (uris) desktop.setOpenURIHandler { event -> handler.submit(listOf(event.uri.toASCIIString())) }
    } catch (_: Exception) {
        Log.w("DesktopOpenEvents") { "OS open callbacks unavailable" }
    }
    return AutoCloseable {
        runCatching {
            if (files) desktop.setOpenFileHandler(null)
            if (uris) desktop.setOpenURIHandler(null)
        }.onFailure { Log.w("DesktopOpenEvents") { "OS open callbacks could not be removed" } }
    }
}

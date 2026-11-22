// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

/** The window's last rectangle as stored in `desktop.window_bounds` (11 Window state). */
@Serializable
internal data class StoredWindowBounds(
    val screen: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximised: Boolean = false,
)

/**
 * The window-bounds codec and restore rule (11 Window state): a stored rectangle is restored only
 * when its screen is present and at least half of it overlaps that screen's usable bounds. The
 * screen id is AWT's enumeration-order string, so a non-match falls back to the primary screen's
 * default placement rather than trusting a stale identity.
 */
internal object WindowBoundsPreference {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun encode(bounds: StoredWindowBounds): String = json.encodeToString(bounds)

    fun decode(text: String): StoredWindowBounds? {
        if (text.isBlank()) return null
        val stored = runCatching { json.decodeFromString<StoredWindowBounds>(text) }.getOrNull() ?: return null
        if (stored.screen.isBlank() || stored.width <= 0 || stored.height <= 0) return null
        return stored
    }

    fun restore(
        stored: StoredWindowBounds,
        screens: List<Screen>,
    ): WindowBounds? {
        val screen = screens.firstOrNull { it.id == stored.screen } ?: return null
        val overlapWidth =
            min(screen.usableX + screen.usableWidth, stored.x + stored.width) -
                max(screen.usableX, stored.x)
        val overlapHeight =
            min(screen.usableY + screen.usableHeight, stored.y + stored.height) -
                max(screen.usableY, stored.y)
        if (overlapWidth <= 0 || overlapHeight <= 0) return null
        val area = stored.width.toLong() * stored.height.toLong()
        val overlap = overlapWidth.toLong() * overlapHeight.toLong()
        if (overlap * 2 < area) return null
        return WindowBounds(
            x = stored.x.dp,
            y = stored.y.dp,
            width = stored.width.dp,
            height = stored.height.dp,
            maximised = stored.maximised,
        )
    }
}

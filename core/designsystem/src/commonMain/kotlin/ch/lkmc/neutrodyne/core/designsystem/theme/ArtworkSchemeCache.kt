// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.SchemeContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Stores computed artwork only. App fallback and pure black always use current preferences. */
internal class ArtworkSchemeCache(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Key(
        val seed: Int,
        val dark: Boolean,
        val contrast: Double,
    )

    private val mutex = Mutex()
    private val schemes = linkedMapOf<Key, ColorScheme?>()

    suspend fun scheme(
        seed: Int,
        dark: Boolean,
        contrast: Double,
    ): ColorScheme? =
        withContext(dispatcher) {
            val key = Key(seed, dark, contrast.normalizedContrast())
            mutex.withLock {
                if (schemes.containsKey(key)) {
                    val cached = schemes.remove(key)
                    schemes[key] = cached
                    return@withLock cached
                }

                val scheme = build(key)
                schemes[key] = scheme
                if (schemes.size > CAPACITY) schemes.remove(schemes.keys.first())
                scheme
            }
        }

    private fun build(key: Key): ColorScheme? {
        val hct = Hct.fromInt(key.seed)
        if (hct.chroma < MIN_CHROMA) return null

        val scheme = SchemeContent(hct, key.dark, key.contrast).toComposeColorScheme()
        if (!key.dark || Hct.fromInt(scheme.primaryContainer.toArgb()).tone <= MAX_DARK_CONTAINER_TONE) {
            return scheme
        }

        val palette = TonalPalette.fromHueAndChroma(hct.hue, max(hct.chroma, MIN_CLAMP_CHROMA))
        return scheme.copy(
            primaryContainer = Color(palette.tone(DARK_CONTAINER_TONE)),
            onPrimaryContainer = Color(palette.tone(DARK_ON_CONTAINER_TONE)),
        )
    }

    private companion object {
        const val CAPACITY = 16
        const val MIN_CHROMA = 8.0
        const val MIN_CLAMP_CHROMA = 16.0
        const val MAX_DARK_CONTAINER_TONE = 40.0
        const val DARK_CONTAINER_TONE = 30
        const val DARK_ON_CONTAINER_TONE = 90
    }
}

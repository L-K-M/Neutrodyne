// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.ui.graphics.toArgb
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrandColorsTest {
    @Test fun sixVariantsHaveReadableOnColoursAndStableRoles() {
        val expected =
            listOf(
                "ff914c00 ffffffff fff3881c ff5b2e00 fff8f9ff ff191c20 ffffbf8d ff9dac11 ff73777f",
                "ff562b00 ffffffff ffa75900 ffffffff fff8f9ff ff0e1115 ff946238 ff687300 ff4e535a",
                "ff472200 ffffffff ff723b00 ffffffff fff8f9ff ff000000 ff6a3f18 ff464d00 ff282d33",
                "ffffb77f ff4e2600 fff3881c ff5b2e00 ff101418 ffe0e2e8 ff683c16 ff9dac11 ff8c9199",
                "ffffd4b5 ff3e1d00 fff3881c ff2a1200 ff101418 ffffffff ffbd8458 ff9dac11 ffaeb2ba",
                "ffffede1 ff000000 ffffb274 ff170700 ff101418 ffffffff fff4b585 ffbccc37 ffecf0f9",
            )
        var variant = 0
        for (dark in listOf(false, true)) {
            for (contrast in listOf(0.0, 0.5, 1.0)) {
                val scheme = BrandColors.scheme(dark, contrast)
                assertSame(scheme, BrandColors.scheme(dark, contrast))
                if (!dark && contrast == 0.0) {
                    assertEquals(BrandColors.SEED_AMBER, scheme.primaryContainer.toArgb())
                }
                assertEquals(
                    expected[variant++],
                    listOf(
                        scheme.primary,
                        scheme.onPrimary,
                        scheme.primaryContainer,
                        scheme.onPrimaryContainer,
                        scheme.surface,
                        scheme.onSurface,
                        scheme.secondaryContainer,
                        scheme.tertiaryContainer,
                        scheme.outline,
                    ).joinToString(" ") { it.toArgb().toUInt().toString(16) },
                )
                for ((bg, fg) in listOf(
                    scheme.primary to scheme.onPrimary,
                    scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary,
                    scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary,
                    scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.background to scheme.onBackground,
                    scheme.surface to scheme.onSurface,
                    scheme.surfaceContainerHigh to scheme.onSurface,
                    scheme.surfaceContainerHighest to scheme.onSurface,
                    scheme.primaryFixed to scheme.onPrimaryFixed,
                    scheme.primaryFixedDim to scheme.onPrimaryFixedVariant,
                    scheme.secondaryFixed to scheme.onSecondaryFixed,
                    scheme.secondaryFixedDim to scheme.onSecondaryFixedVariant,
                    scheme.tertiaryFixed to scheme.onTertiaryFixed,
                    scheme.tertiaryFixedDim to scheme.onTertiaryFixedVariant,
                    scheme.surfaceVariant to scheme.onSurfaceVariant,
                    scheme.error to scheme.onError,
                    scheme.errorContainer to scheme.onErrorContainer,
                    scheme.inverseSurface to scheme.inverseOnSurface,
                )) {
                    assertTrue(
                        ArtColors.contrast(bg.toArgb(), fg.toArgb()) >= 4.5,
                        "dark=$dark contrast=$contrast bg=$bg fg=$fg",
                    )
                }
                assertTrue(ArtColors.contrast(scheme.outline.toArgb(), scheme.surface.toArgb()) >= 3.0)
            }
        }
    }
}

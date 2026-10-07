// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * S9's symbol leg on the JVM (01 S9): the Material Symbols the navigation chrome uses are baked
 * `ImageVector`s — each carries real path data, so nothing depends on a font being installed on
 * the device. Rendering itself is what the GMD suite exercises when it draws the suite items.
 */
class NdIconsTest {
    @Test
    fun navigationSymbolsCarryBakedPathData() {
        val icons =
            listOf(
                NdIcons.DynamicFeed,
                NdIcons.DynamicFeedFilled,
                NdIcons.GridView,
                NdIcons.GridViewFilled,
                NdIcons.QueueMusic,
                NdIcons.QueueMusicFilled,
                NdIcons.Download,
                NdIcons.DownloadFilled,
                NdIcons.Explore,
                NdIcons.ExploreFilled,
                NdIcons.Settings,
            )

        for (icon in icons) {
            val paths = icon.allPaths()
            assertThat(paths).isNotEmpty()
            assertThat(paths.all { it.pathData.isNotEmpty() }).isTrue()
        }
    }

    private fun ImageVector.allPaths(): List<VectorPath> = root.allPaths()

    private fun VectorGroup.allPaths(): List<VectorPath> =
        flatMap { node ->
            when (node) {
                is VectorPath -> listOf(node)
                is VectorGroup -> node.allPaths()
            }
        }
}

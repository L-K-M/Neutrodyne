// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInfoTest {
    private val info =
        BuildInfo(
            versionName = "0.1.0",
            versionCode = 10095,
            debug = true,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = "https://github.com/L-K-M/Neutrodyne",
            updateManifestUrl = "https://github.com/L-K-M/Neutrodyne/releases/latest/download/update.json",
            engineManifestUrl = "https://l-k-m.github.io/Neutrodyne/engine/ytdlp-approved.json",
            youTubeEngineBundled = true,
            desktop =
                BuildInfo.Desktop(
                    os = DesktopOs.LINUX,
                    arch = DesktopArch.X64,
                    installKind = InstallKind.DEV,
                    runtime = "system",
                ),
            shippedLocales = persistentListOf("en", "de"),
            podcastIndexKey = "SECRETKEY123",
            podcastIndexSecret = "s3cr3t-value",
        )

    /** Secrets must never reach log output through BuildInfo's toString (01). */
    @Test
    fun `toString omits the podcast index secrets`() {
        val text = info.toString()

        assertFalse(text.contains("SECRETKEY123"))
        assertFalse(text.contains("s3cr3t-value"))
        assertFalse(text.contains("podcastIndexKey="))
        assertFalse(text.contains("podcastIndexSecret="))
    }

    @Test
    fun `toString still carries the non-secret fields`() {
        val text = info.toString()

        assertTrue(text.contains("versionName=0.1.0"))
        assertTrue(text.contains("versionCode=10095"))
        assertTrue(text.contains("platform=DESKTOP"))
        assertTrue(text.contains("LINUX"))
        assertTrue(text.contains("arm64").not())
    }
}

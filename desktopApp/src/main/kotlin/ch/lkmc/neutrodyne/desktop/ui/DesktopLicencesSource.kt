// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.ui

import ch.lkmc.neutrodyne.feature.settings.LicencesSource
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the AboutLibraries metadata of `:desktopApp` (01 AboutLibraries and the Licences screen):
 * the plugin's `exportLibraryDefinitions` task writes `aboutlibraries.json` into the module's
 * resources at build time — the runtime classpath's copy, not Android's `res/raw`.
 * `null` means the build carries no metadata (emergency builds, tests without the resource).
 *
 * Deviation repair (recorded in 01 step 33 and 11 Desktop UX, 2026-10-06): AboutLibraries 15.2.0
 * exports a custom licence definition keyed by its content hash while the manual library entry
 * references it by its config id, so the parsed [Library] ends up with an empty licence set (the
 * manual entries of `:app` are affected the same way). [reattachCustomLicences] re-links the
 * OpenJDK runtime entry to its definition — the licence's own SPDX expression identifies it —
 * and becomes a no-op once the plugin resolves the reference itself.
 */
internal class DesktopLicencesSource : LicencesSource {
    override suspend fun load(): Libs? =
        withContext(Dispatchers.IO) {
            val json =
                DesktopLicencesSource::class.java.classLoader
                    ?.getResourceAsStream(RESOURCE)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@withContext null
            reattachCustomLicences(Libs.Builder().withJson(json).build())
        }

    /**
     * The one manual entry this shell defines (`desktopApp/config/libraries/openjdk-runtime.json`).
     * Its licence definition carries this SPDX expression, which no Gradle dependency uses.
     */
    private fun reattachCustomLicences(libs: Libs): Libs {
        val openJdk = libs.libraries.firstOrNull { it.uniqueId == OPENJDK_RUNTIME_ID } ?: return libs
        if (openJdk.licenses.isNotEmpty()) return libs

        val licence =
            libs.licenses.firstOrNull { it.spdxId == OPENJDK_SPDX_EXPRESSION } ?: return libs
        return libs.copy(
            libraries =
                libs.libraries.map {
                    if (it ===
                        openJdk
                    ) {
                        it.copy(licenses = setOf(licence))
                    } else {
                        it
                    }
                },
        )
    }

    private companion object {
        /** The plugin's output file name inside the module's resources. */
        const val RESOURCE = "aboutlibraries.json"

        const val OPENJDK_RUNTIME_ID = "neutrodyne:openjdk-runtime"
        const val OPENJDK_SPDX_EXPRESSION = "GPL-2.0-only WITH Classpath-exception-2.0"
    }
}

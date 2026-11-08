// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.links

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.desktop.os.OsRegistrationInitializer
import ch.lkmc.neutrodyne.desktop.system.JnaWindowsRegistry
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * 11's start-up band 0–99 on Windows: register the URL schemes at every start of a
 * packaged build (`neutrodyne` always; the four podcast schemes only when unclaimed or
 * already ours, so no other podcast app's handler is taken over silently). A dev run —
 * no `jpackage.app-path` — and every other OS skip the initializer entirely. Marked an
 * [OsRegistrationInitializer], so the smoke and AOT journeys never run it either (11
 * Smoke mode step 1); only the production shell does. The explicit `binding` keeps the
 * contribution in `Set<AppInitializer>` — Metro would otherwise bind the declared marker.
 */
@ContributesIntoSet(AppScope::class, binding<AppInitializer>())
@SingleIn(AppScope::class)
@Inject
class UrlSchemeRegistrationInitializer(
    private val buildInfo: BuildInfo,
) : OsRegistrationInitializer {
    override val order: Int = ORDER

    override suspend fun run() {
        if (buildInfo.desktop?.os != DesktopOs.WINDOWS) return
        val launcher = System.getProperty(JPACKAGE_APP_PATH)
        if (launcher == null) {
            Log.i(TAG) { "dev run without jpackage.app-path; URL schemes stay unregistered" }
            return
        }
        runCatching { UrlSchemeRegistrar(JnaWindowsRegistry, launcher).registerAtStart() }
            .onFailure { Log.w(TAG, it) { "URL-scheme registration failed; links stay with their current handlers" } }
    }

    companion object {
        const val ORDER = 50
        private const val TAG = "UrlSchemes"
        private const val JPACKAGE_APP_PATH = "jpackage.app-path"
    }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.ui

import android.app.Application
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.feature.settings.LicencesSource
import com.mikepenz.aboutlibraries.Libs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the AboutLibraries metadata the plugin generates into `res/raw/aboutlibraries.json` at build time
 * (offline, 01 AboutLibraries and the Licences screen).
 */
internal class AndroidLicencesSource(private val application: Application) : LicencesSource {
    override suspend fun load(): Libs? = withContext(Dispatchers.IO) {
        val json = application.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        Libs.Builder().withJson(json).build()
    }
}

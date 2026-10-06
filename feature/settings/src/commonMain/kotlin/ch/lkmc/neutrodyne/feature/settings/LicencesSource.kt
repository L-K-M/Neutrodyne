// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import com.mikepenz.aboutlibraries.Libs

/**
 * Loads the AboutLibraries data the shells generate (01 AboutLibraries and the Licences screen).
 * The Gradle plugin writes `aboutlibraries.json` into `:app`'s and `:desktopApp`'s resources;
 * each shell binds an implementation that reads that resource and parses it with
 * `aboutlibraries-core`. `null` means the build carries no metadata (emergency builds, tests).
 */
public fun interface LicencesSource {
    public suspend fun load(): Libs?
}

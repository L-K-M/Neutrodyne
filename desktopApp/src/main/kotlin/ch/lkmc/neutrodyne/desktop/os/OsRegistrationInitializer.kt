// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.os

import ch.lkmc.neutrodyne.core.common.AppInitializer

/**
 * An [AppInitializer] whose work writes host OS registrations — the Windows HKCU keys
 * and shell shortcuts of 11 Links and files from the OS and Windows MSI and ZIP. The
 * packaged smoke and AOT-training journeys run the real graph on a throwaway `AppDirs`
 * root but must never touch the user's registrations (11 Smoke mode step 1), so
 * `ShellJourney` runs [withoutOsRegistrations]; the production shell keeps the whole set.
 */
internal interface OsRegistrationInitializer : AppInitializer

/** Every initializer except the host OS registrations — all bands, all other loading. */
internal fun Collection<AppInitializer>.withoutOsRegistrations(): Set<AppInitializer> =
    filterNot { it is OsRegistrationInitializer }.toSet()

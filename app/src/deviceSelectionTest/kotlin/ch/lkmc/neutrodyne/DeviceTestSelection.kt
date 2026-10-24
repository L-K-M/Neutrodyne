// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import android.os.Bundle

/**
 * The M3 AC6 transport gate's phase arming, shared by the runner and the selector gate.
 * A phase method only runs when `bmgrPhase=<name>` AND `class` pins exactly
 * `BmgrTransportDeviceTest#<phase method>` on a positive-matrix API (31/36) — an ordinary
 * `full`/seeded/orchestrated scope can never select it, and a wrong phase/method/API
 * combination is reported as an exclusion rather than silently skipped.
 */
internal object BmgrTransportPhases {
    const val ARG = "bmgrPhase"
    const val TEST_CLASS = "ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
    const val PREPARE = "prepare"
    const val VERIFY = "verify"
    const val EMPTY = "empty"
    val METHODS =
        mapOf(
            PREPARE to "prepareLocalTransportBackup",
            VERIFY to "firstLaunchRestoresTransportedSnapshot",
            EMPTY to "firstLaunchWithoutTransportDataStaysEmpty",
        )
    val APIS = setOf(31, 36)
}

internal fun bmgrArmedMethod(
    arguments: Bundle,
    sdk: Int,
): String? {
    val phase = arguments.getString(BmgrTransportPhases.ARG) ?: return null
    val method = BmgrTransportPhases.METHODS[phase] ?: return null
    if (sdk !in BmgrTransportPhases.APIS) return null
    // Pin exactly one method: no class lists, no comma-joined selectors, no whole-class runs.
    return method.takeIf { arguments.getString("class") == "${BmgrTransportPhases.TEST_CLASS}#$it" }
}

/** Shared by the device runner and its host regression tests. */
internal fun selectSupportedDeviceTests(
    arguments: Bundle,
    sdk: Int,
    abi: String,
    shippedLocales: List<String>,
    seededRestore: Boolean,
): Map<String, String> {
    val excluded =
        buildMap {
            if (!seededRestore) {
                put("ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest", "requires firstLaunchSeed=restore")
            }
            if (bmgrArmedMethod(arguments, sdk) == null) {
                put(
                    BmgrTransportPhases.TEST_CLASS,
                    "requires bmgrPhase + exact class#method selector on API 31/36",
                )
            }
            if (sdk < Build.VERSION_CODES.TIRAMISU && shippedLocales.none { it.substringBefore('-') == "de" }) {
                put(
                    "ch.lkmc.neutrodyne.PerAppLanguageTest#perAppLanguageRelabelsTheDestinations",
                    "API < 33 requires shipped German Android resources",
                )
            }
            if (sdk < Build.VERSION_CODES.P && abi == "x86_64") {
                put("ch.lkmc.neutrodyne.YtxReleaseSmokeTest", "CPython 3.14 cannot load on x86_64 Android 8.x")
            }
        }
    val selectors = listOfNotNull(arguments.getString("notClass")) + excluded.keys
    if (selectors.isNotEmpty()) arguments.putString("notClass", selectors.joinToString(","))
    return excluded
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import android.os.Bundle

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
            if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                put("ch.lkmc.neutrodyne.AccessibilityChecksTest", "ATF requires API 34+")
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

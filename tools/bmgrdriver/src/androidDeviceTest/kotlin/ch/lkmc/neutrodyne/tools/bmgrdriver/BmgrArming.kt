// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.os.Build
import android.os.Bundle

/**
 * Arms one driver phase — a bmgr leg only runs when the host pins `bmgrPhase=<name>`, the
 * exact `class#method` selector and a disposable API 31/36 lane (05 Testing with bmgr;
 * owner decision 2026-10-21). Without the full triple [BmgrDriverRunner] excludes the test
 * class before discovery, so the module's ordinary `ciGroupAndroidDeviceTest` /
 * `nightlyGroupAndroidDeviceTest` runs only see [DriverSelfTest] and the driver can never
 * seed, click or bind the target inside normal CI.
 */
internal object BmgrArming {
    const val ARG_PHASE = "bmgrPhase"
    const val ARG_NONCE = "bmgrNonce"
    const val TARGET_PACKAGE = "ch.lkmc.neutrodyne"

    val TEST_CLASS: String = BmgrDriverTest::class.java.name

    val PHASE_METHODS: Map<String, String> =
        mapOf(
            "seed" to "seedImport",
            "flip" to "settingsFlip",
            "encrypted" to "encryptedImportRejected",
        )

    private val APIS = setOf(31, 36)

    /** Full triple check used by the runner before discovery. */
    fun isArmed(arguments: Bundle): Boolean {
        val phase = arguments.getString(ARG_PHASE) ?: return false
        val method = PHASE_METHODS[phase] ?: return false
        if (Build.VERSION.SDK_INT !in APIS) return false
        return arguments.getString("class") == "$TEST_CLASS#$method"
    }

    /**
     * Second line of defence at test entry: a bypassed runner still refuses the lane, and
     * the phase method asserts the arming triple itself rather than trusting discovery.
     */
    fun requireArmed(
        phase: String,
        method: String,
        arguments: Bundle,
    ): String {
        check(PHASE_METHODS[phase] == method) { "unknown bmgr driver phase/method $phase/$method" }
        check(Build.VERSION.SDK_INT in APIS) {
            "bmgr driver phase arms only on API 31/36, not ${Build.VERSION.SDK_INT}"
        }
        check(arguments.getString(ARG_PHASE) == phase) { "bmgrPhase argument missing for $phase" }
        check(arguments.getString("class") == "$TEST_CLASS#$method") {
            "class#method selector not pinned to $method"
        }
        val nonce = arguments.getString(ARG_NONCE)
        require(!nonce.isNullOrBlank()) { "bmgrNonce argument missing" }
        return nonce
    }
}

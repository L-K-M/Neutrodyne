// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import java.io.File

internal actual fun convergenceSeedCount(): Int = System.getProperty("neutrodyne.syncSeeds", "1000").toInt()

internal actual fun convergenceRegressions(): String =
    File(
        requireNotNull(System.getProperty("neutrodyne.moduleDir")) {
            "neutrodyne.moduleDir is unset; run the tests through Gradle"
        },
        "src/commonTest/resources/convergence/regressions.json",
    ).readText()

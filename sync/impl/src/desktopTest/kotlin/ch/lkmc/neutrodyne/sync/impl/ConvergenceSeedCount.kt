// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import java.io.File

internal actual fun convergenceSeedCount(): Int = System.getProperty("neutrodyne.syncSeeds", "1000").toInt()

internal actual fun convergenceRegressions(): String =
    File(
        System.getProperty("neutrodyne.moduleDir"),
        "src/commonTest/resources/convergence/regressions.json",
    ).readText()

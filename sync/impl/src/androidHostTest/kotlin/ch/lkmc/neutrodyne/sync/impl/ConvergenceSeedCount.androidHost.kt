// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

internal actual fun convergenceSeedCount(): Int = 1000

internal actual fun convergenceRegressions(): String =
    object {}.javaClass.getResource("/convergence/regressions.json")?.readText() ?: "[]"

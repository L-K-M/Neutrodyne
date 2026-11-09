// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

/** Live player ownership checked by position and session writers inside their transaction. */
fun interface PositionWriteGate {
    fun allowsSaves(episodeId: Long): Boolean

    companion object {
        /** Graphs without a player and direct repository fixtures have no remote-played freeze. */
        val Unrestricted = PositionWriteGate { true }
    }
}

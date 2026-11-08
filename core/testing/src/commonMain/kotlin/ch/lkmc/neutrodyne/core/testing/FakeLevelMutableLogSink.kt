// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.LevelMutableLogSink
import ch.lkmc.neutrodyne.core.common.LogLevel

/** Test double for the `Set<LevelMutableLogSink>` verbose-logging consumers. */
class FakeLevelMutableLogSink(
    override var minLevel: LogLevel = LogLevel.INFO,
) : LevelMutableLogSink {
    val lines = mutableListOf<String>()

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        lines += "$level $tag $message"
    }
}

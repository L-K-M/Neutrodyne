// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import java.text.Collator

/**
 * [TitleCollator] backed by `java.text.Collator` for the device locale (08: A–Z library
 * titles). Bound by the app graph; `Collator` is not thread-safe, so calls serialize here.
 */
public class PlatformTitleCollator : TitleCollator {
    private val collator = Collator.getInstance()

    @Synchronized
    override fun compare(
        a: String,
        b: String,
    ): Int = collator.compare(a, b)
}

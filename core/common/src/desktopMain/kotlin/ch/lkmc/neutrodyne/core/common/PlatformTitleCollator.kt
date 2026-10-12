// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.text.Collator

/**
 * [TitleCollator] backed by `java.text.Collator` for the OS locale (08: A–Z library
 * titles). Bound by the desktop graph; `Collator` is not thread-safe, so calls serialize here.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class PlatformTitleCollator : TitleCollator {
    private val collator = Collator.getInstance()

    @Synchronized
    override fun compare(
        a: String,
        b: String,
    ): Int = collator.compare(a, b)
}

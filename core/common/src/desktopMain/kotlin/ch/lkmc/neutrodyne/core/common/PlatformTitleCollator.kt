// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.text.Collator
import java.util.Locale

/**
 * [TitleCollator] backed by `java.text.Collator` for the current app locale (08: A–Z library
 * titles). Bound by the desktop graph; `Collator` is not thread-safe, so calls serialize here.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class PlatformTitleCollator : TitleCollator {
    private var locale = Locale.getDefault()
    private var collator = Collator.getInstance(locale)

    @Synchronized
    override fun compare(
        a: String,
        b: String,
    ): Int {
        val currentLocale = Locale.getDefault()
        if (locale != currentLocale) {
            collator = Collator.getInstance(currentLocale)
            locale = currentLocale
        }
        return collator.compare(a, b)
    }
}

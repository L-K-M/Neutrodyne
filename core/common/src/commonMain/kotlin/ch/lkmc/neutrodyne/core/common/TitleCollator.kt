// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * 08 Library sort: A–Z title ordering for the app's locale. An interface here because
 * `commonMain` cannot use JDK types; the Android and desktop source sets contribute
 * implementations wrapping the JDK `Collator` ([PlatformTitleCollator]).
 */
public interface TitleCollator {
    /** Locale-aware compare of two display titles (the app's locale on each platform). */
    public fun compare(
        a: String,
        b: String,
    ): Int

    /** A [Comparator] view of [compare] for `sortedWith`. */
    public fun comparator(): Comparator<String> = Comparator { a, b -> compare(a, b) }
}

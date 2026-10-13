// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** `:core:data`'s desktop bindings (03): the JVM parser pair (kxml2 via service discovery). */
@BindingContainer
@ContributesTo(AppScope::class)
object DesktopDataBindings {
    /** kxml2 through `META-INF/services` discovery (the `PullParserFactory` doc). */
    @Provides
    fun pullParserFactory(): PullParserFactory = PullParserFactory.Discovered

    @Provides
    @SingleIn(AppScope::class)
    fun feedParser(factory: PullParserFactory): FeedParser = XmlPullFeedParser(factory)

    @Provides
    @SingleIn(AppScope::class)
    fun showNotesSanitizer(): ShowNotesSanitizer = JsoupShowNotesSanitizer()
}

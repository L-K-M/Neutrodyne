// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Creates the [XmlPullParser] a parse runs on (03 Parser). */
public fun interface PullParserFactory {
    public fun create(): XmlPullParser

    public companion object {
        /**
         * The desktop and JVM-test binding: XmlPull discovery finds kxml2 through its
         * `META-INF/services` entry, so the island names no kxml2 class. Android's binding passes
         * `android.util.Xml.newPullParser()` (AOSP's KXmlParser) from `:core:data`'s `androidMain`.
         */
        public val Discovered: PullParserFactory =
            PullParserFactory { XmlPullParserFactory.newInstance().newPullParser() }
    }
}

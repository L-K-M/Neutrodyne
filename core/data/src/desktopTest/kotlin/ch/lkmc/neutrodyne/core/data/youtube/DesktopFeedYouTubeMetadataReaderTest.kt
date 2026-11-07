// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser

class DesktopFeedYouTubeMetadataReaderTest :
    FeedYouTubeMetadataReaderTest(XmlPullFeedParser(PullParserFactory.Discovered))

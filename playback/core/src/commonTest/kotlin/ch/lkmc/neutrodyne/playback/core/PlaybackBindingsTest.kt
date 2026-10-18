// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.playback.api.NoPlaybackController
import kotlin.test.Test
import kotlin.test.assertSame

class PlaybackBindingsTest {
    @Test fun restoreCanResolveThePrePlaybackPausePort() {
        assertSame(NoPlaybackController, PlaybackBindings.playbackController())
    }
}

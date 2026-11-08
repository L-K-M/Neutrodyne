// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline and startup profiles for the release build against `nonMinifiedRelease`.
 * The producer plugin runs only `BaselineProfileRule` tests on that variant; the consumer merges
 * the collected rules into `app/src/release/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfile = BaselineProfileRule()

    @Test
    fun coldStart() {
        // The startup journey also feeds the startup profile, so R8 can lay out the startup DEX.
        baselineProfile.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            with(JourneyActions) {
                seed()
                pressHome()
                coldStart()
            }
        }
    }

    // Each method gets its own output prefix; repeated collects in one test overwrite it.
    @Test
    fun coverGrid() {
        baselineProfile.collect(packageName = PACKAGE) {
            with(JourneyActions) {
                prepareJourney(Destination.LIBRARY, "library_grid")
                flingCoverGrid()
            }
        }
    }

    @Test
    fun podcastDetail() {
        baselineProfile.collect(packageName = PACKAGE) {
            with(JourneyActions) {
                prepareJourney(Destination.LIBRARY, "library_grid")
                openPodcast()
            }
        }
    }

    @Test
    fun playerExpansion() {
        baselineProfile.collect(packageName = PACKAGE) {
            with(JourneyActions) {
                prepareJourney(Destination.FEEDS, "mini_player")
                expandCollapsePlayer()
            }
        }
    }
}

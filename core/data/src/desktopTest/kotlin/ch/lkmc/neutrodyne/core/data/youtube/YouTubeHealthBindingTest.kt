// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

/**
 * The contributed [YouTubeHealthBindings] provider is the only place the app version code and
 * the bound stream resolver reach the health actor; constructor tests with explicit parameters
 * never prove it (M9a review: both parameters were silently defaulted at the binding site).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeHealthBindingTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test fun boundHealthPersistsItsAppMarkerAndOpensIntoTheBoundResolver() =
        runTest {
            val settings = FakeSettingsRepository()
            val clock = TestClock()
            val streams = ProbeStreams()
            val bind = {
                YouTubeHealthBindings.health(
                    settings,
                    clock,
                    backgroundScope,
                    TestDb.inMemory().also { db = it },
                    { RecordingScheduler() },
                    buildInfo(),
                    { streams },
                )
            }
            val health = bind()
            health.awaitLoaded()
            repeat(5) { health.reportExtraction(ExtractionOutcome.ParseFailure("video$it")) }
            // Breaker opening purges the URL cache through the bound resolver.
            assertEquals(1, streams.invalidations)
            health.awaitLoaded()
            // The persisted commit marker is the bound versionCode, not a silent default.
            assertEquals(VERSION_CODE, settings.get(Keys.BREAKER_VERSION_CODE))
            // A fresh construction restores the persisted breaker — impossible with marker 0.
            val restarted = bind()
            restarted.awaitLoaded()
            assertIs<ExtractionGate.Deny>(restarted.extractionGate(clock.now()))
        }

    private fun buildInfo() =
        BuildInfo(
            versionName = "0.1.0",
            versionCode = VERSION_CODE,
            debug = false,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = "https://example.com/repo",
            updateManifestUrl = "https://example.com/update.json",
            engineManifestUrl = "https://example.com/engine.json",
            youTubeEngineBundled = true,
            shippedLocales = persistentListOf("en"),
            podcastIndexKey = "",
            podcastIndexSecret = "",
        )

    /** The bound `YouTubeStreamResolver`; records whether breaker opening reached it. */
    private class ProbeStreams : YouTubeStreamResolver {
        var invalidations = 0

        override suspend fun resolveAudio(
            videoId: String,
            pref: AudioPref,
            purpose: ResolvePurpose,
        ): ResolveResult = ResolveResult.Unsupported

        override fun invalidate(videoId: String) = Unit

        override fun invalidateAll() {
            invalidations++
        }
    }

    private class RecordingScheduler : RefreshScheduler {
        override fun enqueueNow(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ) = Unit

        override suspend fun reschedulePeriodic() = Unit

        override suspend fun enqueueContinuation(): Boolean = true

        override fun requestFirstFetch() = Unit
    }

    private companion object {
        const val VERSION_CODE = 42
    }
}

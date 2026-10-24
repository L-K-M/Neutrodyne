// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import androidx.test.internal.runner.RunnerArgs
import androidx.test.internal.runner.TestRequestBuilder
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DeviceTestSelectionTest {
    @Test
    fun unseededOreoSelectsExactlyTheSupportedCases() {
        for (sdk in listOf(26, 27)) {
            assertThat(select(sdk = sdk).getString("notClass")?.split(','))
                .containsExactly(RESTORE, BMGR, RELABEL, YTX)
        }
    }

    @Test
    fun modernDevicesKeepLocaleAndPythonAcceptanceChecks() {
        for (sdk in listOf(33, 34, 36)) {
            assertThat(select(sdk = sdk).getString("notClass")).isEqualTo("$RESTORE,$BMGR")
        }
    }

    @Test
    fun supportedOreoAbiKeepsPythonCheck() {
        assertThat(select(sdk = 26, abi = "arm64-v8a").getString("notClass")?.split(','))
            .containsExactly(RESTORE, BMGR, RELABEL)
    }

    @Test
    fun pieKeepsPythonCheck() {
        assertThat(select(sdk = 28).getString("notClass")?.split(','))
            .containsExactly(RESTORE, BMGR, RELABEL)
    }

    @Test
    fun unarmedBmgrTransportClassIsAlwaysExcluded() {
        for (sdk in listOf(26, 31, 36)) {
            assertThat(select(sdk = sdk).getString("notClass")?.split(',')).contains(BMGR)
            assertThat(select(sdk = sdk, seeded = true).getString("notClass")?.split(',')).contains(BMGR)
        }
    }

    @Test
    fun armedBmgrPhaseSelectsExactlyItsPinnedMethod() {
        for (sdk in listOf(31, 36)) {
            val arguments = armed(phase = "verify", method = "firstLaunchRestoresTransportedSnapshot")
            selectSupportedDeviceTests(arguments, sdk, "x86_64", listOf("en-US"), false)
            assertThat(arguments.getString("notClass").orEmpty().split(',')).doesNotContain(BMGR)
            assertThat(bmgrArmedMethod(arguments, sdk)).isEqualTo("firstLaunchRestoresTransportedSnapshot")
        }
    }

    @Test
    fun bmgrPhaseOutsideItsApisStaysExcluded() {
        for (sdk in listOf(26, 30, 32, 35)) {
            val arguments = armed(phase = "verify", method = "firstLaunchRestoresTransportedSnapshot")
            selectSupportedDeviceTests(arguments, sdk, "x86_64", listOf("en-US"), false)
            assertThat(arguments.getString("notClass")?.split(',')).contains(BMGR)
            assertThat(bmgrArmedMethod(arguments, sdk)).isNull()
        }
    }

    @Test
    fun bmgrPhaseWithAMismatchedSelectorDoesNotArm() {
        // phase and method disagree: the runner must not arm anything on a partial match.
        val wrongMethod = armed(phase = "verify", method = "prepareLocalTransportBackup")
        assertThat(bmgrArmedMethod(wrongMethod, 31)).isNull()
        // A whole-class run (no `#method`) never arms — bulk scopes cannot opt the phase in.
        val wholeClass =
            Bundle().apply {
                putString("bmgrPhase", "verify")
                putString("class", BMGR)
            }
        assertThat(bmgrArmedMethod(wholeClass, 36)).isNull()
        // A comma-joined class list never arms either.
        val listed =
            Bundle().apply {
                putString("bmgrPhase", "verify")
                putString("class", "other.Foo,$BMGR#firstLaunchRestoresTransportedSnapshot")
            }
        assertThat(bmgrArmedMethod(listed, 36)).isNull()
        // An unknown phase name never arms.
        val unknown =
            Bundle().apply {
                putString("bmgrPhase", "nonsense")
                putString("class", "$BMGR#firstLaunchRestoresTransportedSnapshot")
            }
        assertThat(bmgrArmedMethod(unknown, 36)).isNull()
    }

    @Test
    fun shippedGermanKeepsRelabelCheckOnOreo() {
        val arguments = Bundle()
        selectSupportedDeviceTests(arguments, 26, "arm64-v8a", listOf("en-US", "de-DE"), false)
        assertThat(arguments.getString("notClass")).isEqualTo("$RESTORE,$BMGR")
    }

    @Test
    fun seededLanesKeepTheGenuineRestoreTest() {
        for (sdk in listOf(26, 36)) {
            assertThat(select(sdk = sdk, seeded = true).getString("notClass").orEmpty().split(','))
                .doesNotContain(RESTORE)
        }
    }

    @Test
    fun selectionPreservesCallerArgumentsAndRealFailures() {
        val fixture = FailureFixture::class.java.name
        val arguments =
            Bundle().apply {
                putString("class", fixture)
                putString("notClass", "$fixture#excludedByCaller")
                putString("annotation", "org.junit.Test")
                putString("firstLaunchSeed", "restore")
            }
        selectSupportedDeviceTests(arguments, 26, "x86_64", listOf("en-US"), true)
        assertThat(arguments.getString("class")).isEqualTo(fixture)
        assertThat(arguments.getString("annotation")).isEqualTo("org.junit.Test")
        assertThat(arguments.getString("firstLaunchSeed")).isEqualTo("restore")

        // Use the pinned runner's real argument parser and request builder, not a copy of its filter.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = RunnerArgs.Builder().fromBundle(instrumentation, arguments).build()
        val request = TestRequestBuilder().addFromRunnerArgs(args).build()
        val result = JUnitCore().run(request)
        assertThat(result.runCount).isEqualTo(1)
        assertThat(result.ignoreCount).isEqualTo(0)
        assertThat(result.failureCount).isEqualTo(1)
        assertThat(result.failures.single().message).isEqualTo("selected assertion still fails")
    }

    class FailureFixture {
        @Test
        fun selectedFailure() = fail("selected assertion still fails")

        @Test
        fun excludedByCaller() = fail("caller exclusion was lost")
    }

    private fun select(
        sdk: Int,
        abi: String = "x86_64",
        seeded: Boolean = false,
    ): Bundle = Bundle().also { selectSupportedDeviceTests(it, sdk, abi, listOf("en-US"), seeded) }

    private fun armed(
        phase: String,
        method: String,
    ): Bundle =
        Bundle().apply {
            putString("bmgrPhase", phase)
            putString("class", "$BMGR#$method")
        }

    private companion object {
        const val RESTORE = "ch.lkmc.neutrodyne.FirstLaunchRestoreDeviceTest"
        const val BMGR = "ch.lkmc.neutrodyne.BmgrTransportDeviceTest"
        const val RELABEL = "ch.lkmc.neutrodyne.PerAppLanguageTest#perAppLanguageRelabelsTheDestinations"
        const val YTX = "ch.lkmc.neutrodyne.YtxReleaseSmokeTest"
    }
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderFailure
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSettingsViewModelTest : MainDispatcherTest() {
    @Test fun countryAndAllProviderWritesPersistAcrossReentry() =
        runTest {
            val settings = FakeSettingsRepository()
            val search = FakeSearchRepository()
            val vm = DiscoverSettingsViewModel(settings, search, TestDiscoverPlatform)
            backgroundScope.launch { vm.uiState.collect {} }
            vm.setCountry(" ch ")
            ProviderId.entries.forEach { vm.setProvider(it, false) }
            runCurrent()
            assertEquals("CH", settings.get(DiscoverSettingKeys.COUNTRY))
            assertFalse(settings.get(DiscoverSettingKeys.APPLE_ENABLED))
            assertFalse(settings.get(DiscoverSettingKeys.FYYD_ENABLED))
            assertFalse(settings.get(DiscoverSettingKeys.PODCASTINDEX_ENABLED))
            val reentered = DiscoverSettingsViewModel(settings, search, TestDiscoverPlatform)
            backgroundScope.launch { reentered.uiState.collect {} }
            runCurrent()
            assertEquals("CH", reentered.uiState.value.country)
            vm.setCountry("")
            runCurrent()
            assertEquals("", settings.get(DiscoverSettingKeys.COUNTRY))
        }

    @Test fun invalidCountryAndWriteFailureKeepStoredValues() =
        runTest {
            val settings = FakeSettingsRepository()
            val vm = DiscoverSettingsViewModel(settings, FakeSearchRepository(), TestDiscoverPlatform)
            backgroundScope.launch { vm.uiState.collect {} }
            vm.setCountry("USA")
            runCurrent()
            assertEquals(DiscoverNotice.INVALID_COUNTRY, vm.uiState.value.notice)
            assertEquals("", settings.get(DiscoverSettingKeys.COUNTRY))
            settings.failNextSet = SettingsError.WriteFailed
            vm.setProvider(ProviderId.APPLE, false)
            runCurrent()
            assertEquals(DiscoverNotice.SAVE_FAILED, vm.uiState.value.notice)
            assertTrue(settings.get(DiscoverSettingKeys.APPLE_ENABLED))
        }

    @Test fun secretBuffersAreClearedAfterSuccessFailureAndCancellation() =
        runTest {
            val search = FakeSearchRepository()
            val vm = DiscoverSettingsViewModel(FakeSettingsRepository(), search, TestDiscoverPlatform)
            backgroundScope.launch { vm.uiState.collect {} }
            val good = "secret-success".toCharArray()
            vm.saveKey("key", good)
            runCurrent()
            assertTrue(good.all { it == '\u0000' })
            assertEquals(DiscoverNotice.SAVED, vm.uiState.value.notice)
            search.keyVerification = Outcome.Failure(ProviderStatus.Failed(null, ProviderFailure.CREDENTIALS))
            val bad = "secret-failure".toCharArray()
            vm.saveKey("bad", bad)
            runCurrent()
            assertTrue(bad.all { it == '\u0000' })
            assertEquals(DiscoverNotice.KEY_FAILED, vm.uiState.value.notice)
            assertEquals(
                DirectoryKeySource.USER,
                search.providers.value
                    .first { it.id == ProviderId.PODCAST_INDEX }
                    .keySource,
            )
            search.keyGate = CompletableDeferred()
            val cancelled = "secret-cancel".toCharArray()
            vm.saveKey("cancelled", cancelled)
            runCurrent()
            vm.cancelKeyEntry()
            runCurrent()
            assertTrue(cancelled.all { it == '\u0000' })
            assertFalse(vm.uiState.value.busy)
            assertFalse(
                vm.uiState.value
                    .toString()
                    .contains("secret-"),
            )
        }

    @Test fun cancellationBeforeVerificationStartsReleasesBusyState() =
        runTest {
            val vm = DiscoverSettingsViewModel(FakeSettingsRepository(), FakeSearchRepository(), TestDiscoverPlatform)
            backgroundScope.launch { vm.uiState.collect {} }
            val secret = "early-cancel".toCharArray()
            vm.saveKey("key", secret)
            vm.cancelKeyEntry()
            runCurrent()
            assertTrue(secret.all { it == '\u0000' })
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun removalFallsBackToBuildAndStorageFailureIsActionable() =
        runTest {
            val search = FakeSearchRepository().apply { buildKeySource = DirectoryKeySource.BUILD }
            val vm = DiscoverSettingsViewModel(FakeSettingsRepository(), search, TestDiscoverPlatform)
            backgroundScope.launch { vm.uiState.collect {} }
            vm.saveKey("key", "secret".toCharArray())
            runCurrent()
            vm.clearKey()
            runCurrent()
            assertEquals(
                DirectoryKeySource.BUILD,
                vm.uiState.value.providers
                    .first { it.id == ProviderId.PODCAST_INDEX }
                    .keySource,
            )
            assertEquals(DiscoverNotice.REMOVED, vm.uiState.value.notice)
            val broken =
                object : SearchRepository by search {
                    override suspend fun clearPodcastIndexKey() {
                        error("sensitive failure")
                    }
                }
            val failing = DiscoverSettingsViewModel(FakeSettingsRepository(), broken, TestDiscoverPlatform)
            backgroundScope.launch { failing.uiState.collect {} }
            failing.clearKey()
            runCurrent()
            assertEquals(DiscoverNotice.SAVE_FAILED, failing.uiState.value.notice)
            assertFalse(
                failing.uiState.value
                    .toString()
                    .contains("sensitive"),
            )
        }

    @Test fun statusesDistinguishDisabledKeylessBuildUserAndRejected() {
        val keyless = ProviderInfo(ProviderId.PODCAST_INDEX, true)
        val build = keyless.copy(keySource = DirectoryKeySource.BUILD)
        val user = build.copy(keySource = DirectoryKeySource.USER)
        val rejected = user.copy(status = ProviderStatus.Failed(null, ProviderFailure.CREDENTIALS))
        assertEquals(
            5,
            listOf(
                providerSummary(keyless),
                providerSummary(build),
                providerSummary(user),
                providerSummary(rejected),
                providerSummary(user.copy(enabled = false)),
            ).toSet().size,
        )
    }
}

internal object TestDiscoverPlatform : PlatformInfo {
    override val kind = PlatformKind.DESKTOP
    override val userAgentPlatform = "test"
    override val androidSdkInt: Int? = null
    override val regionCode = "CH"
}

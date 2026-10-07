// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Shared behaviour every [SettingsRepository] must show (09 fake contract tests): the fakes in
 * tests and the DataStore implementation in `:core:data` extend this base, so their semantics
 * cannot drift. [createRepository] runs inside `runTest`, so implementations bind their
 * dispatchers to the test scheduler. The keys below borrow real areas' names but live only
 * here — the M0a registry itself is still empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SettingsRepositoryContract {
    protected abstract fun TestScope.createRepository(): SettingsRepository

    protected enum class LibrarySegment { PODCASTS, GROUPS }

    protected val portableSyncedLong: SettingKey<Long> =
        SettingKey.Int64("playback.skip_back_ms", 10_000, SettingsFile.PORTABLE, synced = true)
    protected val portableBool: SettingKey<Boolean> =
        SettingKey.Bool("feeds.notify_new_episodes", false, SettingsFile.PORTABLE)
    protected val portableInt: SettingKey<Int> =
        SettingKey.Int32("playback.sleep_last_minutes", 15, SettingsFile.PORTABLE)
    protected val portableFloat: SettingKey<Float> =
        SettingKey.Float32("playback.speed", 1.0f, SettingsFile.PORTABLE, synced = true)
    protected val portableText: SettingKey<String> =
        SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE)
    protected val portableTextSet: SettingKey<ImmutableSet<String>> =
        SettingKey.TextSet("playback.speed_presets", persistentSetOf("1.25", "1.5"), SettingsFile.PORTABLE)
    protected val deviceText: SettingKey<String> =
        SettingKey.Text("ui.feeds_selected_source", "all", SettingsFile.DEVICE)
    protected val deviceLong: SettingKey<Long> =
        SettingKey.Int64("downloads.planner_baseline_at", 0L, SettingsFile.DEVICE)
    protected val deviceChoice: SettingKey<LibrarySegment> =
        SettingKey.Choice(
            "ui.library_segment",
            LibrarySegment.PODCASTS,
            persistentListOf(LibrarySegment.PODCASTS, LibrarySegment.GROUPS),
            SettingsFile.DEVICE,
        )

    /** A `Choice` that declares only its default, so the other constant is `OutOfRange`. */
    protected val restrictedChoice: SettingKey<LibrarySegment> =
        SettingKey.Choice(
            "ui.density",
            LibrarySegment.PODCASTS,
            persistentListOf(LibrarySegment.PODCASTS),
            SettingsFile.DEVICE,
        )

    @Test
    fun absentKeysReadTheirDefaults() =
        runTest {
            val repository = createRepository()
            val defaults: List<Pair<SettingKey<*>, Any>> =
                listOf(
                    portableSyncedLong to 10_000L,
                    portableBool to false,
                    portableInt to 15,
                    portableFloat to 1.0f,
                    portableText to "",
                    portableTextSet to persistentSetOf("1.25", "1.5"),
                    deviceText to "all",
                    deviceLong to 0L,
                    deviceChoice to LibrarySegment.PODCASTS,
                )

            for ((key, expected) in defaults) {
                assertEquals(expected, repository.get(key), "'${key.name}' should read its default")
            }
        }

    @Test
    fun everyKeyTypeRoundTrips() =
        runTest {
            val repository = createRepository()

            repository.set(portableSyncedLong, 30_000L)
            repository.set(portableBool, true)
            repository.set(portableInt, 45)
            repository.set(portableFloat, 1.5f)
            repository.set(portableText, "https://sync.example.org")
            repository.set(portableTextSet, persistentSetOf("1.75"))
            repository.set(deviceText, "group:abc")
            repository.set(deviceLong, 1_791_072_000_000L)
            repository.set(deviceChoice, LibrarySegment.GROUPS)

            assertEquals(30_000L, repository.get(portableSyncedLong))
            assertEquals(true, repository.get(portableBool))
            assertEquals(45, repository.get(portableInt))
            assertEquals(1.5f, repository.get(portableFloat))
            assertEquals("https://sync.example.org", repository.get(portableText))
            assertEquals(persistentSetOf("1.75"), repository.get(portableTextSet))
            assertEquals("group:abc", repository.get(deviceText))
            assertEquals(1_791_072_000_000L, repository.get(deviceLong))
            assertEquals(LibrarySegment.GROUPS, repository.get(deviceChoice))
        }

    @Test
    fun writingOneFileNeverTouchesTheOther() =
        runTest {
            val repository = createRepository()

            repository.set(portableText, "https://sync.example.org")
            assertEquals("all", repository.get(deviceText))

            repository.set(deviceText, "ungrouped")
            assertEquals("https://sync.example.org", repository.get(portableText))
        }

    @Test
    fun observeEmitsTheDefaultThenWrittenValuesWithoutDuplicates() =
        runTest {
            val repository = createRepository()

            repository.observe(portableBool).test {
                assertEquals(false, awaitItem())

                repository.set(portableBool, true)
                repository.set(portableBool, true) // same value again: distinctUntilChanged drops it
                assertEquals(true, awaitItem())

                repository.reset(portableBool)
                assertEquals(false, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun resetReturnsTheDefault() =
        runTest {
            val repository = createRepository()
            repository.set(portableText, "https://sync.example.org")
            repository.reset(portableText)

            assertEquals("", repository.get(portableText))
            assertEquals("", repository.observe(portableText).first())
        }

    @Test
    fun setRejectsAChoiceValueOutsideItsValues() =
        runTest {
            val repository = createRepository()
            val result = repository.set(restrictedChoice, LibrarySegment.GROUPS)

            assertEquals(Outcome.Failure(SettingsError.OutOfRange(restrictedChoice.name)), result)
            assertEquals(LibrarySegment.PODCASTS, repository.get(restrictedChoice))
        }

    @Test
    fun portableSnapshotMirrorsTheRegisteredPortableKeys() =
        runTest {
            val repository = createRepository()
            val expected =
                AllSettingKeys.list
                    .filter { it.file == SettingsFile.PORTABLE }
                    .associate { key -> key.name to repository.get(asAnyKey(key)) }

            assertEquals(expected, repository.observePortableSnapshot().first())
        }

    @Suppress("UNCHECKED_CAST")
    private fun asAnyKey(key: SettingKey<*>): SettingKey<Any> = key as SettingKey<Any>
}

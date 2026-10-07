// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] over the two `:core:datastore` stores, routing every operation by
 * [SettingKey.file] (01 DataStore files and typed setting keys, rule 4). 10's `SettingsSyncPort`
 * (MS2) will coordinate synced writes beside this class through the same domain contract.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
internal class DataStoreSettingsRepository(
    private val settingsStore: SettingsStore,
    private val deviceSettingsStore: DeviceSettingsStore,
) : SettingsRepository {
    override fun <T : Any> observe(key: SettingKey<T>): Flow<T> = store(key).observe(key)

    override suspend fun <T : Any> get(key: SettingKey<T>): T = store(key).get(key)

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ): Outcome<Unit, SettingsError> {
        if (rejectsValue(key, value)) {
            return Outcome.Failure(SettingsError.OutOfRange(key.name))
        }

        // Only non-cancellation failures land here (IOException from DataStore, 01: logged at WARN).
        return suspendRunCatching { store(key).set(key, value) }
            .fold(
                onSuccess = { Outcome.Success(Unit) },
                onFailure = { failure ->
                    Log.w(LOG_TAG, failure) { "write of '${key.name}' failed" }
                    Outcome.Failure(SettingsError.WriteFailed)
                },
            )
    }

    override suspend fun reset(key: SettingKey<*>) {
        store(key).reset(key)
    }

    override fun observePortableSnapshot(): Flow<Map<String, Any>> {
        val portableKeys = AllSettingKeys.list.filter { it.file == SettingsFile.PORTABLE }
        if (portableKeys.isEmpty()) return flowOf(emptyMap())

        val values: List<Flow<Pair<String, Any>>> =
            portableKeys.map { key ->
                settingsStore.observe(key).map { value -> key.name to value }
            }
        return combine(values) { entries -> entries.toMap() }.distinctUntilChanged()
    }

    private fun store(key: SettingKey<*>): SettingStore =
        when (key.file) {
            SettingsFile.PORTABLE -> settingsStore
            SettingsFile.DEVICE -> deviceSettingsStore
        }

    /** A `Choice` outside its declared values is the one value a key itself forbids. */
    private fun rejectsValue(
        key: SettingKey<*>,
        value: Any,
    ): Boolean = key is SettingKey.Choice<*> && key.values.none { it == value }

    private companion object {
        const val LOG_TAG = "Settings"
    }
}

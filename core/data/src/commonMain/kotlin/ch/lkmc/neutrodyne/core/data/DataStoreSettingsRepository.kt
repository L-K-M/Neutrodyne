// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingReset
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
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
    private val syncPort: SettingsSyncPort,
    private val clock: Clock,
    private val writeLocks: SettingsWriteCoordinator,
) : SettingsRepository {
    override fun <T : Any> observe(key: SettingKey<T>): Flow<T> = store(key).observe(key)

    override suspend fun <T : Any> get(key: SettingKey<T>): T = store(key).get(key)

    override suspend fun <T : Any> getOrThrow(key: SettingKey<T>): T =
        when (key.file) {
            SettingsFile.PORTABLE -> settingsStore.getOrThrow(key)
            SettingsFile.DEVICE -> deviceSettingsStore.getOrThrow(key)
        }

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ): Outcome<Unit, SettingsError> {
        // Shared registry validation (05 Rules 4): a rejected value is OutOfRange, so a
        // backup restore or sync apply counts it as skipped rather than failing.
        if (!AllSettingKeys.acceptsValue(key, value)) {
            return Outcome.Failure(SettingsError.OutOfRange(key.name))
        }

        // 01 Sync write ordering: the record+persist pair is mutually excluded with a linked
        // restore's historical accept+persist for the same key, so a concurrent restore can
        // neither capture before this record nor write its older value after this one.
        return writeLocks.withLock(key) {
            // 10 Settings capture: the durable Room intent lands before DataStore, so a crash
            // between the two recovers the write and a recorder failure leaves DataStore unchanged.
            val recorded =
                suspendRunCatching { syncPort.recordLocal(key, value) }
                    .onFailure { failure ->
                        Log.w(LOG_TAG, failure) { "sync recorder failed for '${key.name}'" }
                    }
            if (recorded.isFailure) {
                return@withLock Outcome.Failure(SettingsError.WriteFailed)
            }

            // Only non-cancellation failures land here (IOException from DataStore, 01: logged at WARN).
            suspendRunCatching {
                if (key.synced) {
                    settingsStore.setWithReceipt(key, value, recorded.getOrThrow().storedReceipt())
                } else {
                    store(key).set(key, value)
                }
            }.fold(
                onSuccess = {
                    syncPort.emitLocal(key, value, clock.now())
                    Outcome.Success(Unit)
                },
                onFailure = { failure ->
                    Log.w(LOG_TAG, failure) { "write of '${key.name}' failed" }
                    Outcome.Failure(SettingsError.WriteFailed)
                },
            )
        }
    }

    override suspend fun reset(key: SettingKey<*>) {
        writeLocks.withLock(key) {
            val origin = syncPort.recordLocal(key, null)
            if (key.synced) {
                settingsStore.resetWithReceipt(key, origin.storedReceipt())
            } else {
                store(key).reset(key)
            }
            syncPort.emitLocal(key, null, clock.now())
        }
    }

    override suspend fun resetAll(keys: List<SettingKey<*>>): Outcome<Unit, SettingsError> {
        if (keys.isEmpty()) return Outcome.Success(Unit)
        val file = keys.first().file
        // Atomicity only exists inside one DataStore file; a cross-file batch can never
        // commit as one write.
        require(keys.all { it.file == file }) { "a batched reset is atomic within one settings file only" }
        // Sorted by name so every multi-key writer takes the per-key locks in the same order.
        val locked = keys.distinctBy { it.name }.sortedBy { it.name }
        return withKeyLocks(locked) {
            // 01 Sync write ordering: each key's durable intent is recorded before the shared
            // DataStore edit, so a recorder failure leaves every stored value unchanged and a
            // synced key's receipt still commits with its removal.
            val entries = mutableListOf<SettingReset>()
            for (key in locked) {
                val recorded =
                    suspendRunCatching { syncPort.recordLocal(key, null) }
                        .onFailure { failure ->
                            Log.w(LOG_TAG, failure) { "sync recorder failed for '${key.name}'" }
                        }
                if (recorded.isFailure) {
                    return@withKeyLocks Outcome.Failure(SettingsError.WriteFailed)
                }
                entries += SettingReset(key, recorded.getOrThrow().takeIf { key.synced }?.storedReceipt())
            }
            suspendRunCatching {
                when (file) {
                    SettingsFile.PORTABLE -> settingsStore.resetAll(entries)
                    SettingsFile.DEVICE -> deviceSettingsStore.resetAll(locked)
                }
            }.fold(
                onSuccess = {
                    locked.forEach { syncPort.emitLocal(it, null, clock.now()) }
                    Outcome.Success(Unit)
                },
                onFailure = { failure ->
                    Log.w(LOG_TAG, failure) { "batched reset failed" }
                    Outcome.Failure(SettingsError.WriteFailed)
                },
            )
        }
    }

    /** Takes each key's write lock in list order; the caller sorts for a global order. */
    private suspend fun <T> withKeyLocks(
        keys: List<SettingKey<*>>,
        block: suspend () -> T,
    ): T =
        if (keys.isEmpty()) {
            block()
        } else {
            writeLocks.withLock(keys.first()) { withKeyLocks(keys.drop(1), block) }
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

    private companion object {
        const val LOG_TAG = "Settings"
    }
}

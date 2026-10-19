// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.WorkManagerImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.hasRunnableRestoreIntent
import ch.lkmc.neutrodyne.core.database.LibraryShape
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The auto-backup start-up initializer (05's work table, order 200 — after the database open
 * at band 100): arms `backup-auto-snapshot` under `UPDATE`, reconciles the enabled flag
 * against the armed debounce (a restored `backup.auto_snapshot_enabled = false` clears
 * `-now`; the periodic still runs to the worker's enabled gate), launches the library-shape
 * watcher, re-enqueues durable work a dead or unknown enqueue left behind, and runs §9's
 * session cleanup even while snapshots are disabled.
 *
 * Every dependency arrives through `Provider`: the graph builds before band 100 opens the
 * database, so constructors must not reach it — only `run()` dereferences, on the band-200
 * coroutine.
 */
@ContributesIntoSet(AppScope::class)
internal class AutoBackupInitializer
    @Inject
    constructor(
        private val snapshots: Provider<WorkManagerSnapshotScheduler>,
        private val work: Provider<WorkManagerImportWorkScheduler>,
        private val db: Provider<NeutrodyneDatabase>,
        private val settings: Provider<SettingsRepository>,
        private val cleaner: Provider<ImportSessionCleaner>,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : AppInitializer {
        override val order: Int = 200

        override suspend fun run() {
            if (!settings().get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED)) {
                suspendRunCatching { snapshots().cancelSoon() }
                    .onFailure { Log.w(TAG, it) { "snapshot debounce cancel failed" } }
            }
            suspendRunCatching { snapshots().armPeriodic() }
                .onFailure { Log.e(TAG, it) { "snapshot periodic arm failed" } }

            // The baseline is read before run() returns: a library change that lands before
            // the watcher's first query is then compared against it instead of being folded
            // into the first emission and skipped with it.
            val baseline =
                suspendRunCatching { db().backupDao().observeLibraryShape().first() }
                    .onFailure { Log.w(TAG, it) { "library-shape baseline read failed" } }
                    .getOrNull()
            appScope.launch { watchLibraryShape(baseline) }
            suspendRunCatching { reconcileDurableWork() }
                .onFailure { Log.w(TAG, it) { "durable-work reconciliation failed" } }

            // §9's sweep runs even while snapshots are disabled — the producer's own pass is
            // behind the enabled gate, so a disabled install would otherwise keep every
            // abandoned payload forever.
            appScope.launch {
                suspendRunCatching { cleaner().cleanExpired() }
                    .onFailure { Log.w(TAG, it) { "import session cleanup failed" } }
            }
        }

        /**
         * 05's library watcher: `distinctUntilChanged` drops same-shape re-emissions and
         * leading emissions equal to the start-up [baseline] are skipped — only real library
         * changes poke the debounced write, and only while the flag allows a produce. Without
         * a baseline (its read failed) the first emission stands in for it.
         */
        private suspend fun watchLibraryShape(baseline: LibraryShape?) {
            suspendRunCatching {
                val shapes = db().backupDao().observeLibraryShape().distinctUntilChanged()
                val changes = if (baseline == null) shapes.drop(1) else shapes.dropWhile { it == baseline }
                changes.collect {
                    suspendRunCatching {
                        if (settings().get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED)) {
                            snapshots().requestSoon()
                        }
                    }.onFailure { Log.w(TAG, it) { "snapshot poke failed" } }
                }
            }.onFailure { Log.w(TAG, it) { "library-shape watcher failed" } }
        }

        /**
         * Durable-work reconciliation (05's typed-acknowledgment contract): an `ALREADY_RUNNING`
         * answer consumed the intent by arbitration — what survives is an ambiguous enqueue or
         * a process death between the claim and the dispatch. Every
         * `PREVIEW` backup session with a restore intent gets `scheduleRestore` again (the
         * typed answer is idempotent), and every `COMMITTED`/`FETCHING` session gets its fetch
         * work back under `KEEP`.
         */
        private suspend fun reconcileDurableWork() {
            val dao = db().importDao()
            for (session in dao.backupPreviewSessions()) {
                if (!session.hasRunnableRestoreIntent()) continue
                val auto =
                    session.optionsJson?.let(ImportOptionsJson::decodeOrNull)?.fromAndroidBackup == true
                suspendRunCatching {
                    work().scheduleRestore(session.id, auto)
                }.onFailure {
                    Log.w(TAG, it) { "restore re-enqueue failed for session ${session.id}" }
                }
            }
            for (session in dao.activeSessions()) {
                suspendRunCatching {
                    work().schedule(session.id, ImportWorkPolicy.KEEP)
                }.onFailure {
                    Log.w(TAG, it) { "fetch re-enqueue failed for session ${session.id}" }
                }
            }
        }

        private companion object {
            const val TAG = "AutoBackupInit"
        }
    }

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest : MainDispatcherTest() {
    private val backups = FakeBackupRepository()
    private val imports = FakeImportRepository()
    private val settings = FakeSettingsRepository()
    private val receipt = backupReceipt(PrivacyCounts(7, 3))

    @Test fun passwordsResetOnEveryOpenAndWarningsUseTheFrozenDescriptor() =
        runTest {
            backups.prepareOutcome = Outcome.Success(receipt)
            backups.preflightCounts = PrivacyCounts(99, 88)
            val vm = BackupViewModel(backups, imports, settings, backgroundScope)
            vm.setPasswords(true)
            vm.create { error("Warning must precede the picker") }
            assertSame(receipt, vm.uiState.value.warning)
            assertEquals(
                PrivacyCounts(7, 3),
                vm.uiState.value.warning
                    ?.privacy,
            )
            assertEquals(listOf("prepareBackup(true)"), backups.calls)
            vm.opened()
            assertFalse(vm.uiState.value.includePasswords)
            vm.confirmWarning { "content://backup" }
            assertSame(receipt, backups.savePreparedCalls.single().first)
            assertEquals("content://backup", backups.savePreparedCalls.single().second)
            assertEquals(1, backups.calls.count { it.startsWith("prepareBackup") })
            assertFalse(vm.uiState.value.busy)
            assertEquals(1, vm.messages.value.size)
        }

    @Test fun warningCancelAndPickerCancelNeverSaveAndRemainSilent() =
        runTest {
            backups.prepareOutcome = Outcome.Success(receipt)
            val vm = BackupViewModel(backups, imports, settings, backgroundScope)
            vm.create { error("Must warn first") }
            vm.cancelWarning()
            vm.confirmWarning { error("Cancelled warning cannot open the picker") }
            assertTrue(backups.savePreparedCalls.isEmpty())
            assertTrue(vm.messages.value.isEmpty())
            vm.create { error("Must warn first") }
            vm.confirmWarning { null }
            assertTrue(backups.savePreparedCalls.isEmpty())
            backups.prepareOutcome = Outcome.Success(backupReceipt(PrivacyCounts(0, 0)))
            vm.create { null }
            assertTrue(backups.savePreparedCalls.isEmpty())
            assertTrue(vm.messages.value.isEmpty())
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun passwordOnlyAndPrivateLinkOnlyBackupsBothRequireConsent() =
        runTest {
            val vm = BackupViewModel(backups, imports, settings, backgroundScope)
            for (counts in listOf(PrivacyCounts(0, 1), PrivacyCounts(1, 0))) {
                backups.prepareOutcome = Outcome.Success(backupReceipt(counts))
                vm.create { error("Must warn first") }
                assertEquals(
                    counts,
                    vm.uiState.value.warning
                        ?.privacy,
                )
                vm.cancelWarning()
            }
        }

    @Test fun prepareAndSaveFailuresNeverReportSuccess() =
        runTest {
            val vm = BackupViewModel(backups, imports, settings, backgroundScope)
            backups.prepareOutcome = Outcome.Failure(BackupError.Storage)
            vm.create { error("Failed prepare cannot pick") }
            assertEquals(
                backupErrorText(BackupError.Storage),
                vm.messages.value
                    .single()
                    .text,
            )
            vm.acknowledgeMessage(
                vm.messages.value
                    .single()
                    .id,
            )
            backups.prepareOutcome = Outcome.Success(backupReceipt(PrivacyCounts(0, 0)))
            backups.savePreparedOutcome = Outcome.Failure(BackupError.DestinationUnavailable)
            vm.create { "file:///backup.zip" }
            assertEquals(
                backupErrorText(BackupError.DestinationUnavailable),
                vm.messages.value
                    .single()
                    .text,
            )
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun acceptedSaveSurvivesCallerDisposalAndBusyPreventsDuplicateCreation() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val delayed =
                object : BackupRepository by backups {
                    override suspend fun savePreparedBackup(
                        prepared: PreparedBackup,
                        destinationUri: String,
                    ): Outcome<Unit, BackupError> {
                        entered.complete(Unit)
                        release.await()
                        return backups.savePreparedBackup(prepared, destinationUri)
                    }
                }
            val vm = BackupViewModel(delayed, imports, settings, scope)
            val caller = launch { vm.create { "file:///backup.zip" } }
            entered.await()
            caller.cancel()
            vm.create { error("Busy prevents another picker") }
            assertTrue(vm.uiState.value.busy)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, backups.savePreparedCalls.size)
            assertFalse(vm.uiState.value.busy)
            scope.cancel()
        }

    @Test fun disposedOwnerNeverLaunchesAPickerAfterPreparation() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val delayed =
                object : BackupRepository by backups {
                    override suspend fun prepareBackup(includePasswords: Boolean) =
                        run {
                            entered.complete(Unit)
                            release.await()
                            backups.prepareBackup(includePasswords)
                        }
                }
            val vm = BackupViewModel(delayed, imports, settings, scope)
            var pickerCalls = 0
            val caller =
                launch {
                    vm.create {
                        pickerCalls++
                        "file:///backup.zip"
                    }
                }
            entered.await()
            caller.cancel()
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue(backups.savePreparedCalls.isEmpty())
            assertEquals(0, pickerCalls)
            assertFalse(vm.uiState.value.busy)
            scope.cancel()
        }

    @Test fun busyClearsEvenWhenTheApplicationJobNeverStarts() =
        runTest {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vm = BackupViewModel(backups, imports, settings, scope)
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { vm.create { "unused" } }
            assertTrue(vm.uiState.value.busy)
            scope.cancel()
            advanceUntilIdle()
            assertTrue(caller.isCancelled)
            assertFalse(vm.uiState.value.busy)
            assertTrue(backups.calls.isEmpty())
        }

    @Test fun restoreAcquisitionCancelDoesNotCreateAndErrorsAreExplicit() =
        runTest {
            val vm = BackupViewModel(backups, imports, settings, backgroundScope)
            assertNull(vm.chooseRestore { null })
            assertTrue(imports.calls.isEmpty())
            imports.createOutcome = Outcome.Failure(ImportError.Unreadable)
            assertNull(vm.chooseRestore { "file:///bad.zip" })
            assertEquals(acquisitionErrorText(ImportError.Unreadable), vm.uiState.value.acquisitionError)
            assertFalse(vm.uiState.value.busy)
        }
}

internal fun backupReceipt(counts: PrivacyCounts) =
    object : PreparedBackup {
        override val fileName = "neutrodyne-backup-2026-10-18-1200.zip"
        override val summary = BackupSummary(2_400_000, 142, 3214)
        override val privacy = counts
    }

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W6's policy hook is ordered under the same barrier as every engine's generic stale reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadResetSeamTest {
    private val mutex = Mutex()
    private val roots =
        object : StorageRoots {
            override suspend fun current(): String = error("Reset must not probe storage")

            override suspend fun list(): List<StorageRootInfo> = error("Reset must not probe storage")

            override suspend fun resolve(rootId: String): ResolvedRoot? = error("Reset must not probe storage")

            override suspend fun ensureSpace(
                rootId: String,
                requiredBytes: Long,
            ): StorageFailure? = error("No allocation")

            override suspend fun classify(
                error: Throwable,
                rootId: String,
            ): StorageFailure = error("No IO")
        }
    private val files = OkioDownloadFileSystem(roots)
    private val index = LocalMediaIndexImpl()
    private var live = setOf("work:auto", "uidt:manual")

    @Test fun resetCallsPlatformHookOnceWithBothLaneTokensWhileClaimsAreExcluded() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                val seen = mutableListOf<Set<String>>()
                val host =
                    reconciler(db) {
                        assertTrue(mutex.isLocked)
                        seen += it
                    }
                host.resetOrphanedRunners()
                live = emptySet()
                host.resetOrphanedRunners()
                assertEquals(listOf(setOf("work:auto", "uidt:manual")), seen)
            } finally {
                db.close()
            }
        }

    @Test fun failedPlatformDecisionDoesNotMarkResetComplete() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                var attempts = 0
                val host =
                    reconciler(db) {
                        attempts++
                        if (attempts == 1) error("Decision unavailable")
                    }
                assertFailsWith<IllegalStateException> { host.resetOrphanedRunners() }
                host.resetOrphanedRunners()
                host.resetOrphanedRunners()
                assertEquals(2, attempts)
            } finally {
                db.close()
            }
        }

    private fun reconciler(
        db: NeutrodyneDatabase,
        hook: suspend (Set<String>) -> Unit,
    ) = DownloadReconciler(
        db,
        files,
        roots,
        PlatformLocalUris(),
        index,
        DeferredDeletes(AndroidDownloadTestStore(), files, roots, db.downloadDao()::allRows),
        mutex,
        { live },
        TestClock(),
        hook,
    )
}

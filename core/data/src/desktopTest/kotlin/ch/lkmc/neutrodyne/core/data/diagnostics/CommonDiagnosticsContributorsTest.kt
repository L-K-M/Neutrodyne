// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The common contributors' value parsing — JSON shapes outside the happy path. */
class CommonDiagnosticsContributorsTest {
    @Test
    fun oneNonPrimitiveStatsEntryDropsOnlyItself() =
        runTest {
            val settings = FakeSettingsRepository()
            val db = TestDb.inMemory()
            try {
                settings.set(
                    DiagnosticsSettingKeys.MAINTENANCE_STATS,
                    """{"kept":1,"bad":{"nested":true},"alsoKept":2}""",
                )
                val keys = DatabaseDiagnosticsContributor(settings, db).collect().map { it.key }
                assertTrue("maintenance.kept" in keys, "primitive entries survive")
                assertTrue("maintenance.alsoKept" in keys, "entries after the bad one survive")
                assertFalse("maintenance.bad" in keys, "only the non-primitive entry drops")
            } finally {
                db.close()
            }
        }
}

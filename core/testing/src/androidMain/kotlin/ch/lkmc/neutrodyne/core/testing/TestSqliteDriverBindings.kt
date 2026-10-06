// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Test override of [SqliteDriverBindings] for Robolectric graph tests: the framework driver wraps
 * Robolectric's host SQLite, while the bundled driver's Android `.so` files cannot load on the
 * host JVM (01 Test overrides; spike S4, 2026-10-06). Instrumented tests keep `BundledSQLiteDriver`;
 * desktop tests use the bundled driver's host natives.
 */
@BindingContainer
@ContributesTo(AppScope::class, replaces = [SqliteDriverBindings::class])
object TestSqliteDriverBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun sqliteDriver(): SQLiteDriver = AndroidSQLiteDriver()
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The production [SQLiteDriver]: one bundled SQLite on Android and the desktop (PLAN D9).
 * Android Robolectric tests replace this binding with `TestSqliteDriverBindings`
 * (`:core:testing` `androidMain`), because the bundled driver's `.so` files target device ABIs,
 * not the host JVM (01 Test overrides; spike S4, 2026-10-06).
 */
@BindingContainer
@ContributesTo(AppScope::class)
object SqliteDriverBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun sqliteDriver(): SQLiteDriver = BundledSQLiteDriver()
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.StrictMigrations
import ch.lkmc.neutrodyne.core.model.BuildInfo
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Database plumbing of the Android main process (02 Database builder and connections). */
@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun databaseFactory(application: Application): DatabaseFactory = AndroidDatabaseFactory(application)

    /**
     * `debug` (and `benchmark` via its debug flag) rethrows migration failures; `release` quarantines
     * them (02 Error handling and recovery).
     */
    @Provides
    @StrictMigrations
    fun strictMigrations(buildInfo: BuildInfo): Boolean = buildInfo.debug
}

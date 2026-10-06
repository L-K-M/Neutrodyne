// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crash

import ch.lkmc.neutrodyne.BuildConfig
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashKey
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.Redactor
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.acra.ACRA

/**
 * ACRA-backed [CrashReporter] and [CrashContext] (09). With no mailbox (PO-10 open, every debug build) ACRA is
 * never installed, so both degrade to no-ops and callers show "Copy diagnostics" instead.
 */
@SingleIn(AppScope::class)
@Inject
class AcraCrashReporter :
    CrashReporter,
    CrashContext {
    override val isAvailable: Boolean
        get() = BuildConfig.ACRA_MAILTO.isNotEmpty() && ACRA.isInitialised

    override fun reportNonFatal(
        t: Throwable,
        where: String,
    ) {
        if (!isAvailable) return
        ACRA.errorReporter.handleException(t)
    }

    override fun put(
        key: CrashKey,
        value: String,
    ) {
        if (!ACRA.isInitialised) return
        ACRA.errorReporter.putCustomData(key.name, Redactor.text(value))
    }
}

/** One instance serves both contracts. */
@ContributesTo(AppScope::class)
@BindingContainer
interface CrashBindings {
    @Binds
    val AcraCrashReporter.bindCrashReporter: CrashReporter

    @Binds
    val AcraCrashReporter.bindCrashContext: CrashContext
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.desktop.playback.DesktopEnginePaths
import ch.lkmc.neutrodyne.desktop.system.IdleSleepInhibitor
import ch.lkmc.neutrodyne.desktop.system.IdleSleepInhibitors
import ch.lkmc.neutrodyne.desktop.system.LinuxMprisSession
import ch.lkmc.neutrodyne.desktop.system.MacNowPlayingSession
import ch.lkmc.neutrodyne.desktop.system.MprisShellActions
import ch.lkmc.neutrodyne.desktop.system.NdmediaDirectory
import ch.lkmc.neutrodyne.desktop.system.OsPowerMonitor
import ch.lkmc.neutrodyne.desktop.system.SystemMediaSession
import ch.lkmc.neutrodyne.desktop.system.UnavailableSystemMediaSession
import ch.lkmc.neutrodyne.desktop.system.WindowsSmtcSession
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The OS-integration bindings of MD2 (11 DesktopAppGraph's `SystemMediaSession`, the
 * `PowerMonitor` port's `OsPowerMonitor`, `IdleSleepInhibitor`): the per-OS session chosen at
 * graph creation by `BuildInfo.os`, each creation failure falling back to the documented
 * unavailable session instead of failing the graph (11 OS-integration failure modes).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopOsBindings {
    private const val TAG = "DesktopOs"

    /**
     * The shell's late-bound window hooks: MPRIS `Raise`/`Quit` arrive before the window
     * exists, so the binding hands the session two lambdas that hop onto the application
     * scope, and [DesktopShell][ch.lkmc.neutrodyne.desktop.DesktopShell] fills them once the
     * activator and the quit path exist.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun shellActions(): DesktopShellActions = DesktopShellActions()

    @Provides
    @SingleIn(AppScope::class)
    fun systemMediaSession(
        buildInfo: BuildInfo,
        @ApplicationScope scope: CoroutineScope,
        shellActions: DesktopShellActions,
    ): SystemMediaSession {
        val os = buildInfo.desktop?.os
        val session =
            runCatching {
                when (os) {
                    DesktopOs.LINUX -> {
                        LinuxMprisSession.open(
                            shell =
                                object : MprisShellActions {
                                    override fun raise() {
                                        scope.launch { shellActions.raise() }
                                    }

                                    override fun quit() {
                                        scope.launch { shellActions.quit() }
                                    }
                                },
                        )
                    }

                    DesktopOs.MACOS -> {
                        MacNowPlayingSession.open(DesktopEnginePaths.current().ndmedia)
                    }

                    DesktopOs.WINDOWS -> {
                        WindowsSmtcSession.open(DesktopEnginePaths.current().ndmedia)
                    }

                    null -> {
                        error("build info carries no desktop OS")
                    }
                }
            }
        return session.fold(
            onSuccess = { it },
            onFailure = { failure ->
                Log.w(TAG, failure) { "OS media session unavailable on $os" }
                UnavailableSystemMediaSession("OS media session unavailable: ${failure.message}")
            },
        )
    }

    /**
     * The `ndmedia` library directory (11 ndmedia interface): the same path the media
     * session and the native idle-sleep inhibitor load, bound here so `:desktop:system`'s
     * login-item registrar can open the SMAppService shim without knowing the shell's
     * resource layout.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun ndmediaDirectory(): NdmediaDirectory = NdmediaDirectory(DesktopEnginePaths.current().ndmedia)

    /**
     * MD2's `OsPowerMonitor` replaces M1a's interim no-events binding (its own comment made
     * this provider the reminder to delete that one). The session stays behind a
     * [Provider]: Linux's logind path never resolves the native media session.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun powerMonitor(
        buildInfo: BuildInfo,
        @ApplicationScope scope: CoroutineScope,
        session: Provider<SystemMediaSession>,
    ): PowerMonitor = OsPowerMonitor.create(buildInfo.desktop?.os, scope) { session() }

    /** 11 Start at login's sibling: the OS pick of the idle-sleep inhibitor. */
    @Provides
    @SingleIn(AppScope::class)
    fun idleSleepInhibitor(
        buildInfo: BuildInfo,
        ndmedia: NdmediaDirectory,
    ): IdleSleepInhibitor =
        IdleSleepInhibitors.create(
            os = buildInfo.desktop?.os,
            ndmedia = ndmedia.path,
        )
}

/** The window hooks the OS surfaces call on the application scope; safe no-ops until set. */
class DesktopShellActions {
    @Volatile var raise: () -> Unit = {}

    @Volatile var quit: () -> Unit = {}
}

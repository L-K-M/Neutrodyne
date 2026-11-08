// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.identity

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.playback.native.NativeWindowsIdentity
import java.nio.file.Path

/**
 * 11 Start-up step 4: the process `AppUserModelID` on Windows, set through the
 * `ndmedia` shim before any window or jump-list surface exists so the media
 * flyout and toasts attribute the process correctly. The ID comes from the real
 * [BuildInfo]: packaged builds use `ch.lkmc.neutrodyne`, a dev build
 * `ch.lkmc.neutrodyne.debug` (the `ch.lkmc` prefix rule). Off Windows, or when the
 * native shim is absent, this is a logged no-op — never a thrown start-up failure.
 */
internal object WindowsAppIdentity {
    private const val TAG = "WinIdentity"

    const val RELEASE_APP_USER_MODEL_ID = "ch.lkmc.neutrodyne"
    const val DEBUG_APP_USER_MODEL_ID = "ch.lkmc.neutrodyne.debug"

    fun appUserModelId(debug: Boolean): String = if (debug) DEBUG_APP_USER_MODEL_ID else RELEASE_APP_USER_MODEL_ID

    /** The shell's pre-graph call; the [ProcessIdentityOutcome] feeds the log, not the exit code. */
    fun applyProcessIdentity(
        buildInfo: BuildInfo,
        ndmedia: Path,
        open: (Path) -> ProcessAumidSetter? = ::nativeProcessAumidSetter,
    ): ProcessIdentityOutcome {
        if (buildInfo.desktop?.os != DesktopOs.WINDOWS) return ProcessIdentityOutcome.SKIPPED
        val setter =
            runCatching { open(ndmedia) }
                .onFailure { Log.w(TAG, it) { "process AppUserModelID skipped: ndmedia unavailable" } }
                .getOrNull()
                ?: return ProcessIdentityOutcome.NATIVE_UNAVAILABLE
        val aumid = appUserModelId(buildInfo.debug)
        return runCatching { setter.set(aumid) }
            .fold(
                { code ->
                    if (code == 0) {
                        Log.i(TAG) { "process AppUserModelID: $aumid" }
                        ProcessIdentityOutcome.APPLIED
                    } else {
                        Log.w(TAG) { "SetCurrentProcessExplicitAppUserModelID($aumid) failed: $code" }
                        ProcessIdentityOutcome.FAILED
                    }
                },
                {
                    Log.w(TAG, it) { "process AppUserModelID failed" }
                    ProcessIdentityOutcome.FAILED
                },
            )
    }

    /** The live `ndmedia` setter, or `null` when the library or its identity exports are absent. */
    private fun nativeProcessAumidSetter(directory: Path): ProcessAumidSetter? {
        val identity = NativeWindowsIdentity.open(directory)
        if (!identity.supported) {
            Log.w(TAG) { "ndmedia lacks the Windows identity exports; run :playback:native:buildNdmedia" }
            identity.close()
            return null
        }
        return ProcessAumidSetter { aumid -> identity.use { it.setProcessAumid(aumid) } }
    }

    enum class ProcessIdentityOutcome { APPLIED, SKIPPED, NATIVE_UNAVAILABLE, FAILED }
}

/** The one native call the shell needs before any window exists (test seam). */
internal fun interface ProcessAumidSetter {
    /** `0` applied, `<0` the native failure code (`HRESULT` or `ND_E_*`). */
    fun set(aumid: String): Int
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.identity

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.desktop.system.JnaWindowsKnownFolders
import ch.lkmc.neutrodyne.desktop.system.JnaWindowsRegistry
import ch.lkmc.neutrodyne.desktop.system.NdmediaDirectory
import ch.lkmc.neutrodyne.desktop.system.WindowsKnownFolders
import ch.lkmc.neutrodyne.desktop.system.WindowsRegistry
import ch.lkmc.neutrodyne.playback.native.NativeWindowsIdentity
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * 11 Windows MSI and ZIP, start-up band 0–99 on packaged Windows builds: the per-user
 * `HKCU\Software\Classes\AppUserModelId\<aumid>` registration (`DisplayName` + `IconUri`)
 * is written on every packaged kind so the media flyout and toasts can resolve the app's
 * name and icon, and on MSI installs the per-user Start-menu shortcut the installer
 * created gets `System.AppUserModel.ID` stamped through the `ndmedia` COM shim. The ZIP
 * fabricates nothing — it has no Start-menu entry by design. Every step is reported in a
 * [WindowsIdentityReport]; a step failure is logged by the initializer, never thrown.
 */
internal class WindowsShortcutIdentity(
    private val registry: WindowsRegistry,
    private val knownFolders: WindowsKnownFolders,
    private val shortcuts: ShortcutAumidStore,
    private val installKind: InstallKind,
    private val launcher: String?,
    private val aumid: String,
    private val fileExists: (String) -> Boolean,
) {
    fun apply(): WindowsIdentityReport {
        if (installKind == InstallKind.DEV) {
            return WindowsIdentityReport(WindowsIdentityStep.SKIPPED, WindowsIdentityStep.SKIPPED)
        }
        val registration = writeRegistration()
        val shortcut = if (installKind == InstallKind.MSI) stampShortcut() else WindowsIdentityStep.SKIPPED
        return WindowsIdentityReport(registration, shortcut)
    }

    /** `HKCU` only — the registration never needs a machine key or elevation. */
    private fun writeRegistration(): WindowsIdentityStep =
        runCatching {
            registry.writeString(registryKey, "DisplayName", DISPLAY_NAME)
            iconUri()?.let { registry.writeString(registryKey, "IconUri", it) }
        }.fold({ WindowsIdentityStep.APPLIED }, { WindowsIdentityStep.FAILED })

    /** `<launcher dir>\Neutrodyne.ico`, else the launcher's own embedded icon. */
    private fun iconUri(): String? {
        val exe = launcher ?: return null
        val icon = exe.substringBeforeLast('\\', "") + "\\" + ICON_FILE
        return if (fileExists(icon)) icon else exe
    }

    /**
     * The MSI's own shortcut: `<FOLDERID_Programs>\Neutrodyne\Neutrodyne.lnk` — jpackage's
     * menu group and launcher name. Only that exact file is touched; an installer that
     * created nothing is an anomaly (FAILED), not a reason to fabricate one.
     */
    private fun stampShortcut(): WindowsIdentityStep {
        val programs = knownFolders.programsFolder() ?: return WindowsIdentityStep.FAILED
        val lnk = shortcutPath(programs) ?: return WindowsIdentityStep.FAILED
        if (!fileExists(lnk)) return WindowsIdentityStep.FAILED
        return if (shortcuts.setAumid(lnk, aumid) == 0) WindowsIdentityStep.APPLIED else WindowsIdentityStep.FAILED
    }

    private val registryKey get() = "Software\\Classes\\AppUserModelId\\$aumid"

    companion object {
        const val DISPLAY_NAME = "Neutrodyne"
        const val ICON_FILE = "Neutrodyne.ico"

        /** jpackage's `<menuGroup>\<launcher>.lnk` under `FOLDERID_Programs`. */
        const val SHORTCUT_RELATIVE = "Neutrodyne\\Neutrodyne.lnk"

        /**
         * `<programsFolder>\Neutrodyne\Neutrodyne.lnk`, or `null` when the known-folder
         * answer cannot be trusted: it must be an absolute drive or UNC path without
         * `..` segments, so the stamp can never land outside the reported folder.
         */
        fun shortcutPath(programsFolder: String): String? {
            val root = programsFolder.trim().trimEnd('\\')
            if (root.isEmpty()) return null
            val absolute = (root.length > 2 && root[1] == ':' && root[2] == '\\') || root.startsWith("\\\\")
            if (!absolute || root.split('\\').any { it == ".." }) return null
            return "$root\\$SHORTCUT_RELATIVE"
        }
    }
}

/** The outcome of one identity step; a failure is reported, never reported as success. */
internal enum class WindowsIdentityStep { APPLIED, SKIPPED, FAILED }

/** Per-step results of [WindowsShortcutIdentity.apply]. */
internal data class WindowsIdentityReport(
    val registry: WindowsIdentityStep,
    val shortcut: WindowsIdentityStep,
)

/** The native shortcut stamp behind [WindowsShortcutIdentity] (test seam). */
internal fun interface ShortcutAumidStore {
    /** `0` applied, `<0` the native failure code (`HRESULT` or `ND_E_*`). */
    fun setAumid(
        lnkPath: String,
        aumid: String,
    ): Int
}

/**
 * The initializer of 11's band 0–99: packaged Windows builds only. A dev run
 * (`InstallKind.DEV`, no `jpackage.app-path`) skips it entirely — the debug process
 * identity needs no shortcut or registration. Runs after `UrlSchemeRegistrar` (50).
 */
@ContributesIntoSet(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class WindowsShortcutIdentityInitializer(
    private val buildInfo: BuildInfo,
    private val ndmedia: NdmediaDirectory,
) : AppInitializer {
    override val order: Int = ORDER

    override suspend fun run() {
        val desktop = buildInfo.desktop ?: return
        if (desktop.os != DesktopOs.WINDOWS) return
        if (desktop.installKind == InstallKind.DEV) {
            Log.i(TAG) { "dev run; Windows shortcut identity skipped" }
            return
        }
        val aumid = WindowsAppIdentity.appUserModelId(buildInfo.debug)
        val report =
            WindowsShortcutIdentity(
                registry = JnaWindowsRegistry,
                knownFolders = JnaWindowsKnownFolders,
                shortcuts = shortcutStore(),
                installKind = desktop.installKind,
                launcher = System.getProperty(JPACKAGE_APP_PATH),
                aumid = aumid,
                fileExists = { Files.exists(Path.of(it)) },
            ).apply()
        when (report.registry) {
            WindowsIdentityStep.APPLIED -> {
                Log.i(TAG) { "AppUserModelId registration written for $aumid" }
            }

            WindowsIdentityStep.FAILED -> {
                Log.w(TAG) { "HKCU AppUserModelId registration failed; flyout/toasts may show the exe name" }
            }

            WindowsIdentityStep.SKIPPED -> {
                Unit
            }
        }
        when (report.shortcut) {
            WindowsIdentityStep.APPLIED -> {
                Log.i(TAG) { "Start-menu shortcut stamped with $aumid" }
            }

            WindowsIdentityStep.FAILED -> {
                Log.w(TAG) { "Start-menu shortcut stamp failed; taskbar/toast identity may fall back" }
            }

            WindowsIdentityStep.SKIPPED -> {
                Log.i(TAG) { "${desktop.installKind.wire} install: no Start-menu shortcut to stamp" }
            }
        }
    }

    /** The `ndmedia` stamp, or a store that reports failure when the exports are absent. */
    private fun shortcutStore(): ShortcutAumidStore {
        val identity =
            runCatching { NativeWindowsIdentity.open(ndmedia.path) }
                .onFailure { Log.w(TAG, it) { "shortcut stamp unavailable: ndmedia not loaded" } }
                .getOrNull()
                ?: return failingStore()
        if (!identity.supported) {
            Log.w(TAG) { "ndmedia lacks the Windows identity exports; run :playback:native:buildNdmedia" }
            identity.close()
            return failingStore()
        }
        return ShortcutAumidStore { lnk, aumid -> identity.setShortcutAumid(lnk, aumid) }
    }

    private fun failingStore() = ShortcutAumidStore { _, _ -> NativeWindowsIdentity.ND_UNSUPPORTED }

    companion object {
        const val ORDER = 60
        private const val TAG = "WinIdentity"
        private const val JPACKAGE_APP_PATH = "jpackage.app-path"
    }
}

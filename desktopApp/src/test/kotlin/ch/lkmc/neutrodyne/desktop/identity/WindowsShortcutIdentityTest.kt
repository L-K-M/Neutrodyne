// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.identity

import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.desktop.system.WindowsKnownFolders
import ch.lkmc.neutrodyne.desktop.system.WindowsRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 11 Windows MSI and ZIP off Windows, against the seam seams: the HKCU
 * `AppUserModelId` registration lands on every packaged kind; the `System.AppUserModel.ID`
 * stamp only on the MSI's per-user Start-menu shortcut, under bounds that never touch an
 * unrelated file (the nightly windows-2025 job exercises the real COM chain).
 */
class WindowsShortcutIdentityTest {
    private val programs = """C:\Users\ann\AppData\Roaming\Microsoft\Windows\Start Menu\Programs"""
    private val shortcut = """$programs\Neutrodyne\Neutrodyne.lnk"""
    private val launcher = """D:\Neutrodyne-App\Neutrodyne.exe"""
    private val aumid = "ch.lkmc.neutrodyne"
    private val key = "Software\\Classes\\AppUserModelId\\$aumid"

    private fun service(
        installKind: InstallKind = InstallKind.MSI,
        registry: MapRegistry = MapRegistry(),
        folders: WindowsKnownFolders = WindowsKnownFolders { programs },
        store: ShortcutAumidStore = ShortcutAumidStore { _, _ -> 0 },
        launcher: String? = this.launcher,
        exists: (String) -> Boolean = { true },
    ): Pair<WindowsShortcutIdentity, MapRegistry> =
        WindowsShortcutIdentity(registry, folders, store, installKind, launcher, aumid, exists) to registry

    @Test
    fun `msi stamps the start-menu shortcut and writes the registration`() {
        val stamped = mutableListOf<Pair<String, String>>()
        val (identity, registry) =
            service(
                store =
                    ShortcutAumidStore { lnk, id ->
                        stamped += lnk to id
                        0
                    },
            )

        val report = identity.apply()

        assertThat(report.registry).isEqualTo(WindowsIdentityStep.APPLIED)
        assertThat(report.shortcut).isEqualTo(WindowsIdentityStep.APPLIED)
        assertThat(stamped).containsExactly(shortcut to aumid)
        assertThat(registry.values[key to "DisplayName"]).isEqualTo("Neutrodyne")
        assertThat(registry.values[key to "IconUri"]).isEqualTo("""D:\Neutrodyne-App\Neutrodyne.ico""")
    }

    @Test
    fun `zip writes the registration but never touches a shortcut`() {
        var storeCalled = false
        val (identity, registry) =
            service(
                installKind = InstallKind.ZIP,
                store =
                    ShortcutAumidStore { _, _ ->
                        storeCalled = true
                        0
                    },
            )

        val report = identity.apply()

        assertThat(report.registry).isEqualTo(WindowsIdentityStep.APPLIED)
        assertThat(report.shortcut).isEqualTo(WindowsIdentityStep.SKIPPED)
        assertThat(storeCalled).isFalse()
        assertThat(registry.values[key to "DisplayName"]).isEqualTo("Neutrodyne")
    }

    @Test
    fun `a dev run does nothing`() {
        var storeCalled = false
        val (identity, registry) =
            service(
                installKind = InstallKind.DEV,
                store =
                    ShortcutAumidStore { _, _ ->
                        storeCalled = true
                        0
                    },
            )

        val report = identity.apply()

        assertThat(report.registry).isEqualTo(WindowsIdentityStep.SKIPPED)
        assertThat(report.shortcut).isEqualTo(WindowsIdentityStep.SKIPPED)
        assertThat(storeCalled).isFalse()
        assertThat(registry.values).isEmpty()
    }

    @Test
    fun `a missing shortcut reports failure but the registration still lands`() {
        val (identity, registry) = service(exists = { it != shortcut })

        val report = identity.apply()

        assertThat(report.shortcut).isEqualTo(WindowsIdentityStep.FAILED)
        assertThat(report.registry).isEqualTo(WindowsIdentityStep.APPLIED)
    }

    @Test
    fun `an unresolvable programs folder reports failure`() {
        val (identity, _) = service(folders = WindowsKnownFolders { null })

        assertThat(identity.apply().shortcut).isEqualTo(WindowsIdentityStep.FAILED)
    }

    @Test
    fun `a malformed known-folder answer is rejected before any file is touched`() {
        var storeCalled = false
        val (identity, _) =
            service(
                folders = WindowsKnownFolders { """Start Menu\Programs""" },
                store =
                    ShortcutAumidStore { _, _ ->
                        storeCalled = true
                        0
                    },
            )

        assertThat(identity.apply().shortcut).isEqualTo(WindowsIdentityStep.FAILED)
        assertThat(storeCalled).isFalse()
    }

    @Test
    fun `a known folder with traversal segments is rejected`() {
        val (identity, _) = service(folders = WindowsKnownFolders { """C:\..\evil""" })

        assertThat(identity.apply().shortcut).isEqualTo(WindowsIdentityStep.FAILED)
    }

    @Test
    fun `a native stamp failure is reported`() {
        val (identity, _) = service(store = ShortcutAumidStore { _, _ -> -1 })

        assertThat(identity.apply().shortcut).isEqualTo(WindowsIdentityStep.FAILED)
    }

    @Test
    fun `the icon falls back to the launcher when the ico is absent`() {
        val (identity, registry) = service(exists = { it == shortcut })

        identity.apply()

        assertThat(registry.values[key to "IconUri"]).isEqualTo(launcher)
    }

    @Test
    fun `a registry failure is reported without hiding the shortcut step`() {
        val throwing =
            object : MapRegistry() {
                override fun writeString(
                    keyPath: String,
                    valueName: String,
                    value: String,
                ): Unit = error("HKCU write failed")
            }
        val (identity, _) = service(registry = throwing)

        val report = identity.apply()

        assertThat(report.registry).isEqualTo(WindowsIdentityStep.FAILED)
        assertThat(report.shortcut).isEqualTo(WindowsIdentityStep.APPLIED)
    }

    @Test
    fun `the shortcut path stays inside the programs folder`() {
        assertThat(WindowsShortcutIdentity.shortcutPath(programs))
            .isEqualTo(shortcut)
        assertThat(WindowsShortcutIdentity.shortcutPath(programs + "\\"))
            .isEqualTo(shortcut)
        assertThat(WindowsShortcutIdentity.shortcutPath("""\\server\share"""))
            .isEqualTo("""\\server\share\Neutrodyne\Neutrodyne.lnk""")
        assertThat(WindowsShortcutIdentity.shortcutPath("")).isNull()
        assertThat(WindowsShortcutIdentity.shortcutPath("relative")).isNull()
        assertThat(WindowsShortcutIdentity.shortcutPath("""C:\..""")).isNull()
        assertThat(WindowsShortcutIdentity.shortcutPath("""C:\x\..\y""")).isNull()
    }

    private open class MapRegistry : WindowsRegistry {
        val values = mutableMapOf<Pair<String, String>, String>()

        override fun readString(
            keyPath: String,
            valueName: String,
        ): String? = values[keyPath to valueName]

        override fun readBinary(
            keyPath: String,
            valueName: String,
        ): ByteArray? = null

        override fun writeString(
            keyPath: String,
            valueName: String,
            value: String,
        ) {
            values[keyPath to valueName] = value
        }

        override fun deleteValue(
            keyPath: String,
            valueName: String,
        ): Boolean = values.remove(keyPath to valueName) != null
    }
}

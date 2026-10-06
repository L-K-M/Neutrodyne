// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Resolves the app's per-user directories from the environment — never from the install location
 * (11 AppDirs, R8.11). Everything is computed without touching the disk; [ensureCreated] is the
 * only call that writes, and it is called once at start-up.
 *
 * Per-OS layout (the table of 11):
 * - **Windows:** `%LOCALAPPDATA%\Neutrodyne\` for data and config, `Cache\` and `Logs\` under it.
 *   `%LOCALAPPDATA%` comes from the environment; when unset or relative, `FOLDERID_LocalAppData`
 *   is resolved through JNA (`SHGetKnownFolderPath`). Never `%APPDATA%` — roaming profiles would
 *   copy a large database between machines.
 * - **macOS:** `~/Library/Application Support/ch.lkmc.neutrodyne/`, `~/Library/Caches/…`,
 *   `~/Library/Logs/Neutrodyne/` — fixed names under `user.home`, the data dir named by the
 *   frozen bundle ID.
 * - **Linux:** XDG base dirs — an `XDG_*` variable is used only when it is an absolute path,
 *   otherwise the documented `~/…` default applies.
 *
 * `logs` lives in the `state` directory's `logs/` child everywhere: the "state and logs" row of the
 * table names the state dir, whose contents per the table are `instance.lock`, `instance.port`,
 * `instance.token`, `session.json`, `crash-*.txt`, `logs/`.
 */
data class AppDirs(
    val data: Path,
    val config: Path,
    val cache: Path,
    val state: Path,
    val logs: Path,
    val downloadsDefault: Path,
) {
    /** Creates every directory; on POSIX file systems each is `0700`. Safe to call repeatedly. */
    fun ensureCreated() {
        for (dir in setOf(data, config, cache, state, logs)) {
            Files.createDirectories(dir)
            try {
                Files.setPosixFilePermissions(dir, POSIX_USER_ONLY)
            } catch (e: UnsupportedOperationException) {
                // Not a POSIX file system (Windows ACLs come from %LOCALAPPDATA% itself).
            }
        }
    }

    /**
     * Which desktop OS to resolve for. Nested in [AppDirs] because `:core:common` may not depend on
     * `:core:model`'s `DesktopOs` (01 module rules); values and names mirror it.
     */
    enum class DesktopOs {
        WINDOWS,
        MACOS,
        LINUX,
        ;

        companion object {
            /** Maps `os.name` onto the matrix; anything unrecognised resolves as Linux. */
            fun current(osName: String = System.getProperty("os.name")): DesktopOs {
                val lower = osName.lowercase()
                return when {
                    "win" in lower -> WINDOWS
                    "mac" in lower || "darwin" in lower -> MACOS
                    else -> LINUX
                }
            }
        }
    }

    /**
     * How the Windows known-folder lookup is performed. The default calls
     * `SHGetKnownFolderPath(FOLDERID_LocalAppData)` through JNA; tests inject a value so the
     * `%LOCALAPPDATA%`-unset branch is exercised off Windows.
     */
    fun interface WindowsLocalAppData {
        /** Returns the absolute LocalAppData path, or `null` when the OS lookup fails. */
        fun resolve(): Path?
    }

    companion object {
        private const val APP_DIR_NAME = "Neutrodyne"
        private const val LINUX_DIR_NAME = "neutrodyne"
        private const val MACOS_DIR_NAME = "ch.lkmc.neutrodyne"
        private const val LOCAL_APP_DATA = "LOCALAPPDATA"
        private const val LOGS_DIR_NAME = "logs"
        private const val DOWNLOADS_DIR_NAME = "Downloads"

        private val POSIX_USER_ONLY = PosixFilePermissions.fromString("rwx------")
        private val WINDOWS_ABSOLUTE = Regex("""^([A-Za-z]:[\\/]|\\\\)""")

        /** The real directories of this process — what `:desktopApp` calls at start-up. */
        fun current(): AppDirs = resolve(DesktopOs.current())

        fun resolve(
            os: DesktopOs,
            env: Map<String, String> = System.getenv(),
            home: Path = Path.of(System.getProperty("user.home")),
            windowsLocalAppData: WindowsLocalAppData = WindowsLocalAppData { knownFolderLocalAppData() },
        ): AppDirs =
            when (os) {
                DesktopOs.WINDOWS -> resolveWindows(env, home, windowsLocalAppData)
                DesktopOs.MACOS -> resolveMacOs(home)
                DesktopOs.LINUX -> resolveLinux(env, home)
            }

        private fun resolveWindows(
            env: Map<String, String>,
            home: Path,
            windowsLocalAppData: WindowsLocalAppData,
        ): AppDirs {
            val localAppData =
                env[LOCAL_APP_DATA]
                    ?.takeIf { WINDOWS_ABSOLUTE.containsMatchIn(it) }
                    ?.let(Path::of)
                    ?: windowsLocalAppData.resolve()
                    ?: error("$LOCAL_APP_DATA is unset or relative and the Windows known-folder lookup failed")
            val root = localAppData.resolve(APP_DIR_NAME)
            val state = root.resolve("Logs")
            return AppDirs(
                data = root,
                config = root,
                cache = root.resolve("Cache"),
                state = state,
                logs = state.resolve(LOGS_DIR_NAME),
                downloadsDefault = root.resolve(DOWNLOADS_DIR_NAME),
            )
        }

        private fun resolveMacOs(home: Path): AppDirs {
            val library = home.resolve("Library")
            val root = library.resolve("Application Support").resolve(MACOS_DIR_NAME)
            val state = library.resolve("Logs").resolve(APP_DIR_NAME)
            return AppDirs(
                data = root,
                config = root,
                cache = library.resolve("Caches").resolve(MACOS_DIR_NAME),
                state = state,
                logs = state.resolve(LOGS_DIR_NAME),
                downloadsDefault = root.resolve(DOWNLOADS_DIR_NAME),
            )
        }

        private fun resolveLinux(
            env: Map<String, String>,
            home: Path,
        ): AppDirs {
            fun xdg(
                variable: String,
                defaultUnderHome: String,
            ): Path {
                val value = env[variable]
                return if (value != null && value.startsWith('/')) {
                    Path.of(value).resolve(LINUX_DIR_NAME)
                } else {
                    home.resolve(defaultUnderHome).resolve(LINUX_DIR_NAME)
                }
            }

            val data = xdg("XDG_DATA_HOME", ".local/share")
            val state = xdg("XDG_STATE_HOME", ".local/state")
            return AppDirs(
                data = data,
                config = xdg("XDG_CONFIG_HOME", ".config"),
                cache = xdg("XDG_CACHE_HOME", ".cache"),
                state = state,
                logs = state.resolve(LOGS_DIR_NAME),
                downloadsDefault = data.resolve(DOWNLOADS_DIR_NAME),
            )
        }

        /** `SHGetKnownFolderPath(FOLDERID_LocalAppData)` — reachable only on real Windows. */
        private fun knownFolderLocalAppData(): Path? =
            runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData) }
                .getOrNull()
                ?.takeUnless(String::isBlank)
                ?.let(Path::of)
    }
}

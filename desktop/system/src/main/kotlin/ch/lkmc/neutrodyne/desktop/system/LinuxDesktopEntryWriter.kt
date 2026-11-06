// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

/**
 * The `Exec`/`TryExec` quoting and desktop-entry writes shared by the applications-menu entry
 * and the autostart entry (11 Links and files from the OS, Start at login) so both can never
 * diverge, per the Desktop Entry specification (Exec key and value types).
 *
 * The escaping order is the spec's, applied in two passes:
 *
 * 1. **Exec:** the launcher path is always double-quoted (spaces, `(`, `)`, `~` and friends all
 *    need it); inside the quotes `"`, `` ` ``, `$` and `\` gain a backslash prefix, and a
 *    literal `%` is written `%%` — the single trailing `%U` (or `--background` in the
 *    autostart entry) is appended verbatim.
 * 2. **String escape** over the resulting field value — Exec, TryExec and Icon alike — every
 *    `\` becomes `\\`. So a literal `\` in a path is `\\` inside TryExec/Icon and `\\\\` in
 *    the quoted Exec path (quote-escape backslash plus doubled), and a literal `$` reads `\\$`
 *    in Exec. TryExec and Icon get no quoting and no `%` rewriting.
 */
object LinuxDesktopEntryWriter {
    private const val BACKGROUND_FLAG = "--background"

    /** The spec forbids control characters and `=` in an executable's path. */
    fun executablePathAllowed(path: String): Boolean =
        path.isNotEmpty() && path.none { it < ' ' || it == '=' || it == '\u007F' }

    /** The `Exec=` value: `"<escaped path>"` plus the verbatim trailing arguments. */
    fun execValue(
        launcher: String,
        args: List<String>,
    ): String {
        require(executablePathAllowed(launcher)) { "path cannot be used in a desktop entry Exec" }
        val quoted =
            buildString {
                append('"')
                for (c in launcher) {
                    when (c) {
                        '"', '`', '$', '\\' -> {
                            append('\\')
                            append(c)
                        }

                        '%' -> {
                            append("%%")
                        }

                        else -> {
                            append(c)
                        }
                    }
                }
                append('"')
                for (arg in args) {
                    append(' ')
                    append(arg)
                }
            }
        return stringEscape(quoted)
    }

    /** The `TryExec=` value: the path with only the string escape (no quoting, no `%`). */
    fun tryExecValue(launcher: String): String {
        require(executablePathAllowed(launcher)) { "path cannot be used in a desktop entry TryExec" }
        return stringEscape(launcher)
    }

    /**
     * The autostart entry of [Start at login](#start-at-login) (11): like the menu entry
     * without `MimeType`/`StartupWMClass`, `Exec` carrying `--background`, plus
     * `X-GNOME-Autostart-enabled`.
     */
    fun autostartEntry(launcher: String): String =
        buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=Neutrodyne")
            appendLine("GenericName=Podcast player")
            appendLine("Comment=Podcasts organised in groups")
            appendLine("Exec=${execValue(launcher, listOf(BACKGROUND_FLAG))}")
            appendLine("TryExec=${tryExecValue(launcher)}")
            appendLine("Icon=neutrodyne")
            appendLine("Terminal=false")
            appendLine("Categories=AudioVideo;Audio;Player;")
            appendLine("X-GNOME-Autostart-enabled=true")
        }

    /** The spec's string escape over a whole field value; only `\` is rewritten. */
    private fun stringEscape(value: String): String =
        buildString(value.length) {
            for (c in value) {
                if (c == '\\') append("\\\\") else append(c)
            }
        }

    /**
     * The per-user applications-menu entry of [Links and files from the
     * OS](#links-and-files-from-the-os) (11): Name, GenericName, Comment, Categories,
     * MimeType and StartupWMClass match the DEB/RPM package template verbatim; Exec and
     * TryExec carry this extraction's launcher, Icon the absolute path of the per-user
     * icon the installer copied. Re-running after the archive moved rewrites the same
     * values — stale paths are refreshed, never duplicated.
     */
    fun menuEntry(
        launcher: String,
        iconPath: String,
    ): String =
        buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=Neutrodyne")
            appendLine("GenericName=Podcast player")
            appendLine("Comment=Podcasts organised in groups")
            appendLine("Exec=${execValue(launcher, listOf(ARGS_FIELD))}")
            appendLine("TryExec=${tryExecValue(launcher)}")
            appendLine("Icon=${stringEscape(iconPath)}")
            appendLine("Terminal=false")
            appendLine("Categories=AudioVideo;Audio;Player;")
            appendLine("MimeType=$MIME_TYPES")
            appendLine("StartupWMClass=$STARTUP_WM_CLASS")
        }

    /** The package template's `MimeType` list, frozen with the entry name (D61). */
    const val MIME_TYPES =
        "text/x-opml;x-scheme-handler/neutrodyne;x-scheme-handler/feed;" +
            "x-scheme-handler/podcast;x-scheme-handler/pcast;x-scheme-handler/itpc;"

    /** AWT's default WM_CLASS, derived from the main class (11; Unverified exact string, S13). */
    const val STARTUP_WM_CLASS = "ch-lkmc-neutrodyne-desktop-MainKt"

    /** The frozen entry id and file name (D61). */
    const val ENTRY_ID = "ch.lkmc.neutrodyne"

    const val ENTRY_FILE_NAME = "$ENTRY_ID.desktop"

    private const val ARGS_FIELD = "%U"
}

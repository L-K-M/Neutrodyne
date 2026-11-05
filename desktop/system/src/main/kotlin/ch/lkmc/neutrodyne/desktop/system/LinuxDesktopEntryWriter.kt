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

                        '%' -> append("%%")
                        else -> append(c)
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
    private fun stringEscape(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c == '\\') append("\\\\") else append(c)
        }
    }
}

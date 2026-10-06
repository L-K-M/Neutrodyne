// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import kotlinx.serialization.json.Json

/** One JSON configuration for the shell's one-line wire formats (hand-off, session file). */
internal val SHELL_JSON: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

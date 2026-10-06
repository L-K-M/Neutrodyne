// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * `session.json` (11 Crash files and the email dialog): written with `cleanExit = false` while the
 * process runs and `cleanExit = true` on a clean shutdown, so the next start can tell a crash (or
 * kill) from a quit. Never trusted for anything but that distinction; content is diagnostics.
 */
@Serializable
data class SessionState(
    val v: Int = PROTOCOL_VERSION,
    val pid: Long,
    val startedAtMs: Long,
    val versionName: String,
    val cleanExit: Boolean,
) {
    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

/** Reads and atomically writes the session file in [stateDir]. */
internal object SessionFile {
    private const val FILE_NAME = "session.json"

    fun read(stateDir: Path): SessionState? {
        val file = stateDir.resolve(FILE_NAME)
        if (!Files.exists(file)) return null
        return runCatching { SHELL_JSON.decodeFromString<SessionState>(Files.readString(file)) }
            .getOrNull()
    }

    fun write(
        stateDir: Path,
        state: SessionState,
    ) {
        Files.createDirectories(stateDir)
        AtomicWrites.write(stateDir.resolve(FILE_NAME), SHELL_JSON.encodeToString(SessionState.serializer(), state))
    }
}

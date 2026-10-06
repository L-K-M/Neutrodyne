// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Whole-file atomic writes for the shell's small state files (`instance.port`, `instance.token`,
 * `session.json`): write a temp file in the same directory, then rename (11 Single instance and
 * handshake). [userOnly] sets mode `0600` on POSIX file systems; on Windows the files inherit the
 * user-only ACL of `%LOCALAPPDATA%`.
 */
internal object AtomicWrites {
    fun write(path: Path, content: ByteArray, userOnly: Boolean = false) {
        val temp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            if (userOnly) {
                try {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
                } catch (_: UnsupportedOperationException) {
                    // Not a POSIX file system (Windows): the directory's ACL applies.
                }
            }
            Files.write(temp, content)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun write(path: Path, content: String, userOnly: Boolean = false) {
        write(path, content.toByteArray(Charsets.UTF_8), userOnly)
    }
}

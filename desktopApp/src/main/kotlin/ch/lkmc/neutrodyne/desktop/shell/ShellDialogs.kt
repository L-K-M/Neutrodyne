// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * 11 Shell failure modes' dialogs (M0b): plain AWT `JOptionPane`s on the event thread, their
 * texts as constants — the shell shows them before any Compose content exists. A headless
 * environment (CI, a broken display) falls back to stderr, so the exit code still tells the
 * story.
 */
internal object ShellDialogs {
    /** The dialog title; also the application name AWT shows in its own dialogs. */
    const val APP_TITLE = "Neutrodyne"

    /** 11 Single instance, Retry row: the owner never answers after [InstanceHandshake]'s retries. */
    const val OWNER_UNREACHABLE =
        "Neutrodyne is already running but is not responding. Wait a moment and try again, " +
            "or end it in Task Manager / Activity Monitor / your system monitor."

    /** 11 Shell failure modes: a data directory cannot be created or written. */
    const val DATA_DIR_FAILURE_PREFIX = "Neutrodyne cannot create or write its directory"

    /** Shows a modal error dialog and returns when it is dismissed; stderr when headless. */
    fun showError(message: String) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message)
            return
        }
        try {
            SwingUtilities.invokeAndWait {
                JOptionPane.showMessageDialog(null, message, APP_TITLE, JOptionPane.ERROR_MESSAGE)
            }
        } catch (e: Exception) {
            // Interrupted or AWT failed to start: stderr is better than losing the reason.
            System.err.println(message)
        }
    }
}

// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** Why "Forget server" (08 Settings › Sync) did not run; nothing was cleared. */
sealed interface ForgetServerError {
    /**
     * The profile the action named is gone or was replaced — the stored state is not a
     * `Reconnect`/`Revoked` link, or a relink, unlink or another forget won the race.
     */
    data object StaleLink : ForgetServerError

    /** The atomic settings write failed; both stored values remain. */
    data object WriteFailed : ForgetServerError
}

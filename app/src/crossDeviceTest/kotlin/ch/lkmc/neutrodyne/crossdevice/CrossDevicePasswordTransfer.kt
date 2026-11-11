// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.crossdevice

/**
 * The driver side of `SyncController.submitPassword`.
 *
 * The controller launches its login asynchronously and clears the `CharArray` it receives in
 * that coroutine. The driver therefore transfers an independent copy through [array] and keeps
 * its own password for local cleanup; the transfer is never erased here while the controller may
 * still read it. [launched] clears only the driver-owned password, so local cleanup cannot
 * corrupt what the controller still consumes. [close] clears both and is used when no coroutine
 * will (a synchronous rejection, or the driver stops before the controller consumes the copy).
 */
class CrossDevicePasswordTransfer(
    password: CharArray,
) : AutoCloseable {
    private val transfer: CharArray = password.copyOf()
    private val driverPassword: CharArray = password

    /** The independent copy to pass to `submitPassword`; the controller owns it once passed. */
    val array: CharArray
        get() = transfer

    /**
     * The controller accepted the password for an asynchronous login: it owns the transfer and
     * will clear it. Only the driver-owned password is cleared here.
     */
    fun launched() {
        driverPassword.fill('\u0000')
    }

    /** Clears the transfer and the driver-owned password; used only when no coroutine will. */
    override fun close() {
        transfer.fill('\u0000')
        driverPassword.fill('\u0000')
    }
}

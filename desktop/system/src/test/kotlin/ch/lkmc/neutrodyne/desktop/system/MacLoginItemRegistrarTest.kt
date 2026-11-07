// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.playback.native.NativeLoginItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [MacLoginItemRegistrar] over the [MacLoginItemNative] seam (11 Start at login): the
 * SMAppServiceStatus mapping, the `/AppTranslocation/` refusal and the dev-run UNSUPPORTED
 * state. The real SMAppService behaviour with an ad-hoc-signed app stays a manual check.
 */
class MacLoginItemRegistrarTest {
    @Test
    fun statusMapsTheSMAppServiceStatusEnum() {
        assertThat(registrar("//Applications/Neutrodyne.app", NativeLoginItem.STATUS_NOT_REGISTERED).status())
            .isEqualTo(LoginItemState.NOT_REGISTERED)
        assertThat(registrar("//Applications/Neutrodyne.app", NativeLoginItem.STATUS_ENABLED).status())
            .isEqualTo(LoginItemState.REGISTERED)
        assertThat(registrar("//Applications/Neutrodyne.app", NativeLoginItem.STATUS_REQUIRES_APPROVAL).status())
            .isEqualTo(LoginItemState.REQUIRES_APPROVAL)
        assertThat(registrar("//Applications/Neutrodyne.app", NativeLoginItem.STATUS_NOT_FOUND).status())
            .isEqualTo(LoginItemState.UNSUPPORTED)
    }

    @Test
    fun aTranslocatedBundleReportsTheMoveHintAndRefusesToRegister() {
        val native = RecordingNative()
        val registrar = MacLoginItemRegistrar(native, "/AppTranslocation/Neutrodyne.app/Contents/MacOS/neutrodyne")

        assertThat(registrar.status()).isEqualTo(LoginItemState.TRANSLOCATED)
        assertThat(runCatching { registrar.register() }.exceptionOrNull())
            .isInstanceOf(LoginItemException::class.java)
        // The refusal happens before the native call: a translocated bundle never
        // reaches SMAppService.
        assertThat(native.calls).isEmpty()
    }

    @Test
    fun aDevRunWithoutALauncherIsUnsupported() {
        assertThat(MacLoginItemRegistrar(RecordingNative(), null).status()).isEqualTo(LoginItemState.UNSUPPORTED)
        assertThat(runCatching { MacLoginItemRegistrar(RecordingNative(), null).register() }.exceptionOrNull())
            .isInstanceOf(LoginItemException::class.java)
    }

    @Test
    fun registerAndUnregisterCallTheNativeShim() {
        val native = RecordingNative()
        val registrar = MacLoginItemRegistrar(native, "/Applications/Neutrodyne.app/Contents/MacOS/neutrodyne")

        registrar.register()
        registrar.unregister()

        assertThat(native.calls)
            .containsExactly(NativeLoginItem.REGISTER, NativeLoginItem.UNREGISTER)
            .inOrder()
    }

    @Test
    fun aNegativeNativeResultIsARefusedOperation() {
        val native = RecordingNative(result = -4)
        val registrar = MacLoginItemRegistrar(native, "/Applications/Neutrodyne.app/Contents/MacOS/neutrodyne")

        assertThat(runCatching { registrar.register() }.exceptionOrNull())
            .isInstanceOf(LoginItemException::class.java)
        assertThat(runCatching { registrar.unregister() }.exceptionOrNull())
            .isInstanceOf(LoginItemException::class.java)
    }

    private fun registrar(
        launcher: String?,
        status: Int,
    ): MacLoginItemRegistrar = MacLoginItemRegistrar(RecordingNative(status = status), launcher)

    private class RecordingNative(
        private val result: Int = 0,
        private val status: Int = NativeLoginItem.STATUS_NOT_REGISTERED,
    ) : MacLoginItemNative {
        val calls = mutableListOf<Int>()

        override fun call(op: Int): Int {
            calls += op
            return if (op == NativeLoginItem.STATUS) status else result
        }
    }
}

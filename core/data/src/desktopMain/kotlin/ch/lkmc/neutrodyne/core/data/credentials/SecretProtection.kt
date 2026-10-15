// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * The whole-file protection of the desktop secrets file (11 Secrets, PO-44): Windows wraps
 * `secrets.bin` in current-user DPAPI; macOS and Linux write `secrets.json` unencrypted — the
 * `0600` file mode inside the `0700` data directory is the entire mechanism there (disclosed in
 * the help). [forOs] picks the implementation; tests substitute fakes through the seam, like
 * `CipherProvider` on Android.
 */
internal interface SecretProtection {
    /**
     * The file name inside the data directory — the format pairs name and protection, so the
     * seam carries both: DPAPI output is not JSON, and the name a user is told to look at should
     * say which it is.
     */
    val fileName: String

    fun protect(plain: ByteArray): ByteArray

    fun unprotect(protected: ByteArray): ByteArray

    companion object {
        /** The protection 11 assigns to [os]. */
        fun forOs(os: AppDirs.DesktopOs): SecretProtection =
            when (os) {
                AppDirs.DesktopOs.WINDOWS -> DpapiSecretProtection()

                AppDirs.DesktopOs.MACOS,
                AppDirs.DesktopOs.LINUX,
                -> IdentitySecretProtection
            }
    }
}

/** The macOS/Linux choice: the file bytes are the version-1 JSON, guarded by `0600` alone. */
internal object IdentitySecretProtection : SecretProtection {
    override val fileName: String = SECRETS_JSON

    override fun protect(plain: ByteArray): ByteArray = plain

    override fun unprotect(protected: ByteArray): ByteArray = protected
}

/**
 * Windows current-user DPAPI through JNA (11 Secrets): `CryptProtectData`/`CryptUnprotectData`
 * with `CRYPTPROTECT_UI_FORBIDDEN` — a UI prompt from a background store would be a hang, not a
 * safeguard — and the constant entropy `ch.lkmc.neutrodyne/secrets/v1`. `CRYPTPROTECT_LOCAL_MACHINE`
 * is deliberately absent: another account on the same machine must not unwrap the file. Failures
 * surface as JNA `Win32Exception`s, which `DesktopSecretsFile` reports as key loss.
 */
internal class DpapiSecretProtection : SecretProtection {
    override val fileName: String = SECRETS_BIN

    override fun protect(plain: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null)

    override fun unprotect(protected: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(protected, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)

    private companion object {
        val ENTROPY: ByteArray = "ch.lkmc.neutrodyne/secrets/v1".encodeToByteArray()
    }
}

/** The production [SecretProtection] — [SecretProtection.forOs] on the running OS. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<SecretProtection>())
@Inject
internal class PlatformSecretProtection : SecretProtection {
    private val delegate = SecretProtection.forOs(AppDirs.DesktopOs.current())

    override val fileName: String
        get() = delegate.fileName

    override fun protect(plain: ByteArray): ByteArray = delegate.protect(plain)

    override fun unprotect(protected: ByteArray): ByteArray = delegate.unprotect(protected)
}

private const val SECRETS_BIN = "secrets.bin"
private const val SECRETS_JSON = "secrets.json"

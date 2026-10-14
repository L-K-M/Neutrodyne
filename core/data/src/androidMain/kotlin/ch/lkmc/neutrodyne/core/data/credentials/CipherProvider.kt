// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The crypto seam of `KeystoreCredentialStore` (03 Basic auth and CredentialStore): production is
 * [KeystoreCipherProvider], host (Robolectric) tests substitute a software AES-GCM key — the
 * AndroidKeyStore is not usable there, so no test ever touches it.
 */
internal interface CipherProvider {
    /**
     * A cipher initialised for one encryption. The IV is cipher-generated (12 bytes for GCM) —
     * read `cipher.iv` after `init` and store it on the `credential` row; it must never be
     * reused for a second plaintext under the same key.
     */
    fun forEncryption(): Cipher

    /** A cipher initialised to decrypt with the [iv] stored on the row. */
    fun forDecryption(iv: ByteArray): Cipher
}

/**
 * Android Keystore AES-256-GCM for `credential` rows (03): alias `neutrodyne_credentials_v1`,
 * `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, no padding, 128-bit tag. The key is
 * generated on first use and never leaves the Keystore; a Keystore reset/lock loss surfaces at
 * decrypt time as `SecretLostException` up in the store (key loss → `needsCredentials`).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<CipherProvider>())
@Inject
internal class KeystoreCipherProvider : CipherProvider {
    // `by lazy` synchronises: two racing first uses must not generate two keys — the loser's
    // ciphertext would be unrecoverable once the winner overwrites the alias.
    private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreateKey() }

    override fun forEncryption(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun forDecryption(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "neutrodyne_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}

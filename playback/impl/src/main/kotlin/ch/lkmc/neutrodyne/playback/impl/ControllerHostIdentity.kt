// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import java.security.MessageDigest

/**
 * Authenticates the package names Media3 recognizes for Android Auto and AAOS before
 * [SessionCallback] grants them control. [MediaSession.isAutoCompanionController] and
 * [MediaSession.isAutomotiveController] compare package names only — the Media3 Javadoc
 * says they perform no security validation — so a self-installed package claiming a
 * recognized name passes them. A recognized name must instead be bound to the calling
 * uid and carry the genuine host credential: `FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP` on
 * an AAOS host package, a Google production signing certificate on Android Auto. Missing,
 * invisible or ambiguous identity fails closed. A controller bound to the service is
 * exempt from package visibility filtering, so no `<queries>` entry is required.
 */
internal class ControllerHostIdentity(
    private val pm: PackageManager,
    private val signing: SigningFacts = SigningFacts.forPackageManager(pm),
) {
    fun isAuthorizedAutoHost(
        session: MediaSession,
        controller: ControllerInfo,
    ): Boolean =
        when {
            session.isAutoCompanionController(controller) -> {
                boundToCallingUid(controller) && hasGenuineAutoSigner(controller.packageName)
            }

            session.isAutomotiveController(controller) -> {
                boundToCallingUid(controller) && isSystemHost(controller.packageName)
            }

            else -> {
                false
            }
        }

    private fun boundToCallingUid(controller: ControllerInfo): Boolean {
        try {
            val packages = pm.getPackagesForUid(controller.uid) ?: return false
            return controller.packageName in packages
        } catch (_: RuntimeException) {
            return false
        }
    }

    private fun isSystemHost(name: String): Boolean =
        try {
            pm.getApplicationInfo(name, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }

    private fun hasGenuineAutoSigner(name: String): Boolean {
        val current = signing.currentSha256(name) ?: return false
        // A co-signed package stays genuine only while every current signer is pinned;
        // one unknown signer would extend the exception to anything else it signs.
        if (current.size > 1 && current.any { digest -> AUTO_PINS.none(digest::contentEquals) }) return false
        return AUTO_PINS.any { signing.inLineage(name, it) }
    }

    private companion object {
        // Android Auto's release and rotated production certificates, pinned to the upstream
        // UAMP allowlist at 9498d991; its release="false" debug/DHU keys are excluded.
        val AUTO_PINS =
            listOf(
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
                "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295",
            ).map { hex ->
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
    }
}

/**
 * The platform's account of who signed a package: the one piece of host authentication a
 * Robolectric `PackageManager` cannot answer, kept behind this narrow seam for focused tests.
 */
internal interface SigningFacts {
    /** SHA-256 digests of [packageName]'s current content signers; null when unreadable. */
    fun currentSha256(packageName: String): List<ByteArray>?

    /**
     * True when [sha256] signed [packageName]: on API 28+ its whole rotation lineage via
     * `PackageManager.hasSigningCertificate`, on API 26–27 its current signers only.
     */
    fun inLineage(
        packageName: String,
        sha256: ByteArray,
    ): Boolean

    companion object {
        @Suppress("DEPRECATION")
        fun forPackageManager(pm: PackageManager): SigningFacts =
            object : SigningFacts {
                override fun currentSha256(packageName: String): List<ByteArray>? =
                    try {
                        val signers =
                            if (Build.VERSION.SDK_INT >= 28) {
                                pm
                                    .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                                    .signingInfo
                                    ?.apkContentsSigners
                            } else {
                                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                            }
                        signers?.map { it.sha256() }
                    } catch (_: Exception) {
                        null
                    }

                override fun inLineage(
                    packageName: String,
                    sha256: ByteArray,
                ): Boolean =
                    try {
                        if (Build.VERSION.SDK_INT >= 28) {
                            pm.hasSigningCertificate(
                                packageName,
                                sha256,
                                PackageManager.CERT_INPUT_SHA256,
                            )
                        } else {
                            currentSha256(packageName)?.any { it.contentEquals(sha256) } == true
                        }
                    } catch (_: RuntimeException) {
                        false
                    }

                private fun Signature.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(toByteArray())
            }
    }
}

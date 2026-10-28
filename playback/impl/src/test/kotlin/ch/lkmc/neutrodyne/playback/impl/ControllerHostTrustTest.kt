// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val AUTO_PACKAGE = "com.google.android.projection.gearhead"
internal const val CAR_MEDIA_PACKAGE = "com.android.car.media"
internal const val CAR_LAUNCHER_PACKAGE = "com.android.car.carlauncher"
internal const val HOST_UID = 12345

// Google's production certificates from the UAMP allowlist, and the two certificates its
// gearhead entry marks release="false" (the shared AOSP debug key and the DHU key), which
// must never match.
private const val PIN_RELEASE_HEX = "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf"
private const val PIN_ROTATED_HEX = "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"
private val DEBUG_DIGESTS =
    listOf(
        "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
        "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771",
    ).map { it.hexToBytes() }

internal val PIN_RELEASE = PIN_RELEASE_HEX.hexToBytes()
internal val PIN_ROTATED = PIN_ROTATED_HEX.hexToBytes()

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun namedController(
    packageName: String,
    uid: Int = HOST_UID,
): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 1, uid, 0, 0, false, Bundle.EMPTY, true)

internal fun installHostPackage(
    app: Application,
    packageName: String,
    flags: Int = 0,
    signers: Array<Signature> = arrayOf(Signature(ByteArray(32) { 7 })),
) {
    val info =
        PackageInfo().apply {
            this.packageName = packageName
            applicationInfo =
                ApplicationInfo().apply {
                    this.packageName = packageName
                    this.flags = flags
                }
            @Suppress("DEPRECATION")
            this.signatures = signers
            if (RuntimeEnvironment.getApiLevel() >= 28) {
                signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signers) }
            }
        }
    shadowOf(app.packageManager).installPackage(info)
}

internal fun bindPackagesToUid(
    app: Application,
    vararg packages: String,
) = shadowOf(app.packageManager).setPackagesForUid(HOST_UID, *packages)

/** Answers signing facts at digest granularity; no runtime path can inject it. */
internal class FakeSigningFacts(
    var current: List<ByteArray>? = listOf(ByteArray(32) { 9 }),
    var lineage: Set<ByteArray> = emptySet(),
) : SigningFacts {
    val lineageQueries = mutableListOf<ByteArray>()

    override fun currentSha256(packageName: String): List<ByteArray>? = current

    override fun inLineage(
        packageName: String,
        sha256: ByteArray,
    ): Boolean {
        lineageQueries += sha256
        return lineage.any { it.contentEquals(sha256) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ControllerHostTrustTest {
    private fun app(): Application = RuntimeEnvironment.getApplication()

    private fun withSession(block: (MediaSession) -> Unit) {
        val exo = ExoPlayer.Builder(app()).build()
        val session = MediaSession.Builder(app(), exo).build()
        try {
            block(session)
        } finally {
            session.release()
            exo.release()
        }
    }

    @Test fun automotiveSystemPackageControls() =
        withSession { session ->
            installHostPackage(app(), CAR_MEDIA_PACKAGE, ApplicationInfo.FLAG_SYSTEM)
            bindPackagesToUid(app(), CAR_MEDIA_PACKAGE)
            assertTrue(
                ControllerHostIdentity(app().packageManager, FakeSigningFacts())
                    .isAuthorizedAutoHost(session, namedController(CAR_MEDIA_PACKAGE)),
            )
        }

    @Test fun automotiveUpdatedSystemPackageControls() =
        withSession { session ->
            installHostPackage(app(), CAR_LAUNCHER_PACKAGE, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
            bindPackagesToUid(app(), CAR_LAUNCHER_PACKAGE)
            assertTrue(
                ControllerHostIdentity(app().packageManager, FakeSigningFacts())
                    .isAuthorizedAutoHost(session, namedController(CAR_LAUNCHER_PACKAGE)),
            )
        }

    @Test fun ordinaryPackagesWithRecognizedNamesAreDenied() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            installHostPackage(app(), CAR_MEDIA_PACKAGE)
            installHostPackage(app(), CAR_LAUNCHER_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE, CAR_MEDIA_PACKAGE, CAR_LAUNCHER_PACKAGE)
            val identity = ControllerHostIdentity(app().packageManager, FakeSigningFacts())
            for (name in listOf(AUTO_PACKAGE, CAR_MEDIA_PACKAGE, CAR_LAUNCHER_PACKAGE)) {
                assertFalse(identity.isAuthorizedAutoHost(session, namedController(name)), name)
            }
        }

    @Test fun recognizedNameNotBoundToCallingUidIsDenied() =
        withSession { session ->
            // The package exists and would carry credentials, but the calling uid owns none of it.
            installHostPackage(app(), CAR_MEDIA_PACKAGE, ApplicationInfo.FLAG_SYSTEM)
            bindPackagesToUid(app(), "unrelated.pkg")
            val identity = ControllerHostIdentity(app().packageManager, FakeSigningFacts(lineage = setOf(PIN_RELEASE)))
            assertFalse(identity.isAuthorizedAutoHost(session, namedController(CAR_MEDIA_PACKAGE)))
        }

    @Test fun boundButUninstalledRecognizedNameIsDenied() =
        withSession { session ->
            bindPackagesToUid(app(), CAR_MEDIA_PACKAGE)
            val identity = ControllerHostIdentity(app().packageManager, FakeSigningFacts())
            assertFalse(identity.isAuthorizedAutoHost(session, namedController(CAR_MEDIA_PACKAGE)))
        }

    @Test fun autoProductionSignerControls() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts = FakeSigningFacts(lineage = setOf(PIN_RELEASE))
            assertTrue(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun autoRotatedProductionSignerControls() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts = FakeSigningFacts(lineage = setOf(PIN_ROTATED))
            assertTrue(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun autoRotatedAwayToUnknownSignerStillControls() =
        withSession { session ->
            // Lineage rotation keeps the pinned certificate in signing history; API 28+
            // hasSigningCertificate therefore still answers true.
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts =
                FakeSigningFacts(
                    current = listOf(ByteArray(32) { 42 }),
                    lineage = setOf(PIN_RELEASE),
                )
            assertTrue(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun autoAllCurrentSignersPinnedControls() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts =
                FakeSigningFacts(
                    current = listOf(PIN_RELEASE, PIN_ROTATED),
                    lineage = setOf(PIN_ROTATED),
                )
            assertTrue(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun autoSignedByUnrelatedCertificateIsDenied() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts = FakeSigningFacts(lineage = setOf(ByteArray(32) { 55 }))
            assertFalse(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun autoDebugSignerIsDenied() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            for (debug in DEBUG_DIGESTS) {
                val facts =
                    FakeSigningFacts(
                        current = listOf(debug),
                        lineage = setOf(debug),
                    )
                assertFalse(
                    ControllerHostIdentity(app().packageManager, facts)
                        .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
                )
            }
        }

    @Test fun autoMultipleUnknownSignersAreDenied() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts =
                FakeSigningFacts(
                    current = listOf(PIN_RELEASE, ByteArray(32) { 66 }),
                    lineage = setOf(PIN_RELEASE),
                )
            assertFalse(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun missingPackageIdentityIsDenied() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            val facts = FakeSigningFacts(current = null, lineage = setOf(PIN_RELEASE))
            assertFalse(
                ControllerHostIdentity(app().packageManager, facts)
                    .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE)),
            )
        }

    @Test fun unrecognizedControllerNameIsDenied() =
        withSession { session ->
            val identity = ControllerHostIdentity(app().packageManager, FakeSigningFacts())
            assertFalse(identity.isAuthorizedAutoHost(session, namedController("untrusted.app")))
        }

    @Test fun onlyProductionPinsAreQueriedAsGenuine() =
        withSession { session ->
            installHostPackage(app(), AUTO_PACKAGE)
            bindPackagesToUid(app(), AUTO_PACKAGE)
            // Miss the release pin so the rotated one is queried too: the exact set the
            // driver accepts must be the two UAMP production certificates and nothing else.
            val facts = FakeSigningFacts(lineage = setOf(PIN_ROTATED))
            ControllerHostIdentity(app().packageManager, facts)
                .isAuthorizedAutoHost(session, namedController(AUTO_PACKAGE))
            assertEquals(
                setOf(PIN_RELEASE.toList(), PIN_ROTATED.toList()),
                facts.lineageQueries.map { it.toList() }.toSet(),
            )
        }

    @Test fun productionFactsReadCurrentSigners() =
        withSession {
            val signer = Signature(ByteArray(64) { (it * 31).toByte() })
            installHostPackage(app(), AUTO_PACKAGE, signers = arrayOf(signer))
            val facts = SigningFacts.forPackageManager(app().packageManager)
            assertEquals(
                listOf(sha256(signer.toByteArray()).toList()),
                facts.currentSha256(AUTO_PACKAGE)?.map { it.toList() },
            )
            assertNull(facts.currentSha256("missing.pkg"))
        }

    @Test fun productionFactsFailClosedWhenPlatformCannotAnswer() =
        withSession {
            // Robolectric does not implement hasSigningCertificate; the driver must turn
            // that into denial rather than a crash or a grant.
            installHostPackage(app(), AUTO_PACKAGE)
            val facts = SigningFacts.forPackageManager(app().packageManager)
            assertFalse(facts.inLineage(AUTO_PACKAGE, PIN_RELEASE))
            assertFalse(facts.inLineage("missing.pkg", PIN_RELEASE))
        }

    @Test
    @Config(sdk = [26])
    fun api26FactsCheckCurrentSignaturesOnly() =
        withSession {
            val signer = Signature(ByteArray(64) { (it * 17).toByte() })
            installHostPackage(app(), AUTO_PACKAGE, signers = arrayOf(signer))
            val facts = SigningFacts.forPackageManager(app().packageManager)
            assertEquals(
                listOf(sha256(signer.toByteArray()).toList()),
                facts.currentSha256(AUTO_PACKAGE)?.map { it.toList() },
            )
            assertTrue(facts.inLineage(AUTO_PACKAGE, sha256(signer.toByteArray())))
            assertFalse(facts.inLineage(AUTO_PACKAGE, PIN_RELEASE))
            assertNull(facts.currentSha256("missing.pkg"))
            assertFalse(facts.inLineage("missing.pkg", PIN_RELEASE))
        }
}

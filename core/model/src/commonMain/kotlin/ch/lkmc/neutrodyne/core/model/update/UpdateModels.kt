// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.update

import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import kotlinx.collections.immutable.ImmutableList

sealed interface UpdateCheckState {
    data class Disabled(
        val reason: UpdateDisabledReason,
    ) : UpdateCheckState

    data class Idle(
        val lastCheckAtMs: Long?,
    ) : UpdateCheckState

    data object Checking : UpdateCheckState

    data class Available(
        val info: UpdateInfo,
        val lastCheckAtMs: Long,
        val lastError: UpdateCheckError? = null,
    ) : UpdateCheckState

    data class Failed(
        val error: UpdateCheckError,
        val lastCheckAtMs: Long?,
    ) : UpdateCheckState
}

enum class UpdateDisabledReason { DEV_BUILD, CHECKS_OFF }

enum class UpdateCheckError { NETWORK, RATE_LIMITED, MANIFEST_INVALID }

enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT }

enum class VerificationPhase { PRE_ENFORCEMENT, POST_ENFORCEMENT }

/** Validated schema-1 contents, before platform selection. URLs are rebuilt canonical links. */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val publishedAt: String,
    val notes: String,
    val releaseUrl: String,
    val apks: ImmutableList<UpdateApk>,
    val desktop: ImmutableList<UpdateDesktopAsset>,
    val server: UpdateServerInfo?,
)

/** Card data. A missing or incompatible asset still permits opening the release page. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val publishedAt: String,
    val notes: String,
    val releaseUrl: String,
    val apk: UpdateApk?,
    val desktopSelection: DesktopAssetSelection?,
    val server: UpdateServerInfo?,
) {
    val desktopAsset: UpdateDesktopAsset?
        get() = (desktopSelection as? DesktopAssetSelection.Selected)?.asset
}

data class UpdateApk(
    val abi: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class UpdateDesktopAsset(
    val os: DesktopOs,
    val arch: DesktopArch,
    val kind: InstallKind,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val minOs: String?,
)

data class UpdateServerInfo(
    val jarFileName: String,
    val jarUrl: String,
    val jarSizeBytes: Long,
    val jarSha256: String,
    val imageDigestRef: String,
    val minJava: Int,
)

/** Compatibility is separate from parsing so the card can explain a newer-OS requirement. */
sealed interface DesktopAssetSelection {
    data class Selected(
        val asset: UpdateDesktopAsset,
    ) : DesktopAssetSelection

    data class RequiresNewerOs(
        val os: DesktopOs,
        val minOs: String,
    ) : DesktopAssetSelection

    data object Missing : DesktopAssetSelection

    data object DevelopmentBuild : DesktopAssetSelection
}

/** Runtime facts only: installed APK ABI and desktop package identity belong to BuildInfo. */
sealed interface UpdateRuntimeTarget {
    data class Android(
        val preferredAbi: String?,
        val sdkInt: Int,
    ) : UpdateRuntimeTarget

    data class Desktop(
        val osVersion: String,
    ) : UpdateRuntimeTarget
}

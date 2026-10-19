// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.testing.FakeUpdateRuntimeInputs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ch.lkmc.neutrodyne.core.model.DesktopArch as Arch
import ch.lkmc.neutrodyne.core.model.DesktopOs as Os
import ch.lkmc.neutrodyne.core.model.InstallKind as Kind
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError as CheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState as CheckState

internal const val UPDATE_REPO = "https://github.com/L-K-M/Neutrodyne"
internal val UPDATE_HASH = "a".repeat(64)
internal const val HOUR = 3_600_000L
internal const val DAY = 24 * HOUR

internal fun updateBuild(
    platform: BuildInfo.Platform = BuildInfo.Platform.ANDROID,
    debug: Boolean = false,
    versionCode: Int = 10095,
    os: Os = Os.LINUX,
    arch: Arch = Arch.X64,
    kind: Kind = Kind.DEB,
): BuildInfo =
    BuildInfo(
        versionName = "0.1.0",
        versionCode = versionCode,
        debug = debug,
        platform = platform,
        repoUrl = UPDATE_REPO,
        updateManifestUrl = "$UPDATE_REPO/releases/latest/download/neutrodyne-update.json",
        engineManifestUrl = "",
        youTubeEngineBundled = false,
        apkAbi = "armeabi-v7a",
        desktop =
            if (platform == BuildInfo.Platform.DESKTOP) {
                BuildInfo.Desktop(os, arch, kind, "bundled")
            } else {
                null
            },
        shippedLocales = persistentListOf("en"),
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

/** Independent wire fixture, not produced by the cache encoder under test. */
internal fun updateJson(
    version: String = "1.0.0",
    extra: String = "",
): String =
    """
    {"schema":1,"versionName":"$version","versionCode":${VersionScheme.versionCode(version)},
     "minSdk":26,"published":"2026-10-19T12:00:00Z","notes":"Fixes and improvements.",
     "releaseUrl":"$UPDATE_REPO/releases/tag/v$version",
     "apks":[{"abi":"arm64-v8a","file":"neutrodyne-$version-arm64-v8a.apk",
       "url":"$UPDATE_REPO/releases/download/v$version/neutrodyne-$version-arm64-v8a.apk",
       "size":123,"sha256":"$UPDATE_HASH"}]$extra}
    """.trimIndent()

internal fun desktopJson(
    os: String,
    arch: String,
    kind: String,
    minimum: String? = null,
    version: String = "1.0.0",
): String {
    val extension = if (kind == "mac-zip") "zip" else kind
    val file = "neutrodyne-$version-$os-$arch.$extension"
    val min = minimum?.let { ",\"minOs\":\"$it\"" }.orEmpty()
    return """{"os":"$os","arch":"$arch","kind":"$kind","file":"$file",
      "url":"$UPDATE_REPO/releases/download/v$version/$file","size":345,"sha256":"$UPDATE_HASH"$min}"""
}

internal fun serverJson(): String =
    """
    ,"server":{"jar":{"file":"neutrodyne-server-1.0.0.jar",
    "url":"$UPDATE_REPO/releases/download/v1.0.0/neutrodyne-server-1.0.0.jar","size":999,"sha256":"$UPDATE_HASH"},
    "image":"ghcr.io/l-k-m/neutrodyne-server@sha256:$UPDATE_HASH","minJava":21}
    """.trimIndent()

internal fun manifest(
    version: String = "1.0.0",
    extra: String = "",
): UpdateManifest =
    (
        UpdateManifestParser(updateBuild()).parse(updateJson(version, extra).encodeToByteArray()) as Outcome.Success
    ).value

internal fun changedJson(
    input: String,
    key: String,
    value: JsonElement,
): String = JsonObject(Json.parseToJsonElement(input).jsonObject + (key to value)).toString()

internal fun runtime(target: UpdateRuntimeTarget = UpdateRuntimeTarget.Android("arm64-v8a", 37)) =
    FakeUpdateRuntimeInputs(target)

internal class MemoryUpdateStore(
    var value: UpdateStoreSnapshot? = null,
) : UpdateStore {
    var readError: UpdateStoreError? = null
    var writeError: UpdateStoreError? = null
    var writes = 0

    override suspend fun read(): Outcome<UpdateStoreSnapshot?, UpdateStoreError> =
        readError?.let { Outcome.Failure(it) } ?: Outcome.Success(value)

    override suspend fun write(snapshot: UpdateStoreSnapshot): Outcome<Unit, UpdateStoreError> {
        writes++
        return writeError?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit).also { value = snapshot }
    }
}

internal class TestUpdateSource : UpdateSource {
    var calls = 0
    var result: Outcome<UpdateManifest, CheckError> = Outcome.Success(manifest())
    var action: (suspend () -> Outcome<UpdateManifest, CheckError>)? = null

    override suspend fun fetch(): Outcome<UpdateManifest, CheckError> {
        calls++
        return action?.invoke() ?: result
    }
}

internal class TestUpdateNotifications : UpdateNotificationAdapter {
    val posted = mutableListOf<UpdateInfo>()
    var cancelled = 0
    var result = UpdatePostResult.ACCEPTED
    var onPost: (suspend () -> Unit)? = null
    var beforePost: (suspend () -> Unit)? = null

    override suspend fun post(info: UpdateInfo): UpdatePostResult {
        beforePost?.invoke()
        posted += info
        onPost?.invoke()
        return result
    }

    override suspend fun cancel() {
        cancelled++
    }
}

internal class TestUpdateRunner : UpdateCheckRunner {
    lateinit var executor: UpdateCheckExecutor
    var enqueued = 0
    var cancelled = 0
    val scheduling = mutableListOf<UpdateScheduling>()
    var action: (suspend () -> CheckState)? = null

    override suspend fun setAutomaticChecks(mode: UpdateScheduling) {
        scheduling += mode
    }

    override suspend fun runNow(): CheckState {
        enqueued++
        return action?.invoke() ?: executor.execute(UpdateInvocation.MANUAL)
    }

    override suspend fun cancelManual() {
        cancelled++
    }
}

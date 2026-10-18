// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadSource
import ch.lkmc.neutrodyne.core.data.importing.RestoreEnqueue
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.JvmNetErrors
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import kotlinx.serialization.json.Json

// The androidHostTest mirror of `desktopTest/ImportTestSupport.kt` — the same note in
// AndroidTestSupport's header applies: the shared fixture builders land in `:core:testing`
// with a later milestone, so the seeding helpers duplicate here deliberately.

/** An `import_item` row; the caller fills `sessionId` (or [seedImportSession] copies it in). */
internal fun importItem(
    ordinal: Int,
    url: String,
    normalizedUrl: String? = url,
    title: String? = "Item $ordinal",
    groups: List<String> = emptyList(),
    selected: Boolean = true,
    kind: ImportItemKind = ImportItemKind.RSS,
    status: ImportItemStatus = ImportItemStatus.PREVIEW,
    podcastId: Long? = null,
    errorDetail: String? = null,
): ImportItemEntity =
    ImportItemEntity(
        sessionId = 0,
        ordinal = ordinal,
        title = title,
        originalUrl = url,
        normalizedUrl = normalizedUrl,
        kind = kind,
        groupNamesJson = Json.encodeToString(groups),
        selected = selected,
        status = status,
        podcastId = podcastId,
        errorDetail = errorDetail,
    )

/** A session row plus its items in file order; returns the new session id. */
internal suspend fun NeutrodyneDatabase.seedImportSession(
    options: ImportOptions = ImportOptions(),
    format: ImportFormat = ImportFormat.OPML,
    state: ImportState = ImportState.PREVIEW,
    createdAt: Long = 0,
    finishedAt: Long? = null,
    payloadPath: String? = null,
    items: List<ImportItemEntity> = emptyList(),
): Long {
    val sessionId =
        importDao()
            .insertSession(
                ImportSessionEntity(
                    createdAt = createdAt,
                    finishedAt = finishedAt,
                    sourceFormat = format,
                    state = state,
                    payloadPath = payloadPath,
                    optionsJson = ImportOptionsJson.encode(options),
                ),
            )
    importDao().insertItems(items.map { it.copy(sessionId = sessionId) })
    return sessionId
}

/**
 * The restore host's claim of a preview session (05's `ImportOptions.restoreRequestedAt`):
 * one write transaction that stamps the request only while the row still sits in `PREVIEW`.
 * `ImportError.SessionGone` when the row is gone or already past `PREVIEW`.
 */
internal suspend fun NeutrodyneDatabase.requestImportRestore(
    sessionId: Long,
    requestedAt: Long,
): Outcome<Unit, ImportError> =
    withWriteTransaction {
        val dao = importDao()
        val session =
            dao.sessionById(sessionId)
                ?: return@withWriteTransaction Outcome.Failure(ImportError.SessionGone)
        if (session.state != ImportState.PREVIEW) {
            return@withWriteTransaction Outcome.Failure(ImportError.SessionGone)
        }
        val options = session.optionsJson?.let(ImportOptionsJson::decode) ?: ImportOptions()
        dao.setOptionsJson(
            sessionId,
            ImportOptionsJson.encode(options.copy(restoreRequestedAt = requestedAt)),
        )
        Outcome.Success(Unit)
    }

/** Records the schedule policy, restore admission and cancellation of each host request. */
internal class FakeImportWorkScheduler : ImportWorkScheduler {
    val scheduled = mutableListOf<Pair<Long, ImportWorkPolicy>>()
    val cancelled = mutableListOf<Long>()

    /** `(sessionId, auto)` of each [scheduleRestore] call, in order. */
    val restores = mutableListOf<Pair<Long, Boolean>>()

    /** [hasRestoreWork]'s answer. */
    var restoreWork = false

    /** [scheduleRestore]'s acknowledgement. */
    var restoreAck = RestoreEnqueue.ENQUEUED

    var fail = false

    override suspend fun schedule(
        sessionId: Long,
        policy: ImportWorkPolicy,
    ) {
        if (fail) error("scheduler unavailable")
        scheduled += sessionId to policy
    }

    override suspend fun hasRestoreWork(): Boolean {
        if (fail) error("scheduler unavailable")
        return restoreWork
    }

    override suspend fun scheduleRestore(
        sessionId: Long,
        auto: Boolean,
    ): RestoreEnqueue {
        if (fail) error("scheduler unavailable")
        restores += sessionId to auto
        return restoreAck
    }

    override suspend fun cancel(sessionId: Long) {
        if (fail) error("scheduler unavailable")
        cancelled += sessionId
    }
}

/**
 * A `PayloadSource` that fails every open — the `PayloadStore` fixtures in this source set
 * only ever read cache-side files, never provider streams.
 */
internal class NoPayloadSource : PayloadSource {
    override suspend fun open(uri: String): Outcome<PayloadSource.Opened, ImportError> =
        Outcome.Failure(ImportError.Unreadable)
}

/** The connected flag a host test wants from the real JVM classifier. */
internal val testNetErrors =
    object : NetErrorClassifier {
        override fun classify(e: Throwable): NetError = JvmNetErrors.classify(e, connected = true)
    }

/**
 * The real OkHttp client family `PayloadStore` wants at construction (its `client` resolves
 * eagerly). Never used for a real fetch in these fixtures — no request leaves the process.
 */
internal fun testClients(): OkHttpNeutrodyneHttpClients {
    val userAgent = UserAgentProvider(TEST_PLATFORM, "0.1.0", "https://example.com/repo")
    return OkHttpNeutrodyneHttpClients(
        NetworkClients(
            CoreClients(
                ua = UserAgentInterceptor(userAgent),
                lanGuard = LocalNetworkGuard(LocalNetworkAccess(), TEST_PLATFORM),
                hints = DnsFamilyHints(),
                debugInterceptors = emptySet(),
            ),
            AuthInterceptor { CredentialLookup.None },
        ),
        userAgent,
    )
}

private val TEST_PLATFORM =
    object : PlatformInfo {
        override val kind = PlatformKind.ANDROID
        override val userAgentPlatform = "Android 34; test"
        override val androidSdkInt = 34
        override val regionCode = "DE"
    }

/** `BuildInfo` claiming the Android product — `AutoSnapshotProducer.supported` keys off it. */
internal fun androidBuildInfo() =
    BuildInfo(
        versionName = "0.1.0",
        versionCode = 1,
        debug = false,
        platform = BuildInfo.Platform.ANDROID,
        repoUrl = "https://example.com/repo",
        updateManifestUrl = "https://example.com/update.json",
        engineManifestUrl = "https://example.com/engine.json",
        youTubeEngineBundled = false,
        shippedLocales = kotlinx.collections.immutable.persistentListOf("en"),
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

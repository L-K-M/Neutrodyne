// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceQueries
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

internal fun mediaBytes(size: Int = 4096): ByteArray =
    ByteArray(size) { ((it * 31) % 251).toByte() }.also {
        "ID3".encodeToByteArray().copyInto(it)
    }

internal fun response(
    bytes: ByteArray = mediaBytes(),
    code: Int = 200,
    vararg headers: Pair<String, String>,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .body(Buffer().write(bytes))
        .apply {
            headers.forEach { (key, value) -> setHeader(key, value) }
        }.build()

internal class TestRoots : StorageRoots {
    var selected = "old"
    val available = mutableSetOf("old", "new")
    var failure: StorageFailure? = null
    var required = 0L
    var fileLimit: Long? = null

    override suspend fun maximumFileBytes(rootId: String) = fileLimit

    override suspend fun current() = selected

    override suspend fun list() = emptyList<StorageRootInfo>()

    override suspend fun resolve(rootId: String) = if (rootId in available) ResolvedRoot(rootId, "/$rootId") else null

    override suspend fun ensureSpace(
        rootId: String,
        requiredBytes: Long,
    ): StorageFailure? {
        required = requiredBytes
        return failure
    }

    override suspend fun classify(
        error: Throwable,
        rootId: String,
    ) = failure ?: StorageFailure.IO
}

/** Injects write/sync/rename failures underneath the real Okio-backed PartFile. */
internal class FaultFiles(
    val fake: FakeFileSystem = FakeFileSystem(),
) : ForwardingFileSystem(fake) {
    var writeFailure: IOException? = null
    var syncFailure: IOException? = null
    var moveFailure: IOException? = null
    var largestWrite = 0
    val operations = mutableListOf<String>()

    init {
        fake.emulateUnix()
        fake.createDirectories("/old".toPath())
        fake.createDirectories("/new".toPath())
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        return object : FileHandle(true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                writeFailure?.let { throw it }
                largestWrite = maxOf(largestWrite, byteCount)
                operations += "write"
                handle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() {
                syncFailure?.let { throw it }
                operations += "sync"
                handle.flush()
            }

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize() = handle.size()

            override fun protectedClose() {
                operations += "close"
                handle.close()
            }
        }
    }

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        moveFailure?.let { throw it }
        operations += "move"
        super.atomicMove(source, target)
    }
}

internal class RecordingSink : ProgressSink {
    val starts = mutableListOf<TransferMetadata>()
    val progress = mutableListOf<Pair<Long, Long?>>()
    var beforeStarted: (suspend () -> Unit)? = null
    var onBytes: (Long) -> Unit = {}

    override suspend fun started(metadata: TransferMetadata) {
        beforeStarted?.invoke()
        starts += metadata
    }

    override fun bytes(
        downloaded: Long,
        total: Long?,
    ) {
        progress += downloaded to total
        onBytes(downloaded)
    }
}

internal class TransferRig(
    val credentials: CredentialLookup = CredentialLookup.None,
    val platform: PlatformKind = PlatformKind.DESKTOP,
) : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val roots = TestRoots()
    val faults = FaultFiles()
    val files = OkioDownloadFileSystem(roots, faults)
    val clock = TestClock(nowMs = 1_000_000)
    val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    val hasher = PlatformSriHasher(roots, faults)
    val verification = DownloadFiles(files, roots, hasher, PlatformLocalUris())
    var source =
        DownloadSourceRow(
            1,
            2,
            "Episode",
            "Podcast",
            0,
            null,
            false,
            Availability.AVAILABLE,
            SourceType.RSS,
            server.url("/original").toString(),
            "audio/mpeg",
            null,
            null,
            emptyList(),
        )
    private val platformInfo =
        object : PlatformInfo {
            override val kind = PlatformKind.DESKTOP
            override val userAgentPlatform = "Linux; x64"
            override val androidSdkInt: Int? = null
            override val regionCode = "DE"
        }
    private val agent = UserAgentProvider(platformInfo, "0.1.0", "https://example.com/repo")
    val clients =
        OkHttpNeutrodyneHttpClients(
            NetworkClients(
                CoreClients(
                    UserAgentInterceptor(agent),
                    LocalNetworkGuard(LocalNetworkAccess(), platformInfo),
                    DnsFamilyHints(),
                    emptySet(),
                ),
                AuthInterceptor { credentials },
            ),
            agent,
        )
    val transfer =
        RssTransferSource(
            clients,
            credentials,
            object : DownloadSourceQueries {
                override suspend fun downloadSources(ids: List<Long>) = listOf(source)
            },
            roots,
            files,
            verification,
            network,
            clock,
            platform,
        )
    val sink = RecordingSink()
    val row =
        DownloadEntity(
            1,
            DownloadLane.MANUAL,
            DownloadState.RESOLVING,
            priority = 100,
            requestedAt = 0,
            sourceKind = SourceKind.RSS_ENCLOSURE,
            sourceRef = source.enclosureUrl!!,
            rootId = "old",
            allowMetered = true,
        )

    suspend fun part(): PartFile = files.openPart("old", DownloadPaths.part(1))

    suspend fun plan(
        part: PartFile,
        row: DownloadEntity = this.row,
    ): TransferPlan = (transfer.prepare(row, part) as Prepared.Go).plan

    override fun close() {
        clients.close()
        server.close()
        faults.fake.checkNoOpenFiles()
        faults.close()
    }
}

internal suspend fun PartFile.contents(): ByteArray {
    val output = ByteArray(length().toInt())
    var offset = 0
    while (offset < output.size) {
        val chunk = ByteArray(output.size - offset)
        val size = read(offset.toLong(), chunk, chunk.size)
        check(size > 0)
        chunk.copyInto(output, offset, 0, size)
        offset += size
    }
    return output
}

internal fun transferTest(
    credentials: CredentialLookup = CredentialLookup.None,
    platform: PlatformKind = PlatformKind.DESKTOP,
    block: suspend TransferRig.(PartFile) -> Unit,
) = runBlocking<Unit> {
    TransferRig(credentials, platform).use { rig -> rig.part().withOpen { rig.block(it) } }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The expectation record and pre-launch file checks of the M3 AC6 local-transport gate
 * (`BmgrTransportDeviceTest`, armed by `bmgrPhase`; see `DeviceTestSelection`).
 *
 * `prepareLocalTransportBackup` publishes a snapshot through the real DUMP receiver, then
 * exports this record through instrumentation status key [STATUS_EXPECTED] as base64url JSON.
 * The host saves it, verifies the transport TAR against it, and hands it back through the
 * `bmgrExpected` argument of `firstLaunchRestoresTransportedSnapshot` — random fixture
 * identities and per-run canary hashes never rely on in-process state surviving an uninstall.
 *
 * [verifyRestoredBeforeLaunch] runs inside `NeutrodyneTestRunner.callApplicationOnCreate`
 * before `super` — no graph, no seed, no deletion: the app-private tree at that instant is
 * exactly what install-time restore delivered. A created `databases/neutrodyne.db` would prove
 * the band-100 open already ran, so its absence is the opener-pending evidence.
 */
internal object BmgrTransportExpectation {
    const val ARG_PHASE = "bmgrPhase"
    const val ARG_NONCE = "bmgrNonce"
    const val ARG_EXPECTED = "bmgrExpected"
    const val STATUS_EXPECTED = "bmgrExpected"

    const val SNAPSHOT_REL = "files/backup/auto-snapshot.zip"
    const val PORTABLE_REL = "files/datastore/settings.preferences_pb"
    const val DEVICE_PB_REL = "files/datastore/device_settings.preferences_pb"
    const val DB_NAME = "neutrodyne.db"

    const val PHASE_VERIFY = "verify"
    const val PHASE_EMPTY = "empty"

    /** Out-of-band status emit (2 = stream-only progress, same convention as FeedQueryTimingDeviceTest). */
    const val STATUS_CODE = 2

    /** Where a canary lives; `rel` is relative to that root. */
    val CANARY_WHERES = setOf("databases", "files", "cache", "no_backup", "external")

    data class Canary(
        val where: String,
        val rel: String,
        val sha256: String,
        val bytes: Long,
    )

    /** Everything the restore side must prove, carried across uninstall by the host. */
    data class Expected(
        val nonce: String,
        val packageName: String,
        val api: Int,
        val installationId: String,
        val snapshotSha256: String,
        val snapshotBytes: Long,
        val portableSha256: String,
        val portableBytes: Long,
        val portableKey: String,
        val portableInSnapshot: String,
        val portableAfter: String,
        val syncIds: Map<String, String>,
        val groupUuids: Map<String, String>,
        val canaries: List<Canary>,
        val receiverLine: String,
        val preparedAt: Long,
    )

    fun encode(expected: Expected): String {
        val json =
            JSONObject()
                .put("v", 1)
                .put("nonce", expected.nonce)
                .put("package", expected.packageName)
                .put("api", expected.api)
                .put("installationId", expected.installationId)
                .put("snapshotSha256", expected.snapshotSha256)
                .put("snapshotBytes", expected.snapshotBytes)
                .put("portableSha256", expected.portableSha256)
                .put("portableBytes", expected.portableBytes)
                .put("portableKey", expected.portableKey)
                .put("portableInSnapshot", expected.portableInSnapshot)
                .put("portableAfter", expected.portableAfter)
                .put("syncIds", JSONObject(expected.syncIds))
                .put("groupUuids", JSONObject(expected.groupUuids))
                .put(
                    "canaries",
                    JSONArray().also { array ->
                        expected.canaries.forEach { canary ->
                            array.put(
                                JSONObject()
                                    .put("where", canary.where)
                                    .put("rel", canary.rel)
                                    .put("sha256", canary.sha256)
                                    .put("bytes", canary.bytes),
                            )
                        }
                    },
                ).put("receiverLine", expected.receiverLine)
                .put("preparedAt", expected.preparedAt)
        return Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    fun decode(encoded: String): Expected {
        val json =
            JSONObject(
                String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8),
            )
        check(json.getInt("v") == 1) { "unsupported bmgrExpected version" }
        return Expected(
            nonce = json.getString("nonce"),
            packageName = json.getString("package"),
            api = json.getInt("api"),
            installationId = json.getString("installationId"),
            snapshotSha256 = json.getString("snapshotSha256"),
            snapshotBytes = json.getLong("snapshotBytes"),
            portableSha256 = json.getString("portableSha256"),
            portableBytes = json.getLong("portableBytes"),
            portableKey = json.getString("portableKey"),
            portableInSnapshot = json.getString("portableInSnapshot"),
            portableAfter = json.getString("portableAfter"),
            syncIds =
                json.getJSONObject("syncIds").let { obj ->
                    obj.keys().asSequence().associateWith(obj::getString)
                },
            groupUuids =
                json.getJSONObject("groupUuids").let { obj ->
                    obj.keys().asSequence().associateWith(obj::getString)
                },
            canaries =
                json.getJSONArray("canaries").let { array ->
                    (0 until array.length()).map { index ->
                        array.getJSONObject(index).let { c ->
                            Canary(c.getString("where"), c.getString("rel"), c.getString("sha256"), c.getLong("bytes"))
                        }
                    }
                },
            receiverLine = json.getString("receiverLine"),
            preparedAt = json.getLong("preparedAt"),
        )
    }

    fun phaseOf(arguments: Bundle): String? = arguments.getString(ARG_PHASE)

    fun requireExpected(arguments: Bundle): Expected {
        val encoded = checkNotNull(arguments.getString(ARG_EXPECTED)) { "bmgrPhase=verify requires -e bmgrExpected" }
        return decode(encoded)
    }

    /** The roots a canary `where` resolves against; the test supplies the app's real dirs. */
    class Roots(
        val dataDir: File,
        val cacheDir: File,
        val noBackupDir: File,
        val externalPodcastsDir: File?,
    ) {
        constructor(context: Context) : this(
            dataDir = context.applicationInfo.dataDir.let(::File),
            cacheDir = context.cacheDir,
            noBackupDir = context.noBackupFilesDir,
            externalPodcastsDir = context.getExternalFilesDir("Podcasts"),
        )

        fun resolve(canary: Canary): File =
            when (canary.where) {
                "databases" -> {
                    File(dataDir, "databases/${canary.rel}")
                }

                "files" -> {
                    File(dataDir, "files/${canary.rel}")
                }

                "cache" -> {
                    File(cacheDir, canary.rel)
                }

                "no_backup" -> {
                    File(noBackupDir, canary.rel)
                }

                "external" -> {
                    File(checkNotNull(externalPodcastsDir) { "no external files dir for canary" }, canary.rel)
                }

                else -> {
                    throw IllegalArgumentException("unknown canary where ${canary.where}")
                }
            }
    }

    /**
     * The transport gate's pre-`onCreate` assertions (no graph, no deletion):
     * `files/` holds exactly the two restored payloads with the expected bytes, the
     * database family and the device DataStore are absent (opener still pending), and no
     * canary path came back.
     */
    fun verifyRestoredBeforeLaunch(
        roots: Roots,
        expected: Expected,
    ) {
        val snapshot = File(roots.dataDir, SNAPSHOT_REL)
        val portable = File(roots.dataDir, PORTABLE_REL)
        check(snapshot.isFile) { "install-time restore did not deliver $SNAPSHOT_REL" }
        check(portable.isFile) { "install-time restore did not deliver $PORTABLE_REL" }
        checkFile(snapshot, expected.snapshotSha256, expected.snapshotBytes, SNAPSHOT_REL)
        checkFile(portable, expected.portableSha256, expected.portableBytes, PORTABLE_REL)

        // The whole files/ tree must equal the two payloads — a third transported file fails here.
        val extraFiles =
            File(roots.dataDir, "files")
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(roots.dataDir).invariantSeparatorsPath }
                .toSet()
        check(extraFiles == setOf(SNAPSHOT_REL, PORTABLE_REL)) {
            "unexpected files under files/ after install-time restore: ${extraFiles -
                setOf(
                    SNAPSHOT_REL,
                    PORTABLE_REL,
                )
            }"
        }

        check(noFiles(File(roots.dataDir, "databases"))) {
            "a database file exists before production onCreate — the transport must not deliver one"
        }
        check(noFiles(File(roots.dataDir, "no_backup"))) { "a file exists under no_backup/ before onCreate" }
        check(!File(roots.dataDir, DEVICE_PB_REL).exists()) { "the device DataStore must not be restored" }
        for (canary in expected.canaries) {
            check(!roots.resolve(canary).exists()) { "excluded canary was restored: ${canary.where}/${canary.rel}" }
        }
    }

    /** The empty leg's pre-`onCreate` proof: nothing was restored at all. */
    fun verifyNothingRestored(roots: Roots) {
        check(noFiles(File(roots.dataDir, "files"))) { "files/ must be empty on the empty-transport leg" }
        check(noFiles(File(roots.dataDir, "databases"))) { "databases/ must be empty on the empty-transport leg" }
        check(noFiles(File(roots.dataDir, "no_backup"))) { "no_backup/ must be empty on the empty-transport leg" }
    }

    private fun noFiles(dir: File): Boolean = !dir.exists() || dir.walkTopDown().none { it.isFile }

    private fun checkFile(
        file: File,
        sha256: String,
        bytes: Long,
        what: String,
    ) {
        val content = file.readBytes()
        check(content.size.toLong() == bytes) { "$what size ${content.size} != expected $bytes" }
        check(FirstLaunchRestoreChecks.sha256Hex(content) == sha256) { "$what SHA-256 mismatch after transport" }
    }
}

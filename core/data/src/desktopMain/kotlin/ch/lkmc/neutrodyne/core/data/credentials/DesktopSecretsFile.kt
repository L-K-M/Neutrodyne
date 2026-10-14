// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The secrets file's bytes could not be durably replaced — the temp write failed or the
 * filesystem refused the atomic rename (11 Secrets forbids falling back to a non-atomic write).
 */
internal class SecretStorageException(
    message: String,
    cause: Throwable?,
) : IOException(message, cause)

/**
 * The atomic-rename step of [DesktopSecretsFile]'s write, behind a seam so tests can deny it.
 * The default is `Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
 */
internal fun interface AtomicMove {
    fun move(
        source: Path,
        target: Path,
    )
}

/**
 * The `secrets.json` / `secrets.bin` file of 11 Secrets (PO-44): the desktop `SecretStore`'s
 * only storage of secret material; the `credential` table holds metadata rows only. The
 * plaintext form is version-1 JSON:
 *
 * ```json
 * {"v":1,"entries":{"https://feeds.example.com":{"user":"…","secret":"…"},
 *                  "sync:sync.example.net":{"secret":"nds_…","fp":"<sha256>"}}}
 * ```
 *
 * An entry object's keys other than `user`/`secret` are its caller-supplied `attributes` (10's
 * `fp` fingerprint lives there); `user` is omitted when empty.
 *
 * Every read-modify-write holds [mutex] — the store's puts and cascade-driven removals
 * serialise — and writes replace the whole file atomically: the protected bytes go to a
 * same-directory temp file (`0600` on POSIX file systems), then an
 * `ATOMIC_MOVE` + `REPLACE_EXISTING` rename moves it over the target. A filesystem that cannot
 * rename atomically gets [SecretStorageException], never a torn write.
 *
 * The file is read once per process. An unreadable or undecryptable file — corrupt JSON, a
 * foreign-machine DPAPI blob, an unknown version — is key loss (03 Basic auth): the entry set
 * becomes empty so every row lookup reports loss, and the file is reset to an empty v1 file so
 * the failure does not repeat at every startup.
 */
internal class DesktopSecretsFile(
    val path: Path,
    private val protection: SecretProtection,
    private val move: AtomicMove =
        AtomicMove { source, target ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
) {
    private val mutex = Mutex()
    private var loaded = false
    private var entries: Map<String, StoredSecret> = emptyMap()

    /** The whole entry set — empty when the file does not exist or was unreadable at load. */
    suspend fun entries(): Map<String, StoredSecret> =
        mutex.withLock {
            ensureLoaded()
            entries
        }

    suspend fun entry(origin: String): StoredSecret? =
        mutex.withLock {
            ensureLoaded()
            entries[origin]
        }

    /** Inserts or replaces [origin]'s entry; returns the entry it replaced, if any. */
    suspend fun put(
        origin: String,
        entry: StoredSecret,
    ): StoredSecret? =
        mutex.withLock {
            ensureLoaded()
            val prior = entries[origin]
            write(entries + (origin to entry))
            prior
        }

    /** Deletes [origin]'s entry; returns false when nothing was stored for it. */
    suspend fun remove(origin: String): Boolean =
        mutex.withLock {
            ensureLoaded()
            if (origin !in entries) return@withLock false
            write(entries - origin)
            true
        }

    /**
     * Drops every entry [keep] rejects and rewrites the file only when something changed — the
     * interrupted-put sweep for entries whose `credential` row never committed (11).
     */
    suspend fun dropOrphans(keep: suspend (String) -> Boolean) {
        mutex.withLock {
            ensureLoaded()
            val survivors = LinkedHashMap<String, StoredSecret>(entries.size)
            for ((origin, entry) in entries) if (keep(origin)) survivors[origin] = entry
            if (survivors.size == entries.size) return@withLock
            Log.w(
                TAG,
            ) { "dropping ${entries.size - survivors.size} secrets-file entries that no credential row claims" }
            write(survivors)
        }
    }

    /**
     * First-touch read. A missing file is an empty store; a present-but-unreadable file is key
     * loss — latch the empty set and leave a clean empty file behind (11's reset rule). Caller
     * holds [mutex].
     */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        entries =
            try {
                decode(protection.unprotect(Files.readAllBytes(path)))
            } catch (e: NoSuchFileException) {
                emptyMap()
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                Log.e(TAG, t) { "secrets file unreadable; treating its entries as lost" }
                try {
                    writeAtomically(protection.protect(encode(emptyMap())))
                } catch (resetFailure: Exception) {
                    Log.w(TAG, resetFailure) { "could not reset the unreadable secrets file" }
                }
                emptyMap()
            }
    }

    /** The only mutator — memory and the durable file move together. Caller holds [mutex]. */
    private fun write(next: Map<String, StoredSecret>) {
        writeAtomically(protection.protect(encode(next)))
        entries = next
    }

    /**
     * Whole-file atomic replace (11): a temp file next to the target takes the bytes — `0600`
     * at creation on POSIX, so no window carries looser permissions — then the rename. The
     * parent directory is made `0700` defensively; `AppDirs.ensureCreated` owns it in
     * production.
     */
    private fun writeAtomically(bytes: ByteArray) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        try {
            Files.setPosixFilePermissions(dir, POSIX_DIR_PERMISSIONS)
        } catch (e: UnsupportedOperationException) {
            // Not a POSIX file system — Windows ACLs come from the data directory itself.
        }

        val tmp = createTemp(dir)
        try {
            Files.write(tmp, bytes)
            move.move(tmp, path)
        } catch (e: AtomicMoveNotSupportedException) {
            throw SecretStorageException("filesystem does not support atomic rename for $path", e)
        } finally {
            // After a successful move the temp name no longer exists.
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun createTemp(dir: Path): Path =
        try {
            Files.createTempFile(
                dir,
                TEMP_PREFIX,
                TEMP_SUFFIX,
                PosixFilePermissions.asFileAttribute(POSIX_FILE_PERMISSIONS),
            )
        } catch (e: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): the blob's protection is the file content's.
            Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX)
        }

    private fun encode(entries: Map<String, StoredSecret>): ByteArray =
        buildJsonObject {
            put("v", FORMAT_VERSION)
            putJsonObject("entries") {
                for ((origin, stored) in entries) put(origin, encodeEntry(stored))
            }
        }.toString().encodeToByteArray()

    private fun encodeEntry(stored: StoredSecret): JsonObject =
        buildJsonObject {
            if (stored.username.isNotEmpty()) put(KEY_USER, stored.username)
            put(KEY_SECRET, stored.secret.concatToString())
            for ((key, value) in stored.attributes) {
                // `user`/`secret` are the entry's fields; an attribute may not shadow them.
                if (key == KEY_USER || key == KEY_SECRET) continue
                put(key, value)
            }
        }

    /**
     * Strict version-1 decode — anything that is not exactly the documented shape throws
     * ([IllegalArgumentException], [SerializationException]) and the caller treats it as loss.
     */
    private fun decode(plain: ByteArray): Map<String, StoredSecret> {
        val root = Json.parseToJsonElement(plain.decodeToString()).jsonObject
        val version = root["v"]?.jsonPrimitive?.intOrNull
        require(version == FORMAT_VERSION) { "unsupported secrets file version: $version" }
        val entriesObj =
            root["entries"]?.jsonObject
                ?: throw IllegalArgumentException("secrets file has no entries object")
        val result = LinkedHashMap<String, StoredSecret>(entriesObj.size)
        for ((origin, element) in entriesObj) {
            val obj = element.jsonObject
            val secret =
                obj[KEY_SECRET]
                    ?.jsonPrimitive
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?: throw IllegalArgumentException("secrets entry '$origin' has no string secret")
            val user =
                obj[KEY_USER]
                    ?.jsonPrimitive
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    .orEmpty()
            val attributes = LinkedHashMap<String, String>()
            for ((key, value) in obj) {
                if (key == KEY_USER || key == KEY_SECRET) continue
                attributes[key] =
                    (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                        ?: throw IllegalArgumentException("secrets entry '$origin' attribute '$key' is not a string")
            }
            result[origin] = StoredSecret(user, secret.toCharArray(), attributes)
        }
        return result
    }

    private companion object {
        const val TAG = "DesktopSecretsFile"
        const val FORMAT_VERSION = 1
        const val KEY_USER = "user"
        const val KEY_SECRET = "secret"
        const val TEMP_PREFIX = "secrets"
        const val TEMP_SUFFIX = ".tmp"
        val POSIX_DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val POSIX_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import kotlin.time.Duration.Companion.days

internal fun productionInputs(): ServerInputs {
    val random = SecureRandom()
    return ServerInputs(System::currentTimeMillis) { size -> ByteArray(size).also(random::nextBytes) }
}

internal class AuthSecrets(
    private val inputs: ServerInputs,
) {
    fun secret(prefix: String = ""): String =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(inputs.randomBytes(32))

    fun epoch(): String = inputs.randomBytes(4).toHexString()

    fun code(length: Int): String {
        val result = StringBuilder(length)
        // Rejection sampling avoids bias because the alphabet does not divide 256.
        while (result.length < length) {
            for (byte in inputs.randomBytes(length)) {
                val index = byte.toInt() and 255
                if (index >= 240) continue
                result.append(ALPHABET[index % ALPHABET.length])
                if (result.length == length) break
            }
        }
        return result.toString()
    }

    companion object {
        private const val ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"

        fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).toHexString()

        fun same(
            left: String,
            right: String,
        ): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

        fun normalizeCode(code: String): String = code.uppercase().filterNot { it == '-' || it == ' ' }

        fun username(value: String): String {
            val name = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9._-]{1,40}"))) invalidAuth()
            return name
        }

        fun display(code: String): String = code.chunked(4).joinToString("-")
    }
}

/** Process-wide bound covers all login/password/setup callers, including dummy verification. */
internal class PasswordHasher(
    private val inputs: ServerInputs,
) {
    suspend fun hash(password: String): String {
        validate(password)
        val salt = inputs.randomBytes(SALT_BYTES)
        val hash = derive(password, salt)
        return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=1\$${encode(salt)}\$${encode(hash)}"
    }

    suspend fun verify(
        password: String,
        phc: String?,
    ): Boolean {
        val record = phc ?: DUMMY
        val parts = record.split('$')
        // Refuse corrupt/foreign parameter sets rather than accepting attacker-sized work factors.
        val valid =
            parts.size == 6 && parts[1] == "argon2id" && parts[2] == "v=19" &&
                parts[3] == "m=$MEMORY_KIB,t=$ITERATIONS,p=1"
        val salt = if (valid) runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() else null
        val expected = if (valid) runCatching { Base64.getDecoder().decode(parts[5]) }.getOrNull() else null
        val usable = salt?.size == SALT_BYTES && expected?.size == HASH_BYTES
        val bounded = password.take(MAX_LENGTH)
        val actual = derive(bounded, if (usable) checkNotNull(salt) else ByteArray(SALT_BYTES))
        val matches = MessageDigest.isEqual(actual, if (usable) expected else ByteArray(HASH_BYTES))
        return phc != null && usable && password.length in MIN_LENGTH..MAX_LENGTH && matches
    }

    private suspend fun derive(
        password: String,
        salt: ByteArray,
    ): ByteArray =
        permits.withPermit {
            withContext(Dispatchers.Default) {
                val bytes = password.toByteArray(Charsets.UTF_8)
                try {
                    val params =
                        Argon2Parameters
                            .Builder(Argon2Parameters.ARGON2_id)
                            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                            .withMemoryAsKB(MEMORY_KIB)
                            .withIterations(ITERATIONS)
                            .withParallelism(1)
                            .withSalt(salt)
                            .build()
                    try {
                        val generator = Argon2BytesGenerator()
                        generator.init(params)
                        ByteArray(HASH_BYTES).also { generator.generateBytes(bytes, it) }
                    } finally {
                        params.clear()
                    }
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private fun validate(password: String) {
        if (password.length !in MIN_LENGTH..MAX_LENGTH) invalidAuth()
    }

    companion object {
        private const val MEMORY_KIB = 19 * 1024
        private const val ITERATIONS = 2
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MIN_LENGTH = 10
        private const val MAX_LENGTH = 256
        private val permits = Semaphore(2)

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

        private val DUMMY = "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=1\$${encode(
            ByteArray(SALT_BYTES),
        )}\$${encode(ByteArray(HASH_BYTES))}"
    }
}

internal fun invalidAuth(): Nothing = throw ProtocolException(SyncErrorCode.INVALID, "invalid")

internal fun unauthorized(): Nothing = throw ProtocolException(SyncErrorCode.UNAUTHORIZED, "unauthorized")

internal fun forbidden(): Nothing = throw ProtocolException(SyncErrorCode.FORBIDDEN, "forbidden")

internal fun missingAuth(): Nothing = throw ProtocolException(SyncErrorCode.NOT_FOUND, "not_found")

internal const val HOUR_MS = 3_600_000L
internal val DAY_MS = 1.days.inWholeMilliseconds

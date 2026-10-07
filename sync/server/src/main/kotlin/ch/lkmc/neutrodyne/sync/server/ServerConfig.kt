// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path

/** Log levels of `NEUTRODYNE_SERVER_LOG_LEVEL` / `log.level` (10 Configuration). */
internal enum class LogLevel(
    val wire: String,
) {
    ERROR("error"),
    WARN("warn"),
    INFO("info"),
    DEBUG("debug"),
    TRACE("trace"),
    ;

    internal companion object {
        fun parse(text: String): LogLevel? = entries.firstOrNull { it.wire == text.lowercase() }
    }
}

/** `NEUTRODYNE_SERVER_LOG_FORMAT` / `log.format` (10 Configuration). The JSON formatter arrives with MS1. */
internal enum class LogFormat(
    val wire: String,
) {
    TEXT("text"),
    JSON("json"),
    ;

    internal companion object {
        fun parse(text: String): LogFormat? = entries.firstOrNull { it.wire == text.lowercase() }
    }
}

/** A `host:port` listen address (10 Configuration: `listen`, `metrics.listen`). */
internal data class ListenAddress(
    val host: String,
    val port: Int,
) {
    /** Loopback listeners are always allowed by the listen rule (10 TLS stance and insecure LAN mode). */
    val isLoopback: Boolean
        get() = isLoopbackHost(host)

    override fun toString(): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$hostPart:$port"
    }

    internal companion object {
        const val FORMAT = "host:port (IPv6 in brackets, for example [::1]:8787)"
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        fun parse(text: String): ListenAddress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            // Bracketed IPv6 first: "[::1]:8787"; everything else splits at the last colon.
            val host: String
            val portText: String
            if (trimmed.startsWith("[")) {
                val closing = trimmed.indexOf(']')
                if (closing < 0) return null
                host = trimmed.substring(1, closing)
                val rest = trimmed.substring(closing + 1)
                if (!rest.startsWith(":")) return null
                portText = rest.substring(1)
            } else {
                val colon = trimmed.lastIndexOf(':')
                if (colon <= 0) return null
                host = trimmed.substring(0, colon)
                portText = trimmed.substring(colon + 1)
            }
            if (host.isEmpty()) return null
            val port = portText.toIntOrNull() ?: return null
            if (port !in MIN_PORT..MAX_PORT) return null
            return ListenAddress(host, port)
        }
    }
}

/** `server.properties` keys (10 Configuration; the file itself is chosen with `serve --config`). */
internal object ServerPropertyKeys {
    const val DATA_DIR = "data.dir"
    const val LISTEN = "listen"
    const val PUBLIC_URL = "public.url"
    const val TRUSTED_PROXIES = "trusted.proxies"
    const val LOG_LEVEL = "log.level"
    const val LOG_FORMAT = "log.format"
    const val UPDATE_CHECK = "update.check"
    const val METRICS_LISTEN = "metrics.listen"
    const val QUOTA_RECORDS = "quota.records"
    const val BACKUP_TIME = "backup.time"
    const val BACKUPS_KEEP = "backups.keep"
    const val ACCOUNT_BACKUPS_KEEP = "account_backups.keep"
    const val RETENTION_TOMBSTONE_DAYS = "retention.tombstone_days"
    const val RETENTION_UNSUBSCRIBED_DAYS = "retention.unsubscribed_days"
    const val RETENTION_EMPTY_EPISODE_DAYS = "retention.empty_episode_days"
    const val SSE_MAX_PER_ACCOUNT = "sse.max_per_account"
}

/**
 * Configuration from flags, environment, `server.properties` and defaults, with the listen rule
 * validated (10 Configuration, 10 TLS stance and insecure LAN mode, N13). Precedence:
 * command-line flag > environment > `server.properties` > default.
 */
internal data class ServerConfig(
    val dataDir: Path,
    val listen: ListenAddress,
    val publicUrl: URI?,
    val trustedProxies: List<IpCidr>,
    val logLevel: LogLevel,
    val logFormat: LogFormat,
    val updateCheck: Boolean,
    val metricsListen: ListenAddress?,
    val insecureLan: Boolean,
    val quotaRecords: Long,
    val backupTime: String,
    val backupsKeep: Int,
    val accountBackupsKeep: Int,
    val retentionTombstoneDays: Int,
    val retentionUnsubscribedDays: Int,
    val retentionEmptyEpisodeDays: Int,
    val sseMaxPerAccount: Int,
) {
    /** Whether the server is published behind TLS and must refuse plain requests from untrusted peers. */
    val publicUrlIsHttps: Boolean
        get() = HTTPS_SCHEME == publicUrl?.scheme

    internal companion object {
        const val HTTPS_SCHEME = "https"
        const val HTTP_SCHEME = "http"
    }
}

/** Result of a configuration load: either a valid [ServerConfig] or human-readable errors. */
internal sealed interface ServerConfigResult {
    data class Valid(
        val config: ServerConfig,
    ) : ServerConfigResult

    data class Invalid(
        val errors: List<String>,
    ) : ServerConfigResult
}

/** Flags of the `serve` command (10 CLI). */
internal data class ServeFlags(
    val insecureLan: Boolean = false,
    val configFile: Path? = null,
)

/** Every `NEUTRODYNE_SERVER_*` variable of 10 Configuration. */
internal object ServerEnv {
    const val DATA = "NEUTRODYNE_SERVER_DATA"
    const val LISTEN = "NEUTRODYNE_SERVER_LISTEN"
    const val PUBLIC_URL = "NEUTRODYNE_SERVER_PUBLIC_URL"
    const val TRUSTED_PROXIES = "NEUTRODYNE_SERVER_TRUSTED_PROXIES"
    const val LOG_LEVEL = "NEUTRODYNE_SERVER_LOG_LEVEL"
    const val LOG_FORMAT = "NEUTRODYNE_SERVER_LOG_FORMAT"
    const val UPDATE_CHECK = "NEUTRODYNE_SERVER_UPDATE_CHECK"
    const val METRICS_LISTEN = "NEUTRODYNE_SERVER_METRICS_LISTEN"
}

/** Defaults of 10 Configuration's table. */
internal object ServerDefaults {
    const val DATA_DIR = "./data"
    const val LISTEN_HOST = "127.0.0.1"
    const val LISTEN_PORT = 8787
    const val TRUSTED_PROXIES = "127.0.0.1/32,::1/128"
    const val UPDATE_CHECK = true
    const val QUOTA_RECORDS = 500_000L
    const val BACKUP_TIME = "03:30"
    const val BACKUPS_KEEP = 7
    const val ACCOUNT_BACKUPS_KEEP = 14
    const val RETENTION_TOMBSTONE_DAYS = 365
    const val RETENTION_UNSUBSCRIBED_DAYS = 180
    const val RETENTION_EMPTY_EPISODE_DAYS = 30
    const val SSE_MAX_PER_ACCOUNT = 10

    val LOG_LEVEL: LogLevel = LogLevel.INFO
    val LOG_FORMAT: LogFormat = LogFormat.TEXT
}

/** Loads and validates [ServerConfig]; accumulates every problem so one run reports them all. */
internal object ServerConfigLoader {
    fun load(
        environment: Map<String, String>,
        properties: Map<String, String>,
        flags: ServeFlags,
    ): ServerConfigResult {
        val errors = mutableListOf<String>()

        fun raw(
            envKey: String?,
            propertyKey: String,
        ): String? =
            envKey?.let { environment[it] }?.trim()?.takeIf { it.isNotEmpty() }
                ?: properties[propertyKey]?.trim()?.takeIf { it.isNotEmpty() }

        // Like [raw], but an explicitly empty value stays empty (so `trusted.proxies =` is an
        // error instead of silently falling back to the loopback default).
        fun explicitRaw(
            envKey: String,
            propertyKey: String,
        ): String? = environment[envKey]?.trim() ?: properties[propertyKey]?.trim()

        fun stringSetting(
            envKey: String?,
            propertyKey: String,
            default: String,
        ): String = raw(envKey, propertyKey) ?: default

        fun settingName(
            envKey: String?,
            propertyKey: String,
        ): String = listOfNotNull(envKey, propertyKey).joinToString(" / ")

        fun listenSetting(
            envKey: String?,
            propertyKey: String,
            default: ListenAddress,
        ): ListenAddress {
            val text = raw(envKey, propertyKey) ?: return default
            return ListenAddress.parse(text) ?: run {
                val name = settingName(envKey, propertyKey)
                errors.add("$name: '$text' is not a listen address ${ListenAddress.FORMAT}")
                default
            }
        }

        fun booleanSetting(
            envKey: String?,
            propertyKey: String,
            default: Boolean,
        ): Boolean {
            val text = raw(envKey, propertyKey) ?: return default
            return when (text.lowercase()) {
                "true" -> {
                    true
                }

                "false" -> {
                    false
                }

                else -> {
                    run {
                        errors.add("${settingName(envKey, propertyKey)}: '$text' is not true or false")
                        default
                    }
                }
            }
        }

        fun intSetting(
            envKey: String?,
            propertyKey: String,
            default: Int,
        ): Int {
            val text = raw(envKey, propertyKey) ?: return default
            return text.toIntOrNull()?.takeIf { it > 0 } ?: run {
                errors.add("${settingName(envKey, propertyKey)}: '$text' is not a positive whole number")
                default
            }
        }

        fun longSetting(
            envKey: String?,
            propertyKey: String,
            default: Long,
        ): Long {
            val text = raw(envKey, propertyKey) ?: return default
            return text.toLongOrNull()?.takeIf { it > 0 } ?: run {
                errors.add("${settingName(envKey, propertyKey)}: '$text' is not a positive whole number")
                default
            }
        }

        val dataDir = Path(stringSetting(ServerEnv.DATA, ServerPropertyKeys.DATA_DIR, ServerDefaults.DATA_DIR))
        val listen = listenSetting(ServerEnv.LISTEN, ServerPropertyKeys.LISTEN, defaultListen())
        val publicUrl =
            raw(ServerEnv.PUBLIC_URL, ServerPropertyKeys.PUBLIC_URL)?.let { text ->
                parsePublicUrl(text) ?: run {
                    val name = settingName(ServerEnv.PUBLIC_URL, ServerPropertyKeys.PUBLIC_URL)
                    errors.add("$name: '$text' is not an http:// or https:// URL")
                    null
                }
            }
        val trustedProxies =
            explicitRaw(ServerEnv.TRUSTED_PROXIES, ServerPropertyKeys.TRUSTED_PROXIES)
                ?.let { text -> parseTrustedProxies(text, errors) }
                ?: IpCidr.parseAll(ServerDefaults.TRUSTED_PROXIES)

        val logLevel =
            raw(ServerEnv.LOG_LEVEL, ServerPropertyKeys.LOG_LEVEL)?.let { text ->
                LogLevel.parse(text) ?: run {
                    val name = settingName(ServerEnv.LOG_LEVEL, ServerPropertyKeys.LOG_LEVEL)
                    val levels = LogLevel.entries.joinToString("/") { it.wire }
                    errors.add("$name: '$text' is not one of $levels")
                    null
                }
            } ?: ServerDefaults.LOG_LEVEL

        val logFormat =
            raw(ServerEnv.LOG_FORMAT, ServerPropertyKeys.LOG_FORMAT)?.let { text ->
                LogFormat.parse(text) ?: run {
                    val name = settingName(ServerEnv.LOG_FORMAT, ServerPropertyKeys.LOG_FORMAT)
                    errors.add("$name: '$text' is not text or json")
                    null
                }
            } ?: ServerDefaults.LOG_FORMAT

        val updateCheck =
            booleanSetting(ServerEnv.UPDATE_CHECK, ServerPropertyKeys.UPDATE_CHECK, ServerDefaults.UPDATE_CHECK)
        val metricsListen =
            raw(ServerEnv.METRICS_LISTEN, ServerPropertyKeys.METRICS_LISTEN)?.let { text ->
                ListenAddress.parse(text) ?: run {
                    val name = settingName(ServerEnv.METRICS_LISTEN, ServerPropertyKeys.METRICS_LISTEN)
                    errors.add("$name: '$text' is not a listen address ${ListenAddress.FORMAT}")
                    null
                }
            }

        // Properties-only settings (10 Configuration's last table row).
        val quotaRecords = longSetting(null, ServerPropertyKeys.QUOTA_RECORDS, ServerDefaults.QUOTA_RECORDS)
        val backupsKeep = intSetting(null, ServerPropertyKeys.BACKUPS_KEEP, ServerDefaults.BACKUPS_KEEP)
        val accountBackupsKeep =
            intSetting(null, ServerPropertyKeys.ACCOUNT_BACKUPS_KEEP, ServerDefaults.ACCOUNT_BACKUPS_KEEP)
        val retentionTombstoneDays =
            intSetting(null, ServerPropertyKeys.RETENTION_TOMBSTONE_DAYS, ServerDefaults.RETENTION_TOMBSTONE_DAYS)
        val retentionUnsubscribedDays =
            intSetting(null, ServerPropertyKeys.RETENTION_UNSUBSCRIBED_DAYS, ServerDefaults.RETENTION_UNSUBSCRIBED_DAYS)
        val retentionEmptyEpisodeDays =
            intSetting(
                null,
                ServerPropertyKeys.RETENTION_EMPTY_EPISODE_DAYS,
                ServerDefaults.RETENTION_EMPTY_EPISODE_DAYS,
            )
        val sseMaxPerAccount =
            intSetting(null, ServerPropertyKeys.SSE_MAX_PER_ACCOUNT, ServerDefaults.SSE_MAX_PER_ACCOUNT)

        val backupTime =
            stringSetting(null, ServerPropertyKeys.BACKUP_TIME, ServerDefaults.BACKUP_TIME).let { text ->
                if (isBackupTime(text)) {
                    text
                } else {
                    run {
                        val name = ServerPropertyKeys.BACKUP_TIME
                        errors.add("$name: '$text' is not a time of day HH:mm (for example 03:30)")
                        ServerDefaults.BACKUP_TIME
                    }
                }
            }

        // The listen rule (10 TLS stance, N13): loopback is always allowed; anything else needs
        // an https:// public URL (TLS is then the reverse proxy's job) or --insecure-lan.
        if (!listen.isLoopback && !flags.insecureLan && publicUrl?.scheme != ServerConfig.HTTPS_SCHEME) {
            errors.add(
                "refusing to listen on $listen: the address is not loopback. " +
                    "Set ${ServerEnv.PUBLIC_URL} to the https:// URL of your reverse proxy, " +
                    "or pass --insecure-lan to serve plain HTTP on a trusted LAN",
            )
        }

        if (errors.isNotEmpty()) return ServerConfigResult.Invalid(errors)

        return ServerConfigResult.Valid(
            ServerConfig(
                dataDir = dataDir,
                listen = listen,
                publicUrl = publicUrl,
                trustedProxies = trustedProxies,
                logLevel = logLevel,
                logFormat = logFormat,
                updateCheck = updateCheck,
                metricsListen = metricsListen,
                insecureLan = flags.insecureLan,
                quotaRecords = quotaRecords,
                backupTime = backupTime,
                backupsKeep = backupsKeep,
                accountBackupsKeep = accountBackupsKeep,
                retentionTombstoneDays = retentionTombstoneDays,
                retentionUnsubscribedDays = retentionUnsubscribedDays,
                retentionEmptyEpisodeDays = retentionEmptyEpisodeDays,
                sseMaxPerAccount = sseMaxPerAccount,
            ),
        )
    }

    /** Reads a `server.properties` file (`#` or `!` comments, `key = value` lines). */
    fun readPropertiesFile(path: Path): Map<String, String> {
        try {
            Files.newInputStream(path).use { stream: InputStream ->
                val properties = Properties()
                properties.load(stream)
                return properties.stringPropertyNames().associateWith { key -> properties.getProperty(key).trim() }
            }
        } catch (e: IOException) {
            throw IOException("cannot read ${path.fileName ?: path}: ${e.message}", e)
        }
    }

    private fun defaultListen(): ListenAddress = ListenAddress(ServerDefaults.LISTEN_HOST, ServerDefaults.LISTEN_PORT)

    private fun parsePublicUrl(text: String): URI? {
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val allowed = scheme == ServerConfig.HTTP_SCHEME || scheme == ServerConfig.HTTPS_SCHEME
        return if (allowed && !uri.host.isNullOrEmpty()) uri else null
    }

    private fun parseTrustedProxies(
        text: String,
        errors: MutableList<String>,
    ): List<IpCidr> {
        val entries = text.split(',')
        val cidrs = entries.map { entry -> IpCidr.parse(entry) }
        val invalid = entries.filterIndexed { index, _ -> cidrs[index] == null }
        if (invalid.isNotEmpty()) {
            errors.add(
                "${ServerEnv.TRUSTED_PROXIES} / ${ServerPropertyKeys.TRUSTED_PROXIES}: " +
                    invalid.joinToString(", ") { "'$it'" } + " not a CIDR or address",
            )
            return emptyList()
        }
        return cidrs.filterNotNull()
    }

    /** `backup.time` is a wall-clock `HH:mm` (default 03:30), hour 0–23, minute 0–59. */
    private fun isBackupTime(text: String): Boolean {
        val match = Regex("""^(\d{2}):(\d{2})$""").find(text) ?: return false
        val (hour, minute) = match.destructured
        return hour.toInt() in 0..23 && minute.toInt() in 0..59
    }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Configuration parsing: every `NEUTRODYNE_SERVER_*` variable, precedence and validation (10 Configuration). */
class ServerConfigTest {
    @Test
    fun `defaults match the configuration table`() {
        val config = loadValidConfig()

        assertEquals("./data", config.dataDir.toString())
        assertEquals(ListenAddress("127.0.0.1", 8787), config.listen)
        assertNull(config.publicUrl)
        assertEquals(IpCidr.parseAll("127.0.0.1/32,::1/128"), config.trustedProxies)
        assertEquals(LogLevel.INFO, config.logLevel)
        assertEquals(LogFormat.TEXT, config.logFormat)
        assertEquals(true, config.updateCheck)
        assertNull(config.metricsListen)
        assertEquals(false, config.insecureLan)
        assertEquals(500_000L, config.quotaRecords)
        assertEquals("03:30", config.backupTime)
        assertEquals(7, config.backupsKeep)
        assertEquals(14, config.accountBackupsKeep)
        assertEquals(365, config.retentionTombstoneDays)
        assertEquals(180, config.retentionUnsubscribedDays)
        assertEquals(30, config.retentionEmptyEpisodeDays)
        assertEquals(10, config.sseMaxPerAccount)
    }

    @Test
    fun `environment variables override every documented setting`() {
        val config =
            loadValidConfig(
                env =
                    mapOf(
                        ServerEnv.DATA to "/var/lib/neutrodyne-server",
                        ServerEnv.LISTEN to "0.0.0.0:8787",
                        ServerEnv.PUBLIC_URL to "https://sync.example.org",
                        ServerEnv.TRUSTED_PROXIES to "172.31.87.0/24,10.0.0.8",
                        ServerEnv.LOG_LEVEL to "DEBUG",
                        ServerEnv.LOG_FORMAT to "json",
                        ServerEnv.UPDATE_CHECK to "false",
                        ServerEnv.METRICS_LISTEN to "127.0.0.1:9787",
                    ),
            )

        assertEquals("/var/lib/neutrodyne-server", config.dataDir.toString())
        assertEquals(ListenAddress("0.0.0.0", 8787), config.listen)
        assertEquals("https://sync.example.org", config.publicUrl.toString())
        assertEquals(2, config.trustedProxies.size)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertEquals(LogFormat.JSON, config.logFormat)
        assertEquals(false, config.updateCheck)
        assertEquals(ListenAddress("127.0.0.1", 9787), config.metricsListen)
    }

    @Test
    fun `properties-only settings parse from the properties file`() {
        val config =
            loadValidConfig(
                properties =
                    mapOf(
                        ServerPropertyKeys.QUOTA_RECORDS to "1500",
                        ServerPropertyKeys.BACKUP_TIME to "04:15",
                        ServerPropertyKeys.BACKUPS_KEEP to "3",
                        ServerPropertyKeys.ACCOUNT_BACKUPS_KEEP to "5",
                        ServerPropertyKeys.RETENTION_TOMBSTONE_DAYS to "90",
                        ServerPropertyKeys.RETENTION_UNSUBSCRIBED_DAYS to "60",
                        ServerPropertyKeys.RETENTION_EMPTY_EPISODE_DAYS to "10",
                        ServerPropertyKeys.SSE_MAX_PER_ACCOUNT to "2",
                        ServerPropertyKeys.UPDATE_CHECK to "false",
                    ),
            )

        assertEquals(1500L, config.quotaRecords)
        assertEquals("04:15", config.backupTime)
        assertEquals(3, config.backupsKeep)
        assertEquals(5, config.accountBackupsKeep)
        assertEquals(90, config.retentionTombstoneDays)
        assertEquals(60, config.retentionUnsubscribedDays)
        assertEquals(10, config.retentionEmptyEpisodeDays)
        assertEquals(2, config.sseMaxPerAccount)
        assertEquals(false, config.updateCheck)
    }

    @Test
    fun `the environment beats the properties file`() {
        val config =
            loadValidConfig(
                env = mapOf(ServerEnv.LISTEN to "127.0.0.1:9001"),
                properties = mapOf(ServerPropertyKeys.LISTEN to "127.0.0.1:9002"),
            )
        assertEquals(9001, config.listen.port)
    }

    @Test
    fun `an explicitly empty trusted-proxies list is refused, not defaulted`() {
        val errors = loadInvalidConfig(env = mapOf(ServerEnv.TRUSTED_PROXIES to "   "))
        assertTrue(errors.single().contains(ServerEnv.TRUSTED_PROXIES), errors.single())
    }

    @Test
    fun `listen addresses parse in every documented form`() {
        assertEquals(ListenAddress("127.0.0.1", 8787), ListenAddress.parse("127.0.0.1:8787"))
        assertEquals(ListenAddress("0.0.0.0", 1), ListenAddress.parse("0.0.0.0:1"))
        assertEquals(ListenAddress("::1", 8787), ListenAddress.parse("[::1]:8787"))
        assertNull(ListenAddress.parse("8787"))
        assertNull(ListenAddress.parse("host:0"))
        assertNull(ListenAddress.parse("host:70000"))
        assertNull(ListenAddress.parse("host:notaport"))
        assertNull(ListenAddress.parse("[::1]:"))
    }

    @Test
    fun `invalid values produce clear errors`() {
        val cases =
            mapOf(
                ServerEnv.LISTEN to listOf("nohostport", "host:notaport", "host:0", "host:70000"),
                ServerEnv.PUBLIC_URL to listOf("ftp://sync.example.org", "not-a-url", "https://"),
                ServerEnv.TRUSTED_PROXIES to listOf("nonsense", "10.0.0.0/64"),
                ServerEnv.LOG_LEVEL to listOf("loud"),
                ServerEnv.LOG_FORMAT to listOf("xml"),
                ServerEnv.UPDATE_CHECK to listOf("yes"),
                ServerEnv.METRICS_LISTEN to listOf("nohostport"),
            )
        for ((variable, values) in cases) {
            for (value in values) {
                // The loopback listen address only accompanies other variables' cases; a listen
                // value under test must not be overwritten by it.
                val env =
                    if (variable == ServerEnv.LISTEN) {
                        mapOf(variable to value)
                    } else {
                        mapOf(variable to value, ServerEnv.LISTEN to "127.0.0.1:8787")
                    }
                val errors = loadInvalidConfig(env = env)
                assertTrue(
                    errors.any { error -> error.contains(variable) && error.contains(value) },
                    "$variable=$value should report an error, got: $errors",
                )
            }
        }
    }

    @Test
    fun `invalid properties produce clear errors`() {
        val errors =
            loadInvalidConfig(
                properties =
                    mapOf(
                        ServerPropertyKeys.QUOTA_RECORDS to "-5",
                        ServerPropertyKeys.BACKUP_TIME to "25:00",
                        ServerPropertyKeys.BACKUPS_KEEP to "two",
                    ),
            )
        assertEquals(3, errors.size)
        assertTrue(errors.any { it.contains(ServerPropertyKeys.QUOTA_RECORDS) })
        assertTrue(errors.any { it.contains(ServerPropertyKeys.BACKUP_TIME) })
        assertTrue(errors.any { it.contains(ServerPropertyKeys.BACKUPS_KEEP) })
    }

    @Test
    fun `properties files parse with comments`() {
        val file = Files.createTempFile("server", ".properties")
        file.writeText(
            """
            # a comment
            ! another comment style
            listen = 127.0.0.1:9003
            quota.records=42
            """.trimIndent() + "\n",
        )
        val properties = ServerConfigLoader.readPropertiesFile(file)

        assertEquals("127.0.0.1:9003", properties[ServerPropertyKeys.LISTEN])
        assertEquals("42", properties[ServerPropertyKeys.QUOTA_RECORDS])
    }
}

// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.server.application.ServerReady
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Server entry point (10 CLI, 10 Module and classes): argument parsing lives in [ServerCli],
 * which starts the server through [serve].
 */
fun main(args: Array<String>) {
    exitProcess(ServerCli(args, System.getenv()).run())
}

/**
 * Runs the server on the CIO engine (10 Ktor setup) and blocks. `connectionIdleTimeoutSeconds`
 * stays above a reverse proxy's upstream keep-alive (Caddy: 2 min) so a reused connection is
 * never closed while a request is in flight.
 */
internal fun serve(config: ServerConfig) {
    configureSimpleLogging(config.logLevel)

    val logger = LoggerFactory.getLogger(LOGGER_NAME)
    if (config.insecureLan) {
        logger.warn("--insecure-lan: serving plain HTTP for a trusted LAN; tokens cross the LAN unencrypted")
    }

    lateinit var services: ServerServices
    EventBus({ services.tokens.valid(it) }, config.sseMaxPerAccount).use { events ->
        ServerStorage.open(config.dataDir, events::publish).use { storage ->
            services = ServerServices(storage.store, config, events)
            logger.info("Storage ready: schema {}", Migrator.SCHEMA_VERSION)
            runBlocking { services.setup.initialize() }?.let {
                logger.info("Setup token file: {}", it.toAbsolutePath())
            }
            val ready = AtomicBoolean(false)
            val server =
                embeddedServer(
                    CIO,
                    serverConfig {
                        module {
                            ServerModule(
                                config,
                                ServerVersion.current(),
                                writeProbe = storage.store::writeProbe,
                                services = services,
                            ).install(this)
                            services.updates.start(this)
                            services.backupJob.start(this, services.gcJob)
                            monitor.subscribe(ServerReady) { ready.set(true) }
                        }
                    },
                ) {
                    connectors +=
                        EngineConnectorBuilder().apply {
                            host = config.listen.host
                            port = config.listen.port
                        }
                    connectionIdleTimeoutSeconds = CIO_IDLE_TIMEOUT_SECONDS
                }
            val metricsListener = config.metricsListen?.let { services.metrics.listen(it) }
            logger.info("neutrodyne-server {} listening on {}", ServerVersion.current(), config.listen)
            val shutdownHook =
                Thread({
                    metricsListener?.close()
                    server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
                    if (ready.get()) storage.finishGracefully()
                }, "neutrodyne-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            try {
                server.start(wait = true)
                // CIO's wait may return before the stop hook finishes closing the application.
                shutdownHook.join()
            } finally {
                metricsListener?.close()
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                } catch (_: IllegalStateException) {
                    // The JVM is already stopping; the registered hook owns graceful shutdown.
                }
            }
        }
    }
}

/** slf4j-simple is configured through system properties before the first logger is created. */
private fun configureSimpleLogging(level: LogLevel) {
    System.setProperty(SIMPLE_LOGGER_PREFIX + "defaultLogLevel", level.wire)
    System.setProperty(SIMPLE_LOGGER_PREFIX + "showDateTime", "true")
    System.setProperty(SIMPLE_LOGGER_PREFIX + "logFile", "System.out")
    // Framework events can include raw requests; only our structured logger is request-aware.
    System.setProperty(SIMPLE_LOGGER_PREFIX + "log.io.ktor", "off")
    System.setProperty(SIMPLE_LOGGER_PREFIX + "log.ktor", "off")
}

private const val LOGGER_NAME = "ch.lkmc.neutrodyne.sync.server"

/** 10 Ktor setup: longer than Caddy's 2-min upstream keep-alive. */
private const val CIO_IDLE_TIMEOUT_SECONDS = 180

private const val SIMPLE_LOGGER_PREFIX = "org.slf4j.simpleLogger."

private const val SHUTDOWN_GRACE_MS = 1_000L
private const val SHUTDOWN_TIMEOUT_MS = 5_000L

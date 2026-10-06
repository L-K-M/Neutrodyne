// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.server.application.Application
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/** Shared fixtures of the server's black-box tests (10 Server black-box tests). */

internal val FIXED_INSTANT: Instant = Instant.parse("2026-10-06T10:15:30.250Z")

internal val FIXED_CLOCK: Clock =
    object : Clock {
        override fun now(): Instant = FIXED_INSTANT
    }

internal const val TEST_SERVER_VERSION = "0.42-test"

internal fun loadValidConfig(
    env: Map<String, String> = emptyMap(),
    properties: Map<String, String> = emptyMap(),
    flags: ServeFlags = ServeFlags(),
): ServerConfig =
    when (val result = ServerConfigLoader.load(env, properties, flags)) {
        is ServerConfigResult.Valid -> result.config
        is ServerConfigResult.Invalid -> error("test configuration is invalid: ${result.errors}")
    }

internal fun loadInvalidConfig(
    env: Map<String, String> = emptyMap(),
    properties: Map<String, String> = emptyMap(),
    flags: ServeFlags = ServeFlags(),
): List<String> =
    when (val result = ServerConfigLoader.load(env, properties, flags)) {
        is ServerConfigResult.Invalid -> result.errors
        is ServerConfigResult.Valid -> error("test configuration is unexpectedly valid: ${result.config}")
    }

internal fun tempDataDir(): Path = Files.createTempDirectory("neutrodyne-server-test")

/** Installs the module under test with a fixed version and clock. */
internal fun Application.installTestModule(config: ServerConfig) {
    ServerModule(config.copy(dataDir = tempDataDir()), TEST_SERVER_VERSION, FIXED_CLOCK).install(this)
}

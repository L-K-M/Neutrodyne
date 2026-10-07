// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

/** Server entry point; `serve` and the health endpoints arrive in M0b (10 Server architecture). */
fun main() {
    println("neutrodyne-server ${ServerVersion.current()}")
}

internal object ServerVersion {
    /** The fat JAR's manifest carries the version; tests and `run` fall back to "dev". */
    fun current(): String = ServerVersion::class.java.`package`?.implementationVersion ?: "dev"
}

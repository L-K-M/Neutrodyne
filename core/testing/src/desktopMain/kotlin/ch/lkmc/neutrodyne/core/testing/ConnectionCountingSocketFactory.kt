// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory

/** Counts accepted TCP sockets separately from MockWebServer's HTTP request count. */
public class ConnectionCountingSocketFactory : ServerSocketFactory() {
    private val accepted = AtomicInteger()
    public val acceptedConnections: Int get() = accepted.get()

    override fun createServerSocket(): ServerSocket =
        object : ServerSocket() {
            override fun accept(): Socket = super.accept().also { accepted.incrementAndGet() }
        }

    override fun createServerSocket(port: Int): ServerSocket =
        createServerSocket().apply { bind(InetSocketAddress(port)) }

    override fun createServerSocket(
        port: Int,
        backlog: Int,
    ): ServerSocket =
        createServerSocket().apply { bind(InetSocketAddress(port), backlog) }

    override fun createServerSocket(
        port: Int,
        backlog: Int,
        ifAddress: InetAddress?,
    ): ServerSocket =
        createServerSocket().apply { bind(InetSocketAddress(ifAddress, port), backlog) }
}

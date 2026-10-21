package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import com.shilapi.xcertplay.network.backend.*

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 */
object AirPlayPortSelector {
    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    /** Backend variant: every advertised family must bind the same actual port. */
    fun bindAll(
        network: SessionNetwork,
        addresses: List<NetworkEndpoint>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<TcpListener> {
        require(addresses.isNotEmpty())
        require(addresses.all { it.linkId == network.linkId })
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        for (candidate in candidates) {
            val listeners = mutableListOf<TcpListener>()
            try {
                for (address in addresses.distinct()) {
                    val port = listeners.firstOrNull()?.localEndpoint?.port ?: candidate
                    listeners.add(network.listen(NetworkEndpoint(address.address, port, network.linkId)))
                }
                val port = listeners.first().localEndpoint.port
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return listeners
            } catch (error: Throwable) {
                listeners.forEach { runCatching { it.close() }.exceptionOrNull()?.let(error::addSuppressed) }
                if (error is BackendFailure && error.kind == BackendFailure.Kind.ADDRESS_CONFLICT) continue
                throw error
            }
        }
        throw BackendFailure(BackendFailure.Kind.ADDRESS_CONFLICT)
    }

    /** Specific per-family listeners avoid relying on a platform's IPV6_V6ONLY default. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServerSocket> {
        require(addresses.isNotEmpty()) { "At least one listener address is required" }
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        for (candidate in candidates) {
            val servers = mutableListOf<ServerSocket>()
            try {
                for (address in addresses.distinct()) {
                    servers.add(bindPort(address, servers.firstOrNull()?.localPort ?: candidate))
                }
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                if (error is BindException) continue
                throw error
            }
            try {
                val port = servers.first().localPort
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return servers
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                throw error
            }
        }
        throw BindException("No common AirPlay port available for the selected interface addresses")
    }

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        return reportFallback(bindPort(address, 0), preferredPort, onFallback)
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? = try {
        bindPort(address, port)
    } catch (_: BindException) {
        null
    }

    private fun bindPort(address: InetAddress, port: Int): ServerSocket {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (error: Throwable) {
            closeAfterFailure(server, error)
            throw error
        }
    }

    private fun reportFallback(
        server: ServerSocket,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): ServerSocket = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // Ownership transfers to the caller only after notification succeeds.
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: ServerSocket, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}

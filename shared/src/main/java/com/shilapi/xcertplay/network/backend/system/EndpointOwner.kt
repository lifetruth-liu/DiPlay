package com.shilapi.xcertplay.network.backend.system

import com.shilapi.xcertplay.network.backend.SessionNetwork
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import com.shilapi.xcertplay.network.backend.*

/** Standalone legacy channels own a system adapter; session channels borrow their shared backend. */
internal class EndpointOwner(injected: SessionNetwork?, label: String, address: InetAddress? = null) : Closeable {
    private val owned = injected == null
    val network = injected ?: SystemSessionNetwork(label, (address as? Inet6Address)?.scopeId ?: 0)
    private val lock = Any()
    private var closed = false
    private val endpoints = mutableListOf<Closeable>()

    fun <T : Closeable> own(endpoint: T): T {
        synchronized(lock) {
            if (!closed) {
                endpoints.add(endpoint)
                return endpoint
            }
        }
        endpoint.close()
        throw BackendFailure(BackendFailure.Kind.CANCELLED)
    }

    fun release(endpoint: Closeable) {
        synchronized(lock) { endpoints.remove(endpoint) }
        endpoint.close()
    }

    private fun checkOpen() = synchronized(lock) {
        if (closed) throw BackendFailure(BackendFailure.Kind.CANCELLED)
    }

    fun listen(local: NetworkEndpoint, backlog: Int = 50, reuseAddress: Boolean = false): TcpListener {
        checkOpen()
        return own(network.listen(local, backlog, reuseAddress))
    }

    fun bindUdp(local: NetworkEndpoint): UdpEndpoint {
        checkOpen()
        return own(network.bindUdp(local))
    }

    fun accept(listener: TcpListener): TcpStream {
        checkOpen()
        return own(listener.accept())
    }

    fun acceptFrom(listener: TcpListener, expected: InetAddress, rejected: () -> Unit): TcpStream {
        while (true) {
            val stream = accept(listener)
            if (stream.remoteEndpoint.address.contentEquals(expected.address)) return stream
            release(stream)
            rejected()
        }
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            if (closed) return
            closed = true
            endpoints.toList().also { endpoints.clear() }
        }
        var failure: Throwable? = null
        for (endpoint in snapshot) {
            try { endpoint.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        try { if (owned) network.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

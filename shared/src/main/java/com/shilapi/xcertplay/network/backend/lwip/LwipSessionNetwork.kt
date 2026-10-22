package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit session adapter. It does not select or replace a production backend. */
internal class LwipSessionNetwork private constructor(
    private val runner: LwipPrototype,
    override val linkId: String,
) : SessionNetwork {
    override val clock: MonotonicClock get() = runner.commandClock
    private val closed = AtomicBoolean()
    private fun validate(endpoint: NetworkEndpoint) {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        require(endpoint.linkId == linkId && endpoint.address.size == 16)
    }
    override fun listen(local: NetworkEndpoint, backlog: Int, reuseAddress: Boolean): TcpListener {
        validate(local); return LwipTcpListener.bind(runner, local, backlog, reuseAddress)
    }
    override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream {
        validate(remote); return LwipTcpStream.connect(runner, remote, deadlineMillis)
    }
    override fun bindUdp(local: NetworkEndpoint): UdpEndpoint {
        validate(local); return LwipUdpEndpoint.bind(runner, local)
    }
    override fun close() {
        closed.set(true)
        // Repeated calls may finish a previous bounded join. Only the owner destroys the core.
        runner.close()
    }
    companion object {
        fun start(runner: LwipPrototype, linkId: String): LwipSessionNetwork = try {
            require(linkId.isNotBlank())
            runner.start()
            LwipSessionNetwork(runner, linkId)
        } catch (error: Throwable) {
            runner.close()
            if (error is OutOfMemoryError) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error)
            throw error
        }
        fun native(port: EthernetFramePort, mac: ByteArray, ipv6: ByteArray, linkId: String, mtu: Int = 1500): LwipSessionNetwork {
            require(mac.size == 6 && ipv6.size == 16 && mtu in 1280..1500 && linkId.isNotBlank())
            val ownedMac = mac.copyOf(); val ownedIp = ipv6.copyOf(); val clock = coreCommandClock()
            val runner = LwipPrototype(port, commandClock = clock) {
                NativePrototypeCore.create(ownedMac, ownedIp, mtu, echoServices = false, clockMillis = clock::nowMillis)
            }
            return start(runner, linkId)
        }
    }
}

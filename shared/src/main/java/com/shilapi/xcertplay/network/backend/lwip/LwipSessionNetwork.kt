package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit session adapter. It does not select or replace a production backend. */
internal class LwipSessionNetwork private constructor(
    private val runner: LwipPrototype,
    override val linkId: String,
    private val frameCounters: BackendRuntimeCountersProvider? = null,
) : SessionNetwork, BackendRuntimeCountersProvider {
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

    override fun backendRuntimeCounters(): BackendRuntimeCounters {
        val state = runner.snapshot()
        val frame = frameCounters?.backendRuntimeCounters() ?: BackendRuntimeCounters()
        return frame.copy(
            lwipRxDropped = state.rxDropped,
            lwipTxSent = state.txSent,
            lwipTxNotReady = state.txNotReady,
            rxQueueHighWaterBytes = maxOf(frame.rxQueueHighWaterBytes ?: 0L, state.rxQueueHighWaterBytes.toLong()),
            rxQueueHighWaterFrames = maxOf(frame.rxQueueHighWaterFrames ?: 0L, state.rxQueueHighWaterFrames.toLong()),
            txQueueHighWaterBytes = state.txQueueHighWaterBytes.toLong(),
            txQueueHighWaterFrames = state.txQueueHighWaterFrames.toLong(),
            commandHighWaterCount = state.commandHighWaterCount.toLong(),
            commandHighWaterBytes = state.commandHighWaterBytes.toLong(),
            directBufferCurrentBytes = state.directBufferLeasedBytes.toLong(),
            directBufferPeakBytes = state.directBufferPeakLeasedBytes.toLong(),
            liveResourceScopes = state.resourceScopes.toLong(),
        )
    }
    companion object {
        fun start(
            runner: LwipPrototype,
            linkId: String,
            frameCounters: BackendRuntimeCountersProvider? = null,
        ): LwipSessionNetwork = try {
            require(linkId.isNotBlank())
            runner.start()
            LwipSessionNetwork(runner, linkId, frameCounters)
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
            return start(runner, linkId, port as? BackendRuntimeCountersProvider)
        }
    }
}

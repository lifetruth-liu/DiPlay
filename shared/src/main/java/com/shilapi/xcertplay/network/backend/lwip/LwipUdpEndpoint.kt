package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Explicit prototype adapter; production SessionNetwork selection remains separate. */
internal class LwipUdpEndpoint private constructor(
    private val runner: LwipPrototype,
    private val scope: CoreCommandQueue.Scope,
    private val token: AtomicLong,
    override val localEndpoint: NetworkEndpoint,
) : UdpEndpoint, UdpQueueAgeControl {
    private val closed = AtomicBoolean()
    private val sending = AtomicBoolean()
    private val receiving = AtomicBoolean()
    private data class Payload(val lease: DirectBufferPool.Lease, val received: ReceivedDatagram)

    private inline fun <T> direction(gate: AtomicBoolean, operation: () -> T): T {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        if (!gate.compareAndSet(false, true)) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        try { return allocation { operation() } } finally { gate.set(false) }
    }

    override fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long) = direction(sending) {
        require(bytes.size <= MAX_PAYLOAD && destination.port != 0 && destination.linkId == localEndpoint.linkId)
        val leaseRef = AtomicReference<DirectBufferPool.Lease?>()
        runner.executePreparedCommand(bytes.size + 16, deadlineMillis, scope = scope,
            onRetired = { leaseRef.getAndSet(null)?.close() }) {
            val lease = runner.acquireDirectBuffer(bytes.size).also(leaseRef::set)
            val address = destination.address
            require(address.size == 16)
            val owned = lease.buffer
            owned.put(bytes)
            owned.flip()
            val operation: (LwipPrototypeCore) -> CoreAttempt<Unit> = {
                allocation { udp(it).udpSendDirect(token.get(), address, destination.port, owned, bytes.size) }
            }
            operation
        }
    }

    override fun receive(bytes: ByteArray, deadlineMillis: Long): ReceivedDatagram = direction(receiving) {
        val capacity = minOf(bytes.size, MAX_PAYLOAD)
        val leaseRef = AtomicReference<DirectBufferPool.Lease?>()
        runner.executePreparedDelivered<Payload, ReceivedDatagram>(capacity + RECEIVE_METADATA_BYTES, deadlineMillis, scope,
            deliver = { payload ->
                if (payload.received.copiedLength > 0) payload.lease.buffer.get(bytes, 0, payload.received.copiedLength)
                payload.received
            }, onRetired = { leaseRef.getAndSet(null)?.close() }) {
            val lease = runner.acquireDirectBuffer(capacity).also(leaseRef::set)
            val owned = lease.buffer
            val source = ByteArray(16)
            val metadata = IntArray(3)
            val operation: (LwipPrototypeCore) -> CoreAttempt<Payload> = { core -> allocation {
                when (udp(core).udpReceiveDirect(token.get(), owned, capacity, source, metadata)) {
                    CoreAttempt.Pending -> CoreAttempt.Pending
                    is CoreAttempt.Ready -> {
                        val (port, original, copied) = metadata
                        if (port !in 0..65535 || original !in 0..MAX_PAYLOAD || copied !in 0..minOf(original, capacity)) {
                            throw BackendFailure(BackendFailure.Kind.PROTOCOL)
                        }
                        CoreAttempt.Ready(Payload(lease, ReceivedDatagram(
                            NetworkEndpoint(source, port, localEndpoint.linkId), original, copied)))
                    }
                }
            } }
            operation
        }
    }

    override fun receiveBufferSize(requestedBytes: Int): Int {
        require(requestedBytes >= 0)
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        return runner.executeCommand(scope = scope) { CoreAttempt.Ready(0) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runner.closeResourceScope(scope)
    }
    override fun setReceiveMaxAge(maxAgeMillis: Long) = allocation {
        require(maxAgeMillis in 0..0xffff_ffffL)
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        runner.executeCommand(scope = scope) { core -> allocation {
            udp(core).udpSetQueueMaxAge(token.get(), maxAgeMillis)
            CoreAttempt.Ready(Unit)
        } }
    }
    override fun expiredDatagrams(): Long = allocation {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        runner.executeCommand(scope = scope) { core -> allocation {
            val count = udp(core).udpExpiredCount(token.get())
            if (count < 0) throw BackendFailure(BackendFailure.Kind.PROTOCOL)
            CoreAttempt.Ready(count)
        } }
    }

    companion object {
        private const val MAX_PAYLOAD = 65527
        private const val RECEIVE_METADATA_BYTES = 44 // source + three ints + copied endpoint address
        private fun udp(core: LwipPrototypeCore) = core as? LwipUdpCore
            ?: throw BackendFailure(BackendFailure.Kind.INTERNAL)
        private inline fun <T> allocation(block: () -> T): T = try { block() }
        catch (error: OutOfMemoryError) { throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error) }

        fun bind(runner: LwipPrototype, local: NetworkEndpoint): LwipUdpEndpoint = allocation {
            runner.checkCommandCaller()
            require(local.address.size == 16)
            val token = AtomicLong()
            val scope = runner.newResourceScope { core ->
                val value = token.getAndSet(0)
                if (value != 0L) udp(core).udpClose(value)
            }
            try {
                val bound = runner.executeCommand(64, scope = scope,
                    discard = { _: NetworkEndpoint -> runner.closeResourceScope(scope) }) { core -> allocation {
                    val native = udp(core)
                    val address = native.udpLocalAddress
                    val requested = local.address
                    require(requested.all { it == 0.toByte() } || requested.contentEquals(address))
                    val result = native.udpBind(local.port)
                    token.set(result.token)
                    if (result.token <= 0 || result.port !in 1..65535) throw BackendFailure(BackendFailure.Kind.INTERNAL)
                    CoreAttempt.Ready(NetworkEndpoint(address, result.port, local.linkId))
                } }
                LwipUdpEndpoint(runner, scope, token, bound)
            } catch (error: Throwable) {
                runner.closeResourceScope(scope)
                throw error
            }
        }
    }
}

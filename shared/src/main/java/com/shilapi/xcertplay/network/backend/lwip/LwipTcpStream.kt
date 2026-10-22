package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit prototype adapter. Production backend selection remains separate. */
internal class LwipTcpStream private constructor(
    private val runner: LwipPrototype,
    private val scope: CoreCommandQueue.Scope,
    private val lease: Lease,
    override val localEndpoint: NetworkEndpoint,
    override val remoteEndpoint: NetworkEndpoint,
) : TcpStream {
    private class Lease {
        val token = AtomicLong()
        val reset = AtomicBoolean()
        val published = AtomicBoolean()
    }
    private data class ReadResult(val bytes: ByteArray, val count: Int)
    private val closed = AtomicBoolean()
    private val reading = AtomicBoolean()
    private val writing = AtomicBoolean()
    private inline fun <T> direction(gate: AtomicBoolean, operation: () -> T): T {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        if (!gate.compareAndSet(false, true)) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        try { return allocation(operation) } finally { gate.set(false) }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int = direction(reading) {
        range(bytes, offset, length)
        val capacity = minOf(length, IO_CHUNK_BYTES)
        runner.executePreparedDelivered<ReadResult, Int>(capacity + 4, deadlineMillis, scope,
            deliver = { result ->
                try {
                    if (result.count > 0) result.bytes.copyInto(bytes, offset, 0, result.count)
                    result.count
                } catch (error: Throwable) { close(); throw error }
            }, discard = { result ->
                // A consumed stream prefix cannot be put back after cancellation/deadline.
                // Retiring the stream prevents a later read from silently skipping it.
                if (result.count > 0) close()
            }) {
            val owned = ByteArray(capacity)
            val operation: (LwipPrototypeCore) -> CoreAttempt<ReadResult> = { core -> try { allocation {
                if (capacity == 0) CoreAttempt.Ready(ReadResult(owned, 0))
                else when (val attempt = tcp(core).tcpRead(lease.token.get(), owned, 0, capacity)) {
                    CoreAttempt.Pending -> CoreAttempt.Pending
                    is CoreAttempt.Ready -> {
                        protocol(attempt.value == -1 || attempt.value in 1..capacity)
                        CoreAttempt.Ready(ReadResult(owned, attempt.value))
                    }
                }
            } } catch (error: Throwable) {
                retire(error as? BackendFailure ?: BackendFailure(BackendFailure.Kind.INTERNAL, error))
                throw error
            } }
            operation
        }
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = direction(writing) {
        range(bytes, offset, length)
        var written = 0
        do {
            val start = offset + written
            val count = minOf(length - written, IO_CHUNK_BYTES)
            val accepted = runner.executePreparedCommand(count + 4, deadlineMillis, scope = scope) {
                val owned = bytes.copyOfRange(start, start + count)
                val operation: (LwipPrototypeCore) -> CoreAttempt<Int> = { core -> allocation {
                    when (val attempt = tcp(core).tcpWrite(lease.token.get(), owned, 0, count)) {
                        CoreAttempt.Pending -> CoreAttempt.Pending
                        is CoreAttempt.Ready -> {
                            protocol(if (count == 0) attempt.value == 0 else attempt.value in 1..count)
                            attempt
                        }
                    }
                } }
                operation
            }
            written += accepted
        } while (written < length)
    }
    override fun shutdownOutput() = direction(writing) {
        runner.executeCommand(scope = scope) { core -> allocation { tcp(core).tcpShutdownOutput(lease.token.get()) } }
    }
    override fun configure(options: TcpOptions) {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        runner.executeCommand(scope = scope) { lease.reset.set(options.resetOnClose); CoreAttempt.Ready(Unit) }
    }
    override fun configureLiveness(diagnostic: (String) -> Unit) {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        runner.executeCommand(scope = scope) { core -> allocation {
            tcp(core).tcpConfigureLiveness(lease.token.get()); CoreAttempt.Ready(Unit)
        } }
        diagnostic("lwIP TCP peer health enabled idle=10s interval=3s count=3; protocol retransmission deadlines apply")
    }
    override fun close() {
        retire(BackendFailure(BackendFailure.Kind.CANCELLED))
    }
    private fun retire(reason: BackendFailure) {
        if (closed.compareAndSet(false, true)) runner.closeResourceScope(scope, reason)
    }
    companion object {
        private const val IO_CHUNK_BYTES = 32768
        private fun tcp(core: LwipPrototypeCore) = core as? LwipTcpCore ?: throw BackendFailure(BackendFailure.Kind.INTERNAL)
        private inline fun <T> allocation(block: () -> T): T = try { block() }
        catch (error: OutOfMemoryError) { throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error) }
        private fun protocol(valid: Boolean) { if (!valid) throw BackendFailure(BackendFailure.Kind.PROTOCOL) }
        private fun range(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && length <= bytes.size && offset <= bytes.size - length)
        }
        fun connect(runner: LwipPrototype, remote: NetworkEndpoint, deadlineMillis: Long = 0): LwipTcpStream = allocation {
            runner.checkCommandCaller()
            require(remote.port != 0)
            val lease = Lease()
            val scope = runner.newResourceScope { core ->
                val token = lease.token.getAndSet(0)
                if (token > 0) {
                    if (lease.published.get() && !lease.reset.get()) tcp(core).tcpCloseGraceful(token)
                    else tcp(core).tcpClose(token)
                }
            }
            try {
                val peer = runner.executePreparedCommand<LwipTcpCore.Peer>(96, deadlineMillis, scope = scope,
                    discard = { runner.closeResourceScope(scope) }) {
                    val address = remote.address
                    require(address.size == 16)
                    val operation: (LwipPrototypeCore) -> CoreAttempt<LwipTcpCore.Peer> = { core -> allocation {
                        val native = tcp(core)
                        if (lease.token.get() == 0L) when (val candidate = native.tcpConnect(address, remote.port)) {
                            CoreAttempt.Pending -> Unit
                            is CoreAttempt.Ready -> { lease.token.set(candidate.value); protocol(candidate.value > 0) }
                        }
                        if (lease.token.get() == 0L) CoreAttempt.Pending
                        else when (val state = native.tcpConnectStatus(lease.token.get())) {
                            CoreAttempt.Pending -> CoreAttempt.Pending
                            is CoreAttempt.Ready -> {
                                val info = state.value
                                protocol(info.localAddress.size == 16 && info.remoteAddress.contentEquals(address) &&
                                    info.localPort in 1..65535 && info.remotePort == remote.port)
                                state
                            }
                        }
                    } }
                    operation
                }
                val stream = LwipTcpStream(runner, scope, lease,
                    NetworkEndpoint(peer.localAddress, peer.localPort, remote.linkId),
                    NetworkEndpoint(peer.remoteAddress, peer.remotePort, remote.linkId))
                lease.published.set(true)
                stream
            } catch (error: Throwable) { runner.closeResourceScope(scope); throw error }
        }
    }
}

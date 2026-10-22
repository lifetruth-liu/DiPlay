package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit prototype listener. Accepted streams own independent resource scopes. */
internal class LwipTcpListener private constructor(
    private val runner: LwipPrototype,
    private val scope: CoreCommandQueue.Scope,
    private val token: AtomicLong,
    override val localEndpoint: NetworkEndpoint,
) : TcpListener {
    private val closed = AtomicBoolean()
    private val accepting = AtomicBoolean()
    override fun accept(deadlineMillis: Long): TcpStream {
        if (closed.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        if (!accepting.compareAndSet(false, true)) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        try { return allocation { LwipTcpStream.acceptFrom(runner, scope, token.get(), localEndpoint, deadlineMillis) } }
        finally { accepting.set(false) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) runner.closeResourceScope(scope)
    }
    companion object {
        private fun tcp(core: LwipPrototypeCore) = core as? LwipTcpCore ?: throw BackendFailure(BackendFailure.Kind.INTERNAL)
        private inline fun <T> allocation(block: () -> T): T = try { block() }
        catch (error: OutOfMemoryError) { throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error) }
        fun bind(runner: LwipPrototype, local: NetworkEndpoint, backlog: Int = 50): LwipTcpListener = allocation {
            runner.checkCommandCaller()
            require(backlog > 0 && local.address.size == 16)
            val token = AtomicLong()
            val scope = runner.newResourceScope { core ->
                val value = token.getAndSet(0)
                if (value > 0) tcp(core).tcpListenerClose(value)
            }
            try {
                val bound = runner.executeCommand<NetworkEndpoint>(64, scope = scope,
                    discard = { runner.closeResourceScope(scope) }) { core -> allocation {
                    val native = tcp(core)
                    val address = native.tcpLocalAddress
                    val requested = local.address
                    require(requested.all { it == 0.toByte() } || requested.contentEquals(address))
                    val result = native.tcpListen(local.port, minOf(backlog, 32))
                    token.set(result.token)
                    if (result.token <= 0 || result.port !in 1..65535 ||
                        (local.port != 0 && result.port != local.port)) throw BackendFailure(BackendFailure.Kind.PROTOCOL)
                    CoreAttempt.Ready(NetworkEndpoint(address, result.port, local.linkId))
                } }
                LwipTcpListener(runner, scope, token, bound)
            } catch (error: Throwable) { runner.closeResourceScope(scope); throw error }
        }
    }
}

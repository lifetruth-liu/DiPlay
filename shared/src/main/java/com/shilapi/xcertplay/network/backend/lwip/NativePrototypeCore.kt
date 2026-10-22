package com.shilapi.xcertplay.network.backend.lwip

import android.os.SystemClock
import com.shilapi.xcertplay.network.backend.BackendFailure
import java.security.SecureRandom

/** Explicit P2 echo core on a virtual netif; not the production SessionNetwork implementation. */
internal class NativePrototypeCore private constructor(private val clockMillis: () -> Long, private val address: ByteArray) : LwipUdpCore {
    data class Stats(
        val queuedFrames: Int, val entropyWords: Int, val configuredBytes: Int,
        val heapUsed: Int, val pbufUsed: Int, val tcpListeners: Int, val udpEndpoints: Int,
        val failure: Int, val tcpActive: Int, val txRejected: Long,
    )
    private val owner = Thread.currentThread()
    private val random = SecureRandom()
    private val entropy = ByteArray(1024)
    private val output = ByteArray(1514)
    private var handle = 0L

    private fun checkOwner() {
        if (Thread.currentThread() !== owner) throw BackendFailure(BackendFailure.Kind.INTERNAL)
        if (handle == 0L) throw BackendFailure(BackendFailure.Kind.CANCELLED)
    }

    private fun supply() {
        checkOwner()
        val remaining = LwipNativeBindings.entropyRemaining(handle)
        checkResult(remaining.takeIf { it < 0 } ?: 0)
        val refill = if (remaining < 128) entropy.also { random.nextBytes(it) } else null
        val now = clockMillis()
        check(now >= 0) { "negative monotonic clock" }
        try { checkResult(LwipNativeBindings.supply(handle, now, refill)) }
        finally { entropy.fill(0) }
    }

    override fun prepare() = nativeCall {
        supply()
        checkResult(LwipNativeBindings.prepareEcho(handle), initializing = true)
    }
    override fun setLinkReady(ready: Boolean) = nativeCall {
        supply(); checkResult(LwipNativeBindings.setReady(handle, ready))
    }
    override fun input(frame: ByteArray) = nativeCall {
        supply()
        val result = LwipNativeBindings.input(handle, frame)
        // Non-IPv6 or malformed frames and bounded pbuf pressure are packet drops, not link failures.
        when (result) { -1, -2, -7 -> Unit; else -> checkResult(result) }
    }
    override fun poll() = nativeCall { supply(); checkResult(LwipNativeBindings.poll(handle)) }
    override fun nextTimeoutMillis(): Long = nativeCall {
        supply()
        LwipNativeBindings.nextTimeout(handle).also { if (it < 0) checkResult(it.toInt()) }
    }
    override fun takeOutput(): ByteArray? = nativeCall {
        checkOwner()
        val length = LwipNativeBindings.takeOutput(handle, output)
        checkResult(length.takeIf { it < 0 } ?: 0)
        if (length == 0) null else output.copyOf(length)
    }
    fun stats(): Stats = nativeCall {
        checkOwner()
        val s = LwipNativeBindings.stats(handle)
        check(s.size == 11) { "Invalid prototype stats ABI" }
        checkResult(s[0].toInt())
        Stats(s[1].toInt(), s[2].toInt(), s[3].toInt(), s[4].toInt(), s[5].toInt(),
            s[6].toInt(), s[7].toInt(), s[8].toInt(), s[9].toInt(), s[10])
    }
    override val udpLocalAddress: ByteArray get() { checkOwner(); return address.copyOf() }
    override fun udpBind(port: Int): LwipUdpCore.Binding = nativeCall {
        supply()
        val result = LongArray(2)
        checkResult(LwipNativeBindings.udpBind(handle, port, result))
        try {
            check(result[0] > 0 && result[1] in 1L..65535L) { "Invalid UDP binding metadata" }
            LwipUdpCore.Binding(result[0], result[1].toInt())
        } catch (error: Throwable) {
            if (result[0] > 0) checkResult(LwipNativeBindings.udpClose(handle, result[0]))
            throw error
        }
    }
    override fun udpSend(token: Long, address: ByteArray, port: Int, bytes: ByteArray): CoreAttempt<Unit> = nativeCall {
        supply()
        udpAttempt(LwipNativeBindings.udpSend(handle, token, address, port, bytes, 0, bytes.size))
    }
    override fun udpReceive(token: Long, bytes: ByteArray, source: ByteArray, metadata: IntArray): CoreAttempt<Unit> = nativeCall {
        supply()
        udpAttempt(LwipNativeBindings.udpReceive(handle, token, bytes, source, metadata))
    }
    override fun udpClose(token: Long) = nativeCall {
        supply(); checkResult(LwipNativeBindings.udpClose(handle, token))
    }
    override fun close() = nativeCall {
        if (Thread.currentThread() !== owner) throw BackendFailure(BackendFailure.Kind.INTERNAL)
        if (handle != 0L) {
            checkResult(LwipNativeBindings.destroy(handle))
            handle = 0
        }
        entropy.fill(0); output.fill(0)
    }

    companion object {
        fun create(mac: ByteArray, ip: ByteArray, mtu: Int, clockMillis: () -> Long = SystemClock::elapsedRealtime): NativePrototypeCore = nativeCall {
            require(mac.size == 6 && ip.size == 16 && mtu in 1280..1500)
            LwipNativeBindings.ensureLoaded()
            val core = NativePrototypeCore(clockMillis, ip.copyOf())
            try {
                core.random.nextBytes(core.entropy)
                val now = clockMillis()
                require(now >= 0)
                val result = LwipNativeBindings.create(mac.copyOf(), ip.copyOf(), mtu, now, core.entropy)
                if (result <= 0) checkResult(result.toInt(), initializing = true)
                check(result > 0) { "Invalid zero native token" }
                core.handle = result
                core
            } finally { core.entropy.fill(0) }
        }

        private fun checkResult(status: Int, initializing: Boolean = false) {
            if (status == 0) return
            val kind = when (status) {
                -2 -> BackendFailure.Kind.RESOURCE_LIMIT
                -5, -10 -> BackendFailure.Kind.ADDRESS_CONFLICT
                -9 -> BackendFailure.Kind.USB_IO
                else -> if (initializing) BackendFailure.Kind.BACKEND_INIT else BackendFailure.Kind.INTERNAL
            }
            throw BackendFailure(kind, IllegalStateException("lwIP status $status"))
        }
        private fun udpAttempt(status: Int): CoreAttempt<Unit> {
            if (status == -11) return CoreAttempt.Pending
            checkResult(status)
            return CoreAttempt.Ready(Unit)
        }
        private inline fun <T> nativeCall(block: () -> T): T = try { block() }
        catch (error: LinkageError) { throw BackendFailure(BackendFailure.Kind.NATIVE_UNAVAILABLE, error) }
        catch (error: OutOfMemoryError) { throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error) }
    }
}

package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.math.BigInteger
import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay timing-port clock sync.
 *
 * The accessory drives a two-way RTCP-style exchange with the phone: PT_REQUEST (210) carries our
 * transmit timestamp and PT_RESPONSE (211) echoes it while adding the phone's receive and
 * transmit stamps. The resulting offset and round-trip time steer a local monotonic clock onto
 * the phone's media-clock domain, which /feedback reports.
 *
 * An injected network is borrowed; only this clock's UDP endpoint is closed. The default
 * system adapter is owned by the clock and requires the accepted peer's scoped address for
 * link-local traffic. Closing is terminal, including when no listener was created.
 */
class NtpClock(
    private val injectedNetwork: SessionNetwork? = null,
    systemPeer: InetAddress? = null,
) : Closeable {
    // The legacy system path must retain the accepted socket's interface scope. An injected
    // backend owns its own addressing context and must never use an Android interface index.
    private val network = injectedNetwork ?: SystemSessionNetwork(
        "airplay-timing", (systemPeer as? Inet6Address)?.scopeId ?: 0,
    )
    private val running = AtomicBoolean(false)
    private var closed = false
    private val socketLock = Any()
    private val clockLock = Any()
    private val sendLock = Any()

    private var socket: UdpEndpoint? = null
    private var receiver: Thread? = null
    private var sender: Thread? = null
    private var peer: NetworkEndpoint? = null

    private var clockOffsetNs = wallClockNtpOffsetNs(System.nanoTime())
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: BigInteger? = null
    private var synced = false

    fun listen(): Int = synchronized(socketLock) {
        check(!closed) { "NtpClock is closed" }
        check(!running.getAndSet(true)) { "NtpClock is already running" }
        try {
            val bound = network.bindUdp(NetworkEndpoint(ByteArray(16), 0, network.linkId))
            socket = bound
            receiver = Thread(::runReceiver, "airplay-ntp-rx").apply { isDaemon = true; start() }
            bound.localEndpoint.port
        } catch (error: Exception) {
            running.set(false)
            socket?.close()
            socket = null
            throw error
        }
    }

    fun start(peerAddress: InetAddress, port: Int) {
        require(port > 0) { "timing port must be positive" }
        peer = NetworkEndpoint(peerAddress.address, port, network.linkId)
        sender = Thread(::runSender, "airplay-ntp-tx").apply { isDaemon = true; start() }
    }

    /** Now in the phone's synchronized clock domain, as an unsigned NTP64 value. */
    fun syncedNtp(): BigInteger = synchronized(clockLock) {
        ntpFromNanos(System.nanoTime() + clockOffsetNs)
    }

    override fun close() {
        synchronized(socketLock) {
            closed = true
            running.set(false)
            socket?.close()
            socket = null
        }
        sender?.interrupt()
        receiver?.interrupt()
        if (injectedNetwork == null) network.close()
    }

    private fun runSender() {
        while (running.get()) {
            try {
                sendRequest()
                Thread.sleep(REQUEST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(REQUEST_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun sendRequest() {
        val destination = peer ?: return
        val packet = ByteArray(NTP_PACKET_BYTES)
        packet[0] = 0x80.toByte()
        packet[1] = PT_REQUEST.toByte()
        writeU16Be(packet, 2, 7)
        val t1 = syncedNtp()
        synchronized(clockLock) { pendingT1 = t1 }
        ntpBytes(t1).copyInto(packet, NTP_TRANSMIT_OFFSET)
        send(packet, destination)
    }

    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running.get()) {
            val sock = currentSocket() ?: return
            val packet = try {
                sock.receive(buffer)
            } catch (failure: BackendFailure) {
                if (failure.kind == BackendFailure.Kind.TIMEOUT && running.get()) continue
                running.set(false)
                return
            } catch (_: Exception) {
                if (running.get()) continue else return
            }
            if (!packet.truncated && packet.copiedLength >= NTP_PACKET_BYTES) {
                try {
                    handleMessage(buffer.copyOf(packet.copiedLength), packet.source)
                } catch (_: Exception) {
                    if (!running.get()) return
                }
            }
        }
    }

    private fun handleMessage(message: ByteArray, source: NetworkEndpoint) {
        when (message[1].toInt() and 0xff) {
            PT_REQUEST -> respondToRequest(message, source)
            PT_RESPONSE -> handleResponse(message)
        }
    }

    private fun respondToRequest(request: ByteArray, source: NetworkEndpoint) {
        val response = ByteArray(NTP_PACKET_BYTES)
        response[0] = 0x80.toByte()
        response[1] = PT_RESPONSE.toByte()
        writeU16Be(response, 2, 7)
        request.copyInto(response, NTP_ORIGINATE_OFFSET, NTP_TRANSMIT_OFFSET, NTP_PACKET_BYTES)
        ntpBytes(syncedNtp()).copyInto(response, NTP_RECEIVE_OFFSET)
        ntpBytes(syncedNtp()).copyInto(response, NTP_TRANSMIT_OFFSET)
        send(response, source)
    }

    private fun send(bytes: ByteArray, destination: NetworkEndpoint) = synchronized(sendLock) {
        currentSocket()?.send(bytes, destination)
    }

    private fun handleResponse(response: ByteArray) {
        val t4 = syncedNtp()
        val t1 = readNtp(response, NTP_ORIGINATE_OFFSET)
        val t2 = readNtp(response, NTP_RECEIVE_OFFSET)
        val t3 = readNtp(response, NTP_TRANSMIT_OFFSET)

        synchronized(clockLock) {
            val pending = pendingT1
            if (pending == null || pending != t1) return
            pendingT1 = null
        }

        val offset = t2.subtract(t1).add(t3.subtract(t4)).toDouble() * 0.5 / TWO32_DOUBLE
        val rtt = t4.subtract(t1).subtract(t3.subtract(t2)).toDouble() / TWO32_DOUBLE
        if (rtt < 0.0) return

        if (rtt < pickRtt) {
            pickRtt = rtt
            pickOffset = offset
        }
        if (--pickCount > 0) return

        val selectedRtt = pickRtt
        val selectedOffset = pickOffset
        pickCount = PICK_COUNT
        pickRtt = Double.POSITIVE_INFINITY

        synchronized(clockLock) {
            val useSample = delays.all { selectedRtt <= it }
            delays[delayIndex] = selectedRtt
            delayIndex = (delayIndex + 1) % DELAY_WINDOW
            if (useSample) applyOffset(selectedOffset)
        }
    }

    private fun applyOffset(offsetSec: Double) {
        val stepping = !synced || Math.abs(offsetSec) > STEP_THRESHOLD_SEC
        val applied = if (stepping) offsetSec else offsetSec * SLEW_GAIN
        clockOffsetNs += Math.round(applied * 1e9)
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            pendingT1 = null
            synced = true
        }
    }

    private fun currentSocket(): UdpEndpoint? = synchronized(socketLock) { socket }

    private companion object {
        const val PT_REQUEST = 210
        const val PT_RESPONSE = 211
        const val NTP_PACKET_BYTES = 32
        const val NTP_ORIGINATE_OFFSET = 8
        const val NTP_RECEIVE_OFFSET = 16
        const val NTP_TRANSMIT_OFFSET = 24
        const val REQUEST_INTERVAL_MS = 1_000L
        const val RECEIVE_BUFFER_BYTES = 2_048
        const val STEP_THRESHOLD_SEC = 0.128
        const val SLEW_GAIN = 1.0 / 8.0
        const val DELAY_WINDOW = 8
        const val PICK_COUNT = 2
        const val TWO32_DOUBLE = 0x1_0000_0000L.toDouble()
    }
}

private const val NTP_EPOCH_OFFSET = 2_208_988_800L
private val TWO32 = BigInteger.ONE.shiftLeft(32)
private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)

private fun wallClockNtpOffsetNs(monoNs: Long): Long {
    val ntpNs = ntp64Now()
        .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        .multiply(NANOS_PER_SECOND)
        .shiftRight(32)
    return ntpNs.subtract(BigInteger.valueOf(monoNs)).toLong()
}

private fun ntp64Now(): BigInteger {
    val seconds = System.currentTimeMillis() / 1000.0 + NTP_EPOCH_OFFSET
    val whole = Math.floor(seconds).toLong()
    val fraction = Math.floor((seconds - whole) * TWO32.toDouble()).toLong()
    return BigInteger.valueOf(whole).shiftLeft(32).or(BigInteger.valueOf(fraction))
}

private fun ntpFromNanos(ns: Long): BigInteger {
    val seconds = BigInteger.valueOf(Math.floorDiv(ns, 1_000_000_000L))
    val nanos = BigInteger.valueOf(Math.floorMod(ns, 1_000_000_000L))
    return seconds.shiftLeft(32).or(nanos.shiftLeft(32).divide(NANOS_PER_SECOND))
}

private fun ntpBytes(value: BigInteger): ByteArray {
    val bytes = value.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).toByteArray()
    return ByteArray(8).also { target ->
        val source = if (bytes.size > 8) bytes.copyOfRange(bytes.size - 8, bytes.size) else bytes
        source.copyInto(target, target.size - source.size)
    }
}

private fun readNtp(value: ByteArray, offset: Int): BigInteger =
    BigInteger(1, value.copyOfRange(offset, offset + 8))

private fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xff).toByte()
    target[offset + 1] = (value and 0xff).toByte()
}

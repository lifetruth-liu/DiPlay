package com.shilapi.xcertplay.transport

/**
 * Counts the Ethernet frames on the NCM link, so a wired attempt that never gets its AirPlay session can tell an
 * iPhone that never tried (no frames, or no TCP connection attempt) from one whose packets arrive but do not reach
 * DiPlay's listener (#889, #890). Only headers are read, never payloads. Free of Android types for JVM tests.
 */
internal class NcmTrafficCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private var fromIphone = 0L
    private var ipv6 = 0L
    private var ipv4 = 0L
    private var toIphone = 0L
    private val synsByPort = LinkedHashMap<Int, Int>()
    private val lastSynNanosByPort = HashMap<Int, Long>()

    @Synchronized fun received(frame: ByteArray): NcmFrameHeader {
        fromIphone++
        val header = NcmFrameHeader.read(frame)
        when (header.etherType) {
            0x86dd -> ipv6++
            0x0800 -> ipv4++
        }
        val port = header.destinationPort
        if (header.tcpSyn && port != null && (port in synsByPort || synsByPort.size < MAX_PORTS)) {
            synsByPort[port] = (synsByPort[port] ?: 0) + 1
            lastSynNanosByPort[port] = nanoTime()
        }
        return header
    }

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos] ([System.nanoTime] time). */
    @Synchronized fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean =
        lastSynNanosByPort[port]?.let { it - sinceNanos >= 0 } == true

    @Synchronized fun sent() {
        toIphone++
    }

    /** One line for the report; [airPlayPort] is DiPlay's listener, whose connection attempts are named apart. */
    @Synchronized fun summary(airPlayPort: Int?): String {
        val toAirPlay = airPlayPort?.let { synsByPort[it] } ?: 0
        val others = synsByPort.filterKeys { it != airPlayPort }.entries.joinToString(",") { "${it.key}:${it.value}" }
        return "fromIphone=$fromIphone ipv6=$ipv6 ipv4=$ipv4 tcpConnectToAirPlay=$toAirPlay " +
            "tcpConnectOther=${others.ifEmpty { "none" }} toIphone=$toIphone"
    }

    private companion object { const val MAX_PORTS = 8 }
}

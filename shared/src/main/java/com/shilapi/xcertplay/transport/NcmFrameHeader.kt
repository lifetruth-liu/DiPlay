package com.shilapi.xcertplay.transport

/** Bounded Ethernet/IP/TCP header parsing shared by counters and diagnostics. No addresses or payload. */
internal data class NcmFrameHeader(val etherType: Int?, val tcpFlags: Int?, val destinationPort: Int?) {
    val tcpSyn: Boolean get() = tcpFlags?.let { it and 2 != 0 && it and 16 == 0 } == true

    override fun toString(): String = "etherType=${etherType ?: "unknown"}" +
        if (tcpFlags != null) " tcpFlags=$tcpFlags tcpSyn=$tcpSyn dstPort=$destinationPort" else ""

    companion object {
        fun read(frame: ByteArray): NcmFrameHeader {
            val etherType = be16(frame, 12)
            val tcpOffset = when (etherType) {
                // Extension headers are deliberately not interpreted as a direct TCP header.
                0x86dd -> if (u8(frame, 20) == 6) 54 else null
                0x0800 -> {
                    val ihl = ((u8(frame, 14) ?: 0) and 15) * 4
                    if (ihl >= 20 && u8(frame, 23) == 6) 14 + ihl else null
                }
                else -> null
            }?.takeIf { frame.size >= it + 20 }
            return NcmFrameHeader(etherType, tcpOffset?.let { u8(frame, it + 13) },
                tcpOffset?.let { be16(frame, it + 2) })
        }

        private fun u8(bytes: ByteArray, offset: Int) = bytes.getOrNull(offset)?.toInt()?.and(255)
        private fun be16(bytes: ByteArray, offset: Int): Int? =
            u8(bytes, offset)?.let { high -> u8(bytes, offset + 1)?.let { (high shl 8) or it } }
    }
}

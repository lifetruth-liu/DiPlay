package com.shilapi.xcertplay.transport

/** Header-only transport evidence. Samples are bounded; USB return codes are not interpreted as NAKs. */
internal class NcmTransferDiagnostics(private val nanoTime: () -> Long = System::nanoTime) {
    private val createdAt = nanoTime()
    private var writes = 0L
    private var success = 0L
    private var notReady = 0L
    private var short = 0L
    private var writeNanos = 0L
    private var maxWriteNanos = 0L
    private var firstSuccessMs: Long? = null
    private var rxFrames = 0L
    private var firstSyn = false
    private var notifications = 0L
    private var lastTxSample = createdAt
    private var lastRxSample = createdAt
    private var lastStatusSample = createdAt

    @Synchronized fun write(requested: Int, returned: Int, timeoutMs: Int, durationNanos: Long, frame: ByteArray): String? {
        val now = nanoTime()
        writes++
        writeNanos += durationNanos.coerceAtLeast(0)
        maxWriteNanos = maxOf(maxWriteNanos, durationNanos)
        val first = returned == requested && firstSuccessMs == null
        when {
            returned == requested -> { success++; if (first) firstSuccessMs = millis(now - createdAt) }
            returned <= 0 -> notReady++
            else -> short++
        }
        if (returned == requested && !first && writes > 4) return null
        if (writes > 4 && !first && returned <= 0 && now - lastTxSample < SAMPLE_NANOS) return null
        lastTxSample = now
        return "NCM transfer tx returned=$returned requested=$requested timeoutMs=$timeoutMs " +
            "durationMs=${millis(durationNanos)} ${header(frame)} ${summary()}"
    }

    @Synchronized fun received(frame: ByteArray, header: NcmFrameHeader = NcmFrameHeader.read(frame)): String? {
        val now = nanoTime()
        rxFrames++
        if (firstSuccessMs != null && firstSyn) return null
        val newSyn = !firstSyn && header.tcpSyn
        if (newSyn) firstSyn = true
        if (firstSuccessMs != null && !newSyn) return null
        if (rxFrames > 4 && !newSyn && now - lastRxSample < SAMPLE_NANOS) return null
        lastRxSample = now
        return "NCM transfer rx bytes=${frame.size} $header ${summary()}"
    }

    @Synchronized fun status(buffer: ByteArray, length: Int): String? {
        val now = nanoTime()
        notifications++
        if (notifications > 8 && now - lastStatusSample < SAMPLE_NANOS) return null
        lastStatusSample = now
        val fields = if (length >= 8 && buffer.size >= 8)
            "notification=${u8(buffer, 1)} value=${le16(buffer, 2)} index=${le16(buffer, 4)} length=${le16(buffer, 6)}"
        else "truncated=true"
        return "NCM transfer status bytes=$length $fields notifications=$notifications bridgeMs=${millis(now - createdAt)}"
    }

    @Synchronized fun summary(): String =
        "bridgeMs=${millis(nanoTime() - createdAt)} attempts=$writes success=$success notReady=$notReady short=$short " +
            "writeTotalMs=${millis(writeNanos)} writeMaxMs=${millis(maxWriteNanos)} " +
            "firstSuccessMs=${firstSuccessMs ?: "none"} rxFrames=$rxFrames notifications=$notifications"

    private fun header(frame: ByteArray): String = NcmFrameHeader.read(frame).toString()

    private fun u8(bytes: ByteArray, offset: Int): Int? = bytes.getOrNull(offset)?.toInt()?.and(255)
    private fun le16(bytes: ByteArray, offset: Int): Int? =
        u8(bytes, offset)?.let { low -> u8(bytes, offset + 1)?.let { low or (it shl 8) } }
    private fun millis(nanos: Long) = nanos.coerceAtLeast(0) / 1_000_000L

    companion object { private const val SAMPLE_NANOS = 1_000_000_000L }
}

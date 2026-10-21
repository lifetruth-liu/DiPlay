package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable

/**
 * Blocking Ethernet I/O owned exclusively by one backend. Read and write use separate
 * I/O workers, never the lwIP core thread. close must cancel a pending receive.
 * A successful send means the entire USB block was transferred, not merely queued.
 */
interface EthernetFramePort : Closeable {
    val hostMac: ByteArray?
    fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult
    fun recv(timeoutMillis: Long): ByteArray?
}

/** Takes ownership of [bridge]; it must no longer be shared with a VPN bridge. */
class NcmFramePort(private val bridge: NcmUsbBridge) : EthernetFramePort {
    override val hostMac: ByteArray? get() = bridge.hostMac

    override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (frame.size !in ETHERNET_HEADER_BYTES..MAX_FRAME_BYTES) {
            return NcmSendResult.Failed(IphoneUsbException.Protocol("Invalid Ethernet frame length: ${frame.size}"))
        }
        return bridge.sendFrame(frame, timeoutMillis)
    }

    override fun recv(timeoutMillis: Long): ByteArray? = bridge.recv(timeoutMillis)
    override fun close() = bridge.close()

    private companion object {
        const val ETHERNET_HEADER_BYTES = 14
        const val MAX_FRAME_BYTES = 1514
    }
}

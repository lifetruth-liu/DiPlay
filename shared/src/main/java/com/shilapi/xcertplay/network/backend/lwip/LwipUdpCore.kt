package com.shilapi.xcertplay.network.backend.lwip

import java.nio.ByteBuffer

/** Owner-thread, nonblocking UDP boundary. Payload and metadata arrays are borrowed per call. */
internal interface LwipUdpCore : LwipPrototypeCore {
    data class Binding(val token: Long, val port: Int)
    val udpLocalAddress: ByteArray
    fun udpBind(port: Int): Binding
    fun udpSend(token: Long, address: ByteArray, port: Int, bytes: ByteArray): CoreAttempt<Unit>
    /** Payload storage is borrowed only until the owner-thread call returns. */
    fun udpSendDirect(token: Long, address: ByteArray, port: Int, buffer: ByteBuffer, length: Int): CoreAttempt<Unit> {
        require(length >= 0 && length <= buffer.capacity())
        val copy = ByteArray(length)
        for (i in 0 until length) copy[i] = buffer.get(i)
        return udpSend(token, address, port, copy)
    }
    fun udpReceive(token: Long, bytes: ByteArray, source: ByteArray, metadata: IntArray): CoreAttempt<Unit>
    fun udpReceiveDirect(token: Long, buffer: ByteBuffer, length: Int, source: ByteArray,
                         metadata: IntArray): CoreAttempt<Unit> {
        require(length >= 0 && length <= buffer.capacity())
        val copy = ByteArray(length)
        return when (val attempt = udpReceive(token, copy, source, metadata)) {
            CoreAttempt.Pending -> CoreAttempt.Pending
            is CoreAttempt.Ready -> {
                val copied = metadata.getOrNull(2) ?: 0
                if (copied in 1..length) for (i in 0 until copied) buffer.put(i, copy[i])
                attempt
            }
        }
    }
    fun udpClose(token: Long)
    fun udpSetQueueMaxAge(token: Long, maxAgeMillis: Long)
    fun udpExpiredCount(token: Long): Long
}

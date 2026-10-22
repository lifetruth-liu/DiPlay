package com.shilapi.xcertplay.network.backend.lwip

/** Owner-thread, nonblocking UDP boundary. Payload and metadata arrays are borrowed per call. */
internal interface LwipUdpCore : LwipPrototypeCore {
    data class Binding(val token: Long, val port: Int)
    val udpLocalAddress: ByteArray
    fun udpBind(port: Int): Binding
    fun udpSend(token: Long, address: ByteArray, port: Int, bytes: ByteArray): CoreAttempt<Unit>
    fun udpReceive(token: Long, bytes: ByteArray, source: ByteArray, metadata: IntArray): CoreAttempt<Unit>
    fun udpClose(token: Long)
    fun udpSetQueueMaxAge(token: Long, maxAgeMillis: Long)
    fun udpExpiredCount(token: Long): Long
}

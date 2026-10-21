package com.shilapi.xcertplay.network.backend

import java.io.Closeable

data class ReceivedDatagram(val source: NetworkEndpoint, val originalLength: Int, val copiedLength: Int) {
    val truncated: Boolean get() = copiedLength < originalLength
}

interface UdpEndpoint : Closeable {
    val localEndpoint: NetworkEndpoint
    /** One sender and receiver may run concurrently; overlapping same-direction calls fail.
     * A send timeout may invalidate the endpoint to cancel a blocked kernel send.
     */
    fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long = 0)
    fun receive(bytes: ByteArray, deadlineMillis: Long = 0): ReceivedDatagram
}

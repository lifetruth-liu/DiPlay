package com.shilapi.xcertplay.network.backend.lwip

import java.nio.ByteBuffer

/** Owner-thread nonblocking TCP boundary. Arguments are borrowed for one call.
 * Returned address arrays are owned by the caller. Close aborts locally; closeGraceful consumes
 * the handle and transfers protocol closing to lwIP. Neither waits for a peer ACK or deadline.
 */
internal interface LwipTcpCore : LwipPrototypeCore {
    val tcpLocalAddress: ByteArray
    data class Listener(val token: Long, val port: Int)
    data class Peer(val localAddress: ByteArray, val localPort: Int, val remoteAddress: ByteArray, val remotePort: Int)
    data class Accepted(val token: Long, val peer: Peer)
    fun tcpListen(port: Int, backlog: Int, reuseAddress: Boolean = false): Listener
    fun tcpAccept(listener: Long): CoreAttempt<Accepted>
    /** Ready means a candidate was created, not that its handshake completed. */
    fun tcpConnect(address: ByteArray, port: Int): CoreAttempt<Long>
    fun tcpConnectStatus(token: Long): CoreAttempt<Peer>
    /** Ready(-1) is peer EOF; reset throws PEER_RESET, including after buffered data drains. */
    fun tcpRead(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int>
    /** Direct buffers are borrowed only until this owner-thread call returns. */
    fun tcpReadDirect(token: Long, buffer: ByteBuffer, length: Int): CoreAttempt<Int> {
        require(length >= 0 && length <= buffer.capacity())
        val copy = ByteArray(length)
        return when (val attempt = tcpRead(token, copy, 0, length)) {
            CoreAttempt.Pending -> CoreAttempt.Pending
            is CoreAttempt.Ready -> {
                if (attempt.value in 1..length) for (i in 0 until attempt.value) buffer.put(i, copy[i])
                attempt
            }
        }
    }
    /** Ready reports an accepted prefix. A thrown failure may also have accepted a prefix. */
    fun tcpWrite(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int>
    fun tcpWriteDirect(token: Long, buffer: ByteBuffer, length: Int): CoreAttempt<Int> {
        require(length >= 0 && length <= buffer.capacity())
        val copy = ByteArray(length)
        for (i in 0 until length) copy[i] = buffer.get(i)
        return tcpWrite(token, copy, 0, length)
    }
    fun tcpShutdownOutput(token: Long): CoreAttempt<Unit>
    fun tcpConfigureLiveness(token: Long)
    fun tcpClose(token: Long)
    fun tcpCloseGraceful(token: Long)
    fun tcpListenerClose(token: Long)
}

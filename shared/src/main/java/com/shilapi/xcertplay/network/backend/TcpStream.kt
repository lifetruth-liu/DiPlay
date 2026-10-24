package com.shilapi.xcertplay.network.backend

import java.io.Closeable

data class TcpOptions(val resetOnClose: Boolean = false)

/** One reader and one writer may run concurrently. Overlapping writers fail with RESOURCE_LIMIT.
 * A write completes its entire range or throws; a failed write may have sent a prefix.
 */
interface TcpStream : Closeable {
    val localEndpoint: NetworkEndpoint
    val remoteEndpoint: NetworkEndpoint
    /** Returns -1 for peer FIN; local cancellation throws CANCELLED. Empty reads return zero. */
    fun read(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, deadlineMillis: Long = 0): Int
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, deadlineMillis: Long = 0)
    fun shutdownOutput()
    fun configureLiveness(diagnostic: (String) -> Unit)
    fun configure(options: TcpOptions)
}

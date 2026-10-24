package com.shilapi.xcertplay.network.backend

import java.io.Closeable

fun interface MonotonicClock {
    fun nowMillis(): Long
}

object Deadline {
    /** Zero means unlimited; a positive value is absolute in the network's monotonic clock. */
    fun remainingMillis(deadlineMillis: Long, clock: MonotonicClock): Long {
        require(deadlineMillis >= 0) { "Negative deadline" }
        if (deadlineMillis == 0L) return 0
        val remaining = deadlineMillis - clock.nowMillis()
        if (remaining <= 0) throw BackendFailure(BackendFailure.Kind.TIMEOUT)
        return remaining
    }
}

/** Owns all endpoints, including in-flight connects. Closing cancels every pending operation. */
interface SessionNetwork : Closeable {
    val linkId: String
    val clock: MonotonicClock
    fun listen(local: NetworkEndpoint, backlog: Int = 50, reuseAddress: Boolean = false): TcpListener
    fun connect(remote: NetworkEndpoint, deadlineMillis: Long = 0): TcpStream
    fun bindUdp(local: NetworkEndpoint): UdpEndpoint
}

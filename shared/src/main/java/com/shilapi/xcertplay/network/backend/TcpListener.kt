package com.shilapi.xcertplay.network.backend

import java.io.Closeable

interface TcpListener : Closeable {
    val localEndpoint: NetworkEndpoint
    /** At most one outstanding accept; overlapping operations fail with RESOURCE_LIMIT. */
    fun accept(deadlineMillis: Long = 0): TcpStream
}

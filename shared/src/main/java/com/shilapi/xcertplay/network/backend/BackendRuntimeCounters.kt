package com.shilapi.xcertplay.network.backend

/** Safe aggregate counters for a wired backend; it intentionally contains no addresses or payloads. */
data class BackendRuntimeCounters(
    val ncmTxSent: Long? = null,
    val ncmTxNotReady: Long? = null,
    val ncmTxShortWrite: Long? = null,
    val ncmRxResync: Long? = null,
    val ncmRxRejected: Long? = null,
    val lwipRxDropped: Long? = null,
    val lwipTxSent: Long? = null,
    val lwipTxNotReady: Long? = null,
    val rxQueueHighWaterBytes: Long? = null,
    val rxQueueHighWaterFrames: Long? = null,
    val txQueueHighWaterBytes: Long? = null,
    val txQueueHighWaterFrames: Long? = null,
    val commandHighWaterCount: Long? = null,
    val commandHighWaterBytes: Long? = null,
    val directBufferCurrentBytes: Long? = null,
    val directBufferPeakBytes: Long? = null,
    val liveResourceScopes: Long? = null,
)

/** Implemented only by the wired prototype's frame port and user-space session adapter. */
interface BackendRuntimeCountersProvider {
    fun backendRuntimeCounters(): BackendRuntimeCounters
}

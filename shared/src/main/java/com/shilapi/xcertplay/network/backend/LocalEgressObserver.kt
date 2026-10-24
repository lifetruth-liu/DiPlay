package com.shilapi.xcertplay.network.backend

/** Local output evidence only: even a drained queue does not prove a FIN reached/was ACKed by the peer. */
enum class LocalEgressResult { LOCAL_EGRESS_DRAINED, DEADLINE, CANCELLED, NOT_CONFIRMED, UNSUPPORTED }

/** Optional bounded observation used by the debug wired-close experiment. Does not destroy the network. */
interface LocalEgressObserver {
    fun awaitLocalEgress(timeoutMillis: Long, cancelled: () -> Boolean): LocalEgressResult
}

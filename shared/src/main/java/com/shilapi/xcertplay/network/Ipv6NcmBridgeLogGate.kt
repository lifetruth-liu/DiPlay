package com.shilapi.xcertplay.network

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps repeated neighbor-advertisement rewrites from flooding device logs. */
internal class Ipv6NcmBridgeLogGate {
    private val neighborAdvertisementRewriteLogged = AtomicBoolean(false)

    fun shouldLogNeighborAdvertisementRewrite(): Boolean =
        neighborAdvertisementRewriteLogged.compareAndSet(false, true)
}

package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6NcmBridgeLogGateTest {
    @Test fun neighborAdvertisementRewriteLogIsAllowedOncePerBridge() {
        val gate = Ipv6NcmBridgeLogGate()

        assertTrue(gate.shouldLogNeighborAdvertisementRewrite())
        repeat(10_000) {
            assertFalse(gate.shouldLogNeighborAdvertisementRewrite())
        }
    }
}

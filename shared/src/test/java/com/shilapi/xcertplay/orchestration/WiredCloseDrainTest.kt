package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.backend.LocalEgressResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class WiredCloseDrainTest {
    @Test fun deadlineIncludesAStuckSessionClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            assertEquals(LocalEgressResult.DEADLINE, WiredCloseDrain.await(
                closeSessions = { entered.countDown(); while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                } },
                awaitEgress = { _, _ -> fail("must not drain before close completes"); LocalEgressResult.NOT_CONFIRMED },
                cancelled = { false }, timeoutMillis = 100,
            ))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        } finally { release.countDown() }
    }

    @Test fun detachCancelsDuringSessionClose() {
        val cancelled = AtomicBoolean(false)
        assertEquals(LocalEgressResult.CANCELLED, WiredCloseDrain.await(
            closeSessions = { cancelled.set(true) },
            awaitEgress = { _, _ -> fail("detached network must not drain"); LocalEgressResult.NOT_CONFIRMED },
            cancelled = cancelled::get,
        ))
    }

    @Test fun completedCloseUsesOnlyRemainingBudget() {
        assertEquals(LocalEgressResult.LOCAL_EGRESS_DRAINED, WiredCloseDrain.await(
            closeSessions = {}, awaitEgress = { remaining, cancelled ->
                assertTrue(remaining in 1..750)
                assertFalse(cancelled())
                LocalEgressResult.LOCAL_EGRESS_DRAINED
            }, cancelled = { false },
        ))
    }
}

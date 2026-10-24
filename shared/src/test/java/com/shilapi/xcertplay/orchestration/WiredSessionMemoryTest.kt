package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbEnumeration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WiredSessionMemoryTest {
    private val phone = IphoneUsbEnumeration(1, "same-node", 0x05ac, 1)
    @Before fun reset() = WiredSessionMemory.clear()

    @Test fun startIsNotEvidenceOfAnActiveSession() {
        WiredSessionMemory.started(phone, 10)
        val record = WiredSessionMemory.snapshot(phone)!!
        assertEquals(10L, record.startedAtNanos)
        assertNull(record.activeAtNanos)
    }

    @Test fun sameNodeWithNewTokenCannotInheritAnOldSession() {
        WiredSessionMemory.active(phone, 10)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.ACTIVE_RECONNECT, 20)
        assertNull(WiredSessionMemory.snapshot(phone.copy(token = 2)))
        assertEquals(WiredSessionStopReason.ACTIVE_RECONNECT, WiredSessionMemory.snapshot(phone)!!.stopReason)
        WiredSessionMemory.invalidate(phone)
        assertNull(WiredSessionMemory.snapshot(phone))
    }

    @Test fun startAndControllerRecreationDoNotResetTheRecoveryBudget() {
        WiredSessionMemory.started(phone, 10)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 20)
        WiredSessionMemory.released(phone, 20)
        WiredSessionMemory.directFailed(phone, 21)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        WiredSessionMemory.started(phone, 30)
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 31)
        WiredSessionMemory.transition(phone, WiredTransitionResult.UNCHANGED_TIMEOUT, 40)
        WiredSessionMemory.directFailed(phone, 50)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        assertEquals(1, WiredSessionMemory.snapshot(phone)!!.transitionsRequested)
        assertEquals(WiredTransitionResult.UNCHANGED_TIMEOUT, WiredSessionMemory.snapshot(phone)!!.transitionResult)
        WiredSessionMemory.active(phone, 60)
        assertEquals(0, WiredSessionMemory.snapshot(phone)!!.transitionsRequested)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 65)
        WiredSessionMemory.released(phone, 66)
        WiredSessionMemory.directFailed(phone, 70)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
    }

    @Test fun recoveryCanOnlyBeClaimedAfterFailedAttemptHasStopped() {
        WiredSessionMemory.started(phone, 10)
        WiredSessionMemory.directFailed(phone, 20)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 30)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        WiredSessionMemory.released(phone, 31)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertFalse(WiredSessionMemory.claimRecovery(phone))
    }

    @Test fun transitionOutcomesRetainTheirTimeWithoutConsumingAnotherRequest() {
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 1)
        for ((index, outcome) in WiredTransitionResult.entries.filter { it != WiredTransitionResult.REQUESTED }.withIndex()) {
            val now = index + 2L
            WiredSessionMemory.transition(phone, outcome, now)
            val record = WiredSessionMemory.snapshot(phone)!!
            assertEquals(outcome, record.transitionResult)
            assertEquals(now, record.transitionAtNanos)
            assertEquals(1, record.transitionsRequested)
        }
    }

    @Test fun invalidationPermanentlyRevokesLateCallbacksForThatToken() {
        WiredSessionMemory.active(phone, 1)
        WiredSessionMemory.invalidate(phone)
        val callbacks: List<() -> Unit> = listOf(
            { WiredSessionMemory.started(phone, 2) },
            { WiredSessionMemory.active(phone, 3) },
            { WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 4) },
            { WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 5) },
            { WiredSessionMemory.directFailed(phone, 6) },
            { WiredSessionMemory.released(phone, 7) },
            { WiredSessionMemory.recoveryFailed(phone, 8) },
        )
        callbacks.forEach { callback ->
            callback()
            assertNull(WiredSessionMemory.snapshot(phone))
            assertFalse(WiredSessionMemory.claimRecovery(phone))
        }
        // A modified identity cannot bypass revocation of the same lifecycle token.
        WiredSessionMemory.active(phone.copy(deviceName = "other-node"), 9)
        assertNull(WiredSessionMemory.snapshot(phone.copy(deviceName = "other-node")))
        val fresh = phone.copy(token = 2)
        WiredSessionMemory.active(fresh, 10)
        assertNotNull(WiredSessionMemory.snapshot(fresh))
    }

    @Test fun invalidateWinsAgainstConcurrentCallbacks() {
        val start = java.util.concurrent.CountDownLatch(1)
        val callbacks = List(8) { Thread {
            start.await()
            repeat(50) { WiredSessionMemory.active(phone, it.toLong()) }
        } }
        callbacks.forEach { it.start() }
        start.countDown()
        WiredSessionMemory.invalidate(phone)
        callbacks.forEach { it.join() }
        assertNull(WiredSessionMemory.snapshot(phone))
    }

    @Test fun concurrentRecoveryClaimsHaveExactlyOneWinner() {
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 1)
        WiredSessionMemory.released(phone, 1)
        WiredSessionMemory.directFailed(phone, 2)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = List(8) { Thread { start.await(); if (WiredSessionMemory.claimRecovery(phone)) winners.incrementAndGet() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }
}

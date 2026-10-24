package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.MonotonicClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CoreCommandPreparationTest {
    @Test(timeout = 10_000) fun failedDeliveryRetiresBudgetAndLeavesQueueUsable() {
        val queue = queue()
        val reason = IllegalStateException("copy failure")
        val failed = Call { queue.callPreparedDeliver<Int, Int>(4, deliver = { throw reason }) {
            { CoreAttempt.Ready(42) }
        } }
        await { queue.hasReady() }; queue.runBatch(0)
        assertSame(reason, failed.finish().exceptionOrNull())
        assertEquals(0, queue.snapshot().bytes)
        val next = Call { queue.callPrepared<Int>(4) { { CoreAttempt.Ready(42) } } }
        await { queue.hasReady() }; queue.runBatch(0)
        assertEquals(42, next.finish().getOrThrow())
    }
    @Test(timeout = 10_000) fun deliveryRetainsBudgetThroughCallerCopy() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val delivering = AtomicReference<Thread>()
        val call = Call { queue.callPreparedDeliver<Int, Int>(4, deliver = {
            delivering.set(Thread.currentThread()); entered.countDown(); gate.await(); it + 1
        }) { { CoreAttempt.Ready(41) } } }
        try {
            await { queue.hasReady() }; queue.runBatch(0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(4, queue.snapshot().bytes)
            assertEquals(1, queue.snapshot().commands)
            val rejected = Call { queue.callPrepared<Int>(1) { fail(); { CoreAttempt.Ready(0) } } }
            failure(BackendFailure.Kind.RESOURCE_LIMIT) { rejected.finish().getOrThrow() }
            queue.close(BackendFailure(BackendFailure.Kind.CANCELLED))
            assertEquals(4, queue.snapshot().bytes)
        } finally { gate.countDown() }
        assertEquals(42, call.finish().getOrThrow())
        assertSame(call.thread, delivering.get())
        assertEquals(0, queue.snapshot().bytes)
    }
    @Test(timeout = 10_000) fun contextStopDuringPreparationPreservesFirstFailure() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val reason = BackendFailure(BackendFailure.Kind.USB_IO)
        val call = Call { queue.callPrepared<Int>(4, 110) {
            entered.countDown(); gate.await(); throw IllegalStateException("later preparation failure")
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.close(reason); now.set(200)
            assertEquals(1, queue.snapshot().commands)
            assertEquals(4, queue.snapshot().bytes)
        } finally { gate.countDown() }
        assertSame(reason, call.finish().exceptionOrNull())
        assertFalse(queue.hasReady())
        assertEquals(0, queue.snapshot().bytes)
    }
    private val now = AtomicLong(100)
    private fun queue(count: Int = 2) = CoreCommandQueue<Int>(MonotonicClock { now.get() }, {}, count, 4)
    private class Call<T>(body: () -> T) {
        val result = AtomicReference<Result<T>>()
        val interrupted = AtomicReference<Boolean>()
        val thread = Thread {
            result.set(runCatching(body)); interrupted.set(Thread.currentThread().isInterrupted)
        }.apply { isDaemon = true; start() }
        fun finish(): Result<T> { thread.join(2000); assertFalse(thread.isAlive); return result.get() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(condition())
    }
    private fun failure(kind: BackendFailure.Kind, body: () -> Unit) {
        val error = runCatching(body).exceptionOrNull()
        assertTrue(error is BackendFailure)
        assertEquals(kind, (error as BackendFailure).kind)
    }

    @Test(timeout = 10_000) fun reservesBeforePreparingAndRejectsExcessWithoutAllocating() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val preparing = AtomicReference<Thread>()
        val operated = AtomicReference<Thread>()
        val call = Call { queue.callPrepared(4) {
            preparing.set(Thread.currentThread()); entered.countDown(); gate.await()
            val owned = byteArrayOf(1, 2, 3, 4)
            val operation: (Int) -> CoreAttempt<Int> = { core ->
                operated.set(Thread.currentThread()); CoreAttempt.Ready(core + owned.sum())
            }
            operation
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, queue.snapshot().commands)
            assertEquals(4, queue.snapshot().bytes)
            assertFalse(queue.hasReady())
            queue.runBatch(0)
            assertNull(operated.get())
            val rejected = Call {
                queue.callPrepared<Int>(1) { fail("must reject before allocation"); { CoreAttempt.Ready(0) } }
            }
            failure(BackendFailure.Kind.RESOURCE_LIMIT) { rejected.finish().getOrThrow() }
        } finally { gate.countDown() }
        await { queue.hasReady() }; queue.runBatch(32)
        assertEquals(42, call.finish().getOrThrow())
        assertSame(call.thread, preparing.get())
        assertSame(Thread.currentThread(), operated.get())
        assertEquals(0, queue.snapshot().bytes)
    }

    @Test(timeout = 10_000) fun preparingConsumesCommandCountEvenWithZeroPayload() {
        val queue = queue(1)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val call = Call { queue.callPrepared<Int> {
            entered.countDown(); gate.await(); { CoreAttempt.Ready(42) }
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            failure(BackendFailure.Kind.RESOURCE_LIMIT) {
                queue.callPrepared<Int> { fail("must reserve a command first"); { CoreAttempt.Ready(0) } }
            }
        } finally { gate.countDown() }
        await { queue.hasReady() }; queue.runBatch(0)
        assertEquals(42, call.finish().getOrThrow())
    }

    @Test(timeout = 10_000) fun endpointCanReleaseWhileCancelledPreparationKeepsItsBudget() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val released = AtomicInteger()
        val operated = AtomicInteger()
        val scope = queue.newResourceScope { released.incrementAndGet() }
        val call = Call { queue.callPrepared<Int>(4, scope = scope) {
            entered.countDown(); gate.await()
            val operation: (Int) -> CoreAttempt<Int> = { operated.incrementAndGet(); CoreAttempt.Ready(42) }
            operation
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.closeResource(scope, BackendFailure(BackendFailure.Kind.CANCELLED))
            queue.runBatch(0)
            assertEquals(1, released.get())
            assertEquals(0, queue.snapshot().resources)
            assertEquals(4, queue.snapshot().bytes)
            assertEquals(1, queue.snapshot().commands)
            assertNull(call.result.get())
        } finally { gate.countDown() }
        failure(BackendFailure.Kind.CANCELLED) { call.finish().getOrThrow() }
        queue.progress(); queue.runBatch(0)
        assertEquals(0, operated.get())
        assertEquals(0, queue.snapshot().commands)
        assertEquals(0, queue.snapshot().bytes)
    }

    @Test(timeout = 10_000) fun deadlineDuringPreparationNeverPublishesOperation() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val operated = AtomicInteger()
        val call = Call { queue.callPrepared<Int>(4, 110) {
            entered.countDown(); gate.await()
            val operation: (Int) -> CoreAttempt<Int> = { operated.incrementAndGet(); CoreAttempt.Ready(42) }
            operation
        } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            now.set(200); queue.runBatch(0)
            assertEquals(4, queue.snapshot().bytes)
        } finally { gate.countDown() }
        failure(BackendFailure.Kind.TIMEOUT) { call.finish().getOrThrow() }
        assertFalse(queue.hasReady())
        assertEquals(0, operated.get())
        assertEquals(0, queue.snapshot().bytes)
    }

    @Test(timeout = 10_000) fun preparationFailureAndInjectedOomReleaseAdmissionWithoutClosingQueue() {
        val queue = queue()
        val reason = IllegalStateException("copy failure")
        assertSame(reason, runCatching { queue.callPrepared<Int>(4) { throw reason } }.exceptionOrNull())
        assertEquals(0, queue.snapshot().bytes)
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { queue.callPrepared<Unit>(4) { throw OutOfMemoryError("injected") } }
        assertEquals(0, queue.snapshot().commands)
        val next = Call { queue.callPrepared<Int>(4) { { CoreAttempt.Ready(42) } } }
        await { queue.hasReady() }; queue.runBatch(0)
        assertEquals(42, next.finish().getOrThrow())
    }

    @Test(timeout = 10_000) fun interruptionDuringPreparationPreservesFlagAndRejectsPublication() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val call = Call { queue.callPrepared<Int>(4) {
            entered.countDown(); gate.await(); { CoreAttempt.Ready(42) }
        } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        call.thread.interrupt()
        failure(BackendFailure.Kind.CANCELLED) { call.finish().getOrThrow() }
        assertEquals(true, call.interrupted.get())
        assertFalse(queue.hasReady())
        assertEquals(0, queue.snapshot().bytes)
        val flagged = Call { queue.callPrepared<Int>(4) {
            Thread.currentThread().interrupt(); { CoreAttempt.Ready(42) }
        } }
        failure(BackendFailure.Kind.CANCELLED) { flagged.finish().getOrThrow() }
        assertEquals(true, flagged.interrupted.get())
    }

    @Test(timeout = 10_000) fun expiredClosedAndInterruptedCallsRejectBeforePreparation() {
        val queue = queue()
        failure(BackendFailure.Kind.TIMEOUT) {
            queue.callPrepared<Unit>(4, 100) { fail("expired"); { CoreAttempt.Ready(Unit) } }
        }
        val interrupted = Call {
            Thread.currentThread().interrupt()
            queue.callPrepared<Unit> { fail("interrupted"); { CoreAttempt.Ready(Unit) } }
        }
        failure(BackendFailure.Kind.CANCELLED) { interrupted.finish().getOrThrow() }
        queue.close(BackendFailure(BackendFailure.Kind.USB_IO))
        failure(BackendFailure.Kind.USB_IO) {
            queue.callPrepared<Unit> { fail("closed"); { CoreAttempt.Ready(Unit) } }
        }
        assertEquals(0, queue.snapshot().commands)
    }
}

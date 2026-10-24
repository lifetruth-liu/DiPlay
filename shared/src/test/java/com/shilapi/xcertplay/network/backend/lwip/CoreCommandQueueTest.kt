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

class CoreCommandQueueTest {
    @Test(timeout = 10_000) fun diagnosticHighWaterMarksTrackBoundedCommandsBytesAndResources() {
        val queue = queue(count = 3, bytes = 8)
        val scope = queue.newResourceScope { }
        val first = Call { queue.call<Int>(2) { CoreAttempt.Pending } }
        val second = Call { queue.call<Int>(3) { CoreAttempt.Pending } }
        try {
            await { queue.snapshot().commands == 2 }
            assertEquals(CoreCommandQueue.HighWaterSnapshot(commands = 2, bytes = 5, resources = 1), queue.highWaterSnapshot())
        } finally {
            queue.close(BackendFailure(BackendFailure.Kind.CANCELLED))
        }
        failure(BackendFailure.Kind.CANCELLED) { first.finish().getOrThrow() }
        failure(BackendFailure.Kind.CANCELLED) { second.finish().getOrThrow() }
        assertNotNull(scope)
    }

    @Test(timeout = 10_000) fun endpointCancellationIsIsolatedAndRejectsLaterCalls() {
        val queue = queue(count = 3)
        val endpoint = queue.newScope()
        val other = queue.newScope()
        val attempts = AtomicInteger()
        val waiting = Call { queue.call<Int>(scope = endpoint) { attempts.incrementAndGet(); CoreAttempt.Pending } }
        val unrelated = Call { queue.call<Int>(scope = other) { CoreAttempt.Pending } }
        await { queue.snapshot().commands == 2 }
        queue.runBatch(0)
        val queued = Call { queue.call(scope = endpoint) { attempts.incrementAndGet(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 3 }
        val reason = BackendFailure(BackendFailure.Kind.CANCELLED)
        queue.cancel(endpoint, reason)
        assertSame(reason, waiting.finish().exceptionOrNull())
        assertSame(reason, queued.finish().exceptionOrNull())
        assertNull(unrelated.result.get())
        assertEquals(1, queue.snapshot().commands)
        queue.cancel(endpoint, BackendFailure(BackendFailure.Kind.INTERNAL))
        assertSame(reason, runCatching { queue.call(scope = endpoint) { CoreAttempt.Ready(0) } }.exceptionOrNull())
        queue.progress(); queue.runBatch(0)
        assertEquals(1, attempts.get())
        queue.close(reason)
        assertSame(reason, unrelated.finish().exceptionOrNull())
        assertEquals(0, queue.snapshot().commands)
    }

    @Test(timeout = 10_000) fun endpointCancellationRetainsActiveBudgetThroughCoreCleanup() {
        val queue = queue(count = 1)
        val endpoint = queue.newScope()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val discarded = AtomicReference<Thread>()
        val call = Call { queue.call(4, scope = endpoint, discard = { _: Int ->
            assertEquals(4, queue.snapshot().bytes)
            discarded.set(Thread.currentThread())
        }) { entered.countDown(); gate.await(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }
        val pump = Thread { queue.runBatch(0) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.cancel(endpoint, BackendFailure(BackendFailure.Kind.CANCELLED))
            failure(BackendFailure.Kind.CANCELLED) { call.finish().getOrThrow() }
            assertEquals(4, queue.snapshot().bytes)
            failure(BackendFailure.Kind.RESOURCE_LIMIT) { queue.call<Int> { CoreAttempt.Ready(0) } }
        } finally { gate.countDown(); pump.join(2000) }
        assertFalse(pump.isAlive)
        assertSame(pump, discarded.get())
        assertEquals(0, queue.snapshot().commands)
        assertEquals(0, queue.snapshot().bytes)
    }

    @Test(timeout = 10_000) fun endpointCancellationPreservesCommittedResultAndChecksQueueIdentity() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val queue = CoreCommandQueue<Int>(MonotonicClock { now.get() }, { entered.countDown(); gate.await() })
        val scope = queue.newScope()
        val first = Call { queue.call(scope = scope) { CoreAttempt.Ready(42) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.runBatch(0)
            queue.cancel(scope, BackendFailure(BackendFailure.Kind.CANCELLED))
        } finally { gate.countDown() }
        assertEquals(42, first.finish().getOrThrow())
        val foreign = queue().newScope()
        assertTrue(runCatching { queue.cancel(foreign, BackendFailure(BackendFailure.Kind.CANCELLED)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { queue.call(scope = foreign) { CoreAttempt.Ready(0) } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, queue.snapshot().commands)
    }
    private val now = AtomicLong(100)
    private fun queue(count: Int = 2, bytes: Int = 4) = CoreCommandQueue<Int>(
        MonotonicClock { now.get() }, {}, count, bytes,
    )
    private class Call<T>(body: () -> T) {
        val result = AtomicReference<Result<T>>()
        val interrupted = AtomicReference<Boolean>()
        val thread = Thread {
            result.set(runCatching(body))
            interrupted.set(Thread.currentThread().isInterrupted)
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

    @Test(timeout = 10_000) fun executesOnlyOnPumpAndTransfersResult() {
        val queue = queue()
        val owner = AtomicReference<Thread>()
        val call = Call { queue.call(4) { core -> owner.set(Thread.currentThread()); CoreAttempt.Ready(core + 1) } }
        await { queue.snapshot().commands == 1 }
        assertNull(call.result.get())
        queue.runBatch(41)
        assertEquals(42, call.finish().getOrThrow())
        assertSame(Thread.currentThread(), owner.get())
        assertEquals(0, queue.snapshot().commands)
        assertEquals(0, queue.snapshot().bytes)
    }

    @Test(timeout = 10_000) fun pendingWaitsForProgressInsteadOfSpinning() {
        val queue = queue()
        val attempts = AtomicInteger()
        val call = Call { queue.call<Int> { core ->
            if (attempts.incrementAndGet() == 1) CoreAttempt.Pending else CoreAttempt.Ready(core)
        } }
        await { queue.snapshot().commands == 1 && queue.hasReady() }
        queue.runBatch(42); repeat(10) { queue.runBatch(42) }
        assertEquals(1, attempts.get())
        assertEquals(Long.MAX_VALUE, queue.nextDelayMillis())
        queue.progress(); queue.runBatch(42)
        assertEquals(42, call.finish().getOrThrow())
    }

    @Test(timeout = 10_000) fun expiresQueuedWorkWithoutCallingOperation() {
        val queue = queue()
        val attempted = AtomicInteger()
        val call = Call { queue.call(1, 110) { attempted.incrementAndGet(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }
        assertEquals(10L, queue.nextDelayMillis())
        now.set(200); queue.runBatch(0)
        failure(BackendFailure.Kind.TIMEOUT) { call.finish().getOrThrow() }
        assertEquals(0, attempted.get())
        assertEquals(0, queue.snapshot().commands)
    }

    @Test(timeout = 10_000) fun lateResultIsDiscardedOnCoreAndRetainsBudgetUntilCleanup() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val discarded = AtomicInteger()
        val discardThread = AtomicReference<Thread>()
        val call = Call { queue.call(4, 110, discard = { value: Int ->
            discarded.set(value); discardThread.set(Thread.currentThread())
        }) { entered.countDown(); gate.await(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }
        val pump = Thread { queue.runBatch(0) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            now.set(200)
            failure(BackendFailure.Kind.TIMEOUT) { call.finish().getOrThrow() }
            assertEquals(1, queue.snapshot().commands)
            assertEquals(4, queue.snapshot().bytes)
            failure(BackendFailure.Kind.RESOURCE_LIMIT) { queue.call<Int>(1) { CoreAttempt.Ready(0) } }
        } finally { gate.countDown(); pump.join(2000) }
        assertFalse(pump.isAlive)
        assertEquals(42, discarded.get())
        assertSame(pump, discardThread.get())
        assertEquals(0, queue.snapshot().commands)
    }

    @Test(timeout = 10_000) fun boundsCountAndBytesAndCloseWakesWaiters() {
        val queue = queue()
        val first = Call { queue.call<Int>(2) { CoreAttempt.Pending } }
        val second = Call { queue.call<Int>(2) { CoreAttempt.Pending } }
        await { queue.snapshot().commands == 2 }
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { queue.call<Int>(0) { CoreAttempt.Ready(0) } }
        val reason = BackendFailure(BackendFailure.Kind.USB_IO)
        queue.close(reason)
        assertSame(reason, first.finish().exceptionOrNull())
        assertSame(reason, second.finish().exceptionOrNull())
        failure(BackendFailure.Kind.USB_IO) { queue.call<Int>(0) { CoreAttempt.Ready(0) } }
        assertEquals(0, queue.snapshot().bytes)
        val bytes = queue()
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { bytes.call<Int>(5) { CoreAttempt.Ready(0) } }
    }

    @Test(timeout = 10_000) fun interruptionCancelsQueuedWorkAndPreservesInterruptFlag() {
        val queue = queue()
        val attempted = AtomicInteger()
        val call = Call { queue.call { attempted.incrementAndGet(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }
        call.thread.interrupt()
        failure(BackendFailure.Kind.CANCELLED) { call.finish().getOrThrow() }
        assertEquals(true, call.interrupted.get())
        queue.runBatch(0)
        assertEquals(0, attempted.get())
        assertEquals(0, queue.snapshot().commands)
    }

    @Test(timeout = 10_000) fun oneOperationFailureDoesNotCloseTheQueue() {
        val queue = queue()
        val reason = BackendFailure(BackendFailure.Kind.ADDRESS_CONFLICT)
        val failed = Call { queue.call<Int> { throw reason } }
        await { queue.snapshot().commands == 1 }; queue.runBatch(0)
        assertSame(reason, failed.finish().exceptionOrNull())
        val next = Call { queue.call { CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }; queue.runBatch(0)
        assertEquals(42, next.finish().getOrThrow())
    }
    @Test(timeout = 10_000) fun completedButUnclaimedResultStillConsumesBudget() {
        val wakeEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val queue = CoreCommandQueue<Int>(MonotonicClock { now.get() }, { wakeEntered.countDown(); gate.await() }, 1, 4)
        val first = Call { queue.call(4) { CoreAttempt.Ready(42) } }
        try {
            assertTrue(wakeEntered.await(2, TimeUnit.SECONDS))
            queue.runBatch(0)
            assertEquals(1, queue.snapshot().commands)
            assertEquals(4, queue.snapshot().bytes)
            val rejected = Call { queue.call(0) { CoreAttempt.Ready(0) } }
            failure(BackendFailure.Kind.RESOURCE_LIMIT) { rejected.finish().getOrThrow() }
        } finally { gate.countDown() }
        assertEquals(42, first.finish().getOrThrow())
        assertEquals(0, queue.snapshot().commands)
    }
}

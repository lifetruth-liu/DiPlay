package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.MonotonicClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CoreCommandResourceTest {
    @Test fun invalidResourceLimitIsRejectedBeforeCapacityAllocation() {
        assertEquals(IllegalArgumentException::class.java, runCatching { queue(Int.MAX_VALUE) }.exceptionOrNull()?.javaClass)
    }
    private fun queue(resources: Int = 1) = CoreCommandQueue<Int>(MonotonicClock { 0 }, {}, 1, 4, resources)
    private val cancelled get() = BackendFailure(BackendFailure.Kind.CANCELLED)
    private fun failure(kind: BackendFailure.Kind, body: () -> Unit) {
        val error = runCatching(body).exceptionOrNull()
        assertTrue(error is BackendFailure)
        assertEquals(kind, (error as BackendFailure).kind)
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(condition())
    }
    private class Call(body: () -> Unit) {
        val result = AtomicReference<Result<Unit>>()
        val thread = Thread { result.set(runCatching(body)) }.apply { isDaemon = true; start() }
        fun finish(): Result<Unit> { thread.join(2000); assertFalse(thread.isAlive); return result.get() }
    }

    @Test(timeout = 10_000) fun closeHasReservedCapacityAndExecutesOnceOnCore() {
        val queue = queue()
        val released = AtomicInteger()
        val owner = AtomicReference<Thread>()
        val scope = queue.newResourceScope { released.set(it); owner.set(Thread.currentThread()) }
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { queue.newResourceScope {} }
        val busy = Call { queue.call<Unit>(4) { CoreAttempt.Pending } }
        await { queue.snapshot().commands == 1 }
        assertTrue(queue.closeResource(scope, cancelled))
        assertFalse(queue.closeResource(scope, cancelled))
        assertEquals(1, queue.snapshot().resources)
        assertEquals(1, queue.snapshot().closingResources)
        assertEquals(0, released.get())
        assertTrue(queue.hasReady())
        queue.runBatch(42, 1)
        assertEquals(42, released.get())
        assertSame(Thread.currentThread(), owner.get())
        assertEquals(0, queue.snapshot().resources)
        assertEquals(0, queue.snapshot().closingResources)
        assertEquals(1, queue.snapshot().commands)
        assertNull(busy.result.get())
        assertFalse(queue.closeResource(scope, cancelled))
        val next = queue.newResourceScope { released.incrementAndGet() }
        queue.closeResource(next, cancelled); queue.runBatch(42)
        assertEquals(43, released.get())
        queue.close(cancelled)
        failure(BackendFailure.Kind.CANCELLED) { busy.finish().getOrThrow() }
    }

    @Test(timeout = 10_000) fun closeCancelsPendingAndRejectsLaterScopedWork() {
        val queue = queue()
        val release = AtomicInteger()
        val scope = queue.newResourceScope { release.incrementAndGet() }
        val pending = Call { queue.call<Unit>(scope = scope) { CoreAttempt.Pending } }
        await { queue.snapshot().commands == 1 }; queue.runBatch(0)
        val reason = cancelled
        queue.cancel(scope, reason)
        assertTrue(queue.closeResource(scope, BackendFailure(BackendFailure.Kind.INTERNAL)))
        assertSame(reason, pending.finish().exceptionOrNull())
        failure(BackendFailure.Kind.CANCELLED) { queue.call<Unit>(scope = scope) { fail(); CoreAttempt.Ready(Unit) } }
        queue.progress(); queue.runBatch(0)
        assertEquals(1, release.get())
        assertEquals(0, queue.snapshot().commands)
    }

    @Test(timeout = 10_000) fun activeOperationDiscardsBeforeResourceRelease() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val phase = AtomicInteger()
        val scope = queue.newResourceScope {
            assertEquals(1, phase.getAndIncrement())
            assertEquals(0, queue.snapshot().commands)
        }
        val call = Call { queue.call(4, scope = scope, discard = { _: Int ->
            assertEquals(0, phase.getAndIncrement())
        }) { entered.countDown(); gate.await(); CoreAttempt.Ready(42) } }
        await { queue.snapshot().commands == 1 }
        val error = AtomicReference<Throwable>()
        val pump = Thread { error.set(runCatching { queue.runBatch(0) }.exceptionOrNull()) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.closeResource(scope, cancelled)
            failure(BackendFailure.Kind.CANCELLED) { call.finish().getOrThrow() }
            assertEquals(4, queue.snapshot().bytes)
            assertEquals(1, queue.snapshot().resources)
            assertEquals(0, phase.get())
        } finally { gate.countDown(); pump.join(2000) }
        assertFalse(pump.isAlive); assertNull(error.get())
        assertEquals(2, phase.get())
        assertEquals(0, queue.snapshot().resources)
    }

    @Test(timeout = 10_000) fun wholeContextCloseAbandonsUnstartedResourceCallbacks() {
        val queue = queue(2)
        val calls = AtomicInteger()
        val first = queue.newResourceScope { calls.incrementAndGet() }
        val second = queue.newResourceScope { calls.incrementAndGet() }
        queue.closeResource(first, cancelled)
        queue.close(cancelled)
        assertEquals(0, queue.snapshot().resources)
        assertFalse(queue.hasReady())
        assertFalse(queue.closeResource(second, cancelled))
        queue.runBatch(0)
        assertEquals(0, calls.get())
        failure(BackendFailure.Kind.CANCELLED) { queue.newResourceScope {} }
    }

    @Test(timeout = 10_000) fun failedReleaseIsNotRetriedAndPropagatesToTheOwner() {
        val queue = queue()
        val error = BackendFailure(BackendFailure.Kind.INTERNAL)
        val calls = AtomicInteger()
        val scope = queue.newResourceScope { calls.incrementAndGet(); throw error }
        queue.closeResource(scope, cancelled)
        assertSame(error, runCatching { queue.runBatch(0) }.exceptionOrNull())
        assertEquals(0, queue.snapshot().resources)
        assertFalse(queue.closeResource(scope, cancelled))
        queue.runBatch(0)
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000) fun runningReleaseRetainsItsSlotAcrossContextStop() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val scope = queue.newResourceScope { entered.countDown(); gate.await() }
        queue.closeResource(scope, cancelled)
        val pump = Thread { queue.runBatch(0) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, queue.snapshot().resources)
            assertEquals(1, queue.snapshot().closingResources)
            queue.close(cancelled)
            assertEquals(1, queue.snapshot().resources)
        } finally { gate.countDown(); pump.join(2000) }
        assertFalse(pump.isAlive)
        assertEquals(0, queue.snapshot().resources)
    }

    @Test(timeout = 10_000) fun releaseBatchIsBoundedAndForeignOrPlainScopesAreRejected() {
        val queue = queue(4)
        val calls = AtomicInteger()
        repeat(4) { queue.closeResource(queue.newResourceScope { calls.incrementAndGet() }, cancelled) }
        assertTrue(queue.runBatch(0, 2).more)
        assertEquals(2, calls.get())
        assertEquals(2, queue.snapshot().resources)
        assertFalse(queue.runBatch(0, 2).more)
        assertEquals(4, calls.get())
        assertTrue(runCatching { queue.closeResource(queue.newScope(), cancelled) }.exceptionOrNull() is IllegalArgumentException)
        val foreign = queue().newResourceScope {}
        assertTrue(runCatching { queue.closeResource(foreign, cancelled) }.exceptionOrNull() is IllegalArgumentException)
    }
}

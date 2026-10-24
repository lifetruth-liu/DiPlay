package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LwipPrototypeTest {
    @Test fun workerAllocationFailureStopsPreparationAndClosesTheOwnedPort() {
        val port = Port()
        val allocations = AtomicInteger()
        val allocationFailure = OutOfMemoryError("injected worker allocation failure")
        val prototype = LwipPrototype(port, workerFactory = { name, body ->
            if (allocations.incrementAndGet() == 2) throw allocationFailure
            Thread(body, name).apply { isDaemon = true }
        }) { error("core factory must not run when worker allocation fails") }

        val failure = runCatching { prototype.start() }.exceptionOrNull()
        assertTrue("worker creation failure should be mapped to BackendFailure: $failure", failure is BackendFailure)
        assertEquals(BackendFailure.Kind.BACKEND_INIT, (failure as BackendFailure).kind)
        assertSame(allocationFailure, failure.cause)
        assertTrue("the cleanup worker must finish", prototype.awaitStopped(2_000))
        assertEquals(LwipPrototype.State.STOPPED, prototype.snapshot().state)
        assertEquals(1, port.closes.get())
        assertEquals(3, allocations.get()) // core, failed read worker, then USB-close worker
        assertSame(failure, prototype.snapshot().failure)
        prototype.close()
    }

    @Test fun closeWorkerAllocationFailureFallsBackToClosingPortAndJoinsStartedWorkers() {
        val port = Port()
        val coreCreated = CountDownLatch(1)
        val core = Core(emptyList())
        val allocations = AtomicInteger()
        val writerStartFailure = OutOfMemoryError("injected writer start failure")
        val closeWorkerAllocationFailure = OutOfMemoryError("injected close worker allocation failure")
        val usbCloseFailure = IllegalStateException("injected USB close failure")
        port.closeFailure = usbCloseFailure
        val prototype = LwipPrototype(port, workerFactory = { name, body ->
            when (allocations.incrementAndGet()) {
                1, 2 -> Thread(body, name).apply { isDaemon = true }
                3 -> object : Thread(body, name) {
                    override fun start() {
                        assertTrue("core must have started before writer startup fails", coreCreated.await(2, TimeUnit.SECONDS))
                        throw writerStartFailure
                    }
                }.apply { isDaemon = true }
                4 -> throw closeWorkerAllocationFailure
                else -> Thread(body, name).apply { isDaemon = true }
            }
        }) { core.also { it.owner = Thread.currentThread(); coreCreated.countDown() } }

        val failure = runCatching { prototype.start() }.exceptionOrNull()
        assertTrue("startup error should be mapped to BackendFailure: $failure", failure is BackendFailure)
        assertEquals(BackendFailure.Kind.BACKEND_INIT, (failure as BackendFailure).kind)
        assertSame(writerStartFailure, failure.cause)
        assertEquals(2, failure.suppressed.size)
        assertSame(closeWorkerAllocationFailure, failure.suppressed[0].cause)
        assertSame(usbCloseFailure, failure.suppressed[1].cause)
        assertTrue("started workers must terminate after inline port close", prototype.awaitStopped(2_000))
        assertEquals(LwipPrototype.State.STOPPED, prototype.snapshot().state)
        assertEquals(1, port.closes.get())
        assertEquals(1, core.closes.get())
        prototype.close()
    }

    @Test fun closeWorkerAllocationFailureOnCoreClosesThePortAfterDestroyingTheCore() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val port = Port().apply { onClose = { events.add("port") } }
        val coreFailure = IllegalStateException("injected core poll failure")
        val core = Core(emptyList()).apply {
            pollFailure = coreFailure
            onClose = { events.add("core") }
        }
        val allocations = AtomicInteger()
        val closeWorkerAllocationFailure = OutOfMemoryError("injected close worker allocation failure")
        val prototype = LwipPrototype(port, workerFactory = { name, body ->
            if (allocations.incrementAndGet() == 4) throw closeWorkerAllocationFailure
            Thread(body, name).apply { isDaemon = true }
        }) { core.also { it.owner = Thread.currentThread() } }

        val startFailure = runCatching { prototype.start() }.exceptionOrNull()
        assertTrue("start may report a core failure that raced preparation", startFailure == null || startFailure is BackendFailure)
        assertTrue("the core poll failure should run", core.failingPoll.await(2, TimeUnit.SECONDS))
        assertTrue("core and inline port close must finish", prototype.awaitStopped(2_000))
        val failure = prototype.snapshot().failure!!
        assertSame(coreFailure, failure.cause)
        assertEquals(1, failure.suppressed.size)
        assertSame(closeWorkerAllocationFailure, failure.suppressed.single().cause)
        assertEquals(listOf("core", "port"), events.toList())
        assertEquals(1, port.closes.get())
        assertEquals(1, core.closes.get())
        prototype.close()
    }

    @Test fun preparedCommandsValidateBeforeCopyAndRunOnlyOnCore() {
        val core = Core(emptyList())
        val prototype = LwipPrototype(Port()) { core.also { it.owner = Thread.currentThread() } }
        val preparations = AtomicInteger()
        val preparing = AtomicReference<Thread>()
        val executing = AtomicReference<Thread>()
        fun submit(): Int = prototype.executePreparedCommand(1, prototype.commandClock.nowMillis() + 1000) {
            preparations.incrementAndGet(); preparing.set(Thread.currentThread())
            val borrowed = byteArrayOf(41)
            val owned = borrowed.copyOf()
            borrowed.fill(0)
            val operation: (LwipPrototypeCore) -> CoreAttempt<Int> = {
                assertSame(core, it); executing.set(Thread.currentThread()); CoreAttempt.Ready(owned[0] + 1)
            }
            operation
        }
        try {
            assertEquals(BackendFailure.Kind.CANCELLED, (runCatching { submit() }.exceptionOrNull() as BackendFailure).kind)
            assertEquals(0, preparations.get())
            prototype.start()
            assertEquals(42, submit())
            assertSame(Thread.currentThread(), preparing.get())
            assertSame(core.owner, executing.get())
            assertEquals(0, prototype.snapshot().commandBytes)
        } finally { prototype.close() }
        assertEquals(BackendFailure.Kind.CANCELLED, (runCatching { submit() }.exceptionOrNull() as BackendFailure).kind)
        assertEquals(1, preparations.get())
    }
    @Test fun resourceCloseWakesCoreAndReleasesOnceWithoutStoppingTheRunner() {
        val core = Core(emptyList()).apply { timeoutGate = CountDownLatch(1) }
        val prototype = LwipPrototype(Port()) { core.also { it.owner = Thread.currentThread() } }
        val released = CountDownLatch(1)
        val count = AtomicInteger()
        val owner = AtomicReference<Thread>()
        try {
            prototype.start()
            assertTrue(core.timeoutEntered.await(2, TimeUnit.SECONDS))
            val scope = prototype.newResourceScope {
                assertSame(core, it)
                owner.set(Thread.currentThread()); count.incrementAndGet(); released.countDown()
            }
            assertEquals(1, prototype.snapshot().resourceScopes)
            assertTrue(prototype.closeResourceScope(scope))
            assertFalse(prototype.closeResourceScope(scope))
            core.timeoutGate!!.countDown()
            assertTrue("resource close must wake the core loop", released.await(2, TimeUnit.SECONDS))
            assertSame(core.owner, owner.get())
            assertEquals(1, count.get())
            assertEquals(0, core.closes.get())
            assertNull(prototype.snapshot().failure)
            assertEquals(42, prototype.executeCommand(deadlineMillis = prototype.commandClock.nowMillis() + 1000) {
                CoreAttempt.Ready(42)
            })
            assertEquals(0, prototype.snapshot().resourceScopes)
            assertEquals(0, prototype.snapshot().closingResourceScopes)
        } finally { core.timeoutGate!!.countDown(); prototype.close() }
        assertEquals(1, core.closes.get())
        assertEquals(1, count.get())
    }

    @Test fun failedResourceReleaseStopsAndDestroysTheContext() {
        val core = Core(emptyList())
        val port = Port()
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        val reason = BackendFailure(BackendFailure.Kind.INTERNAL)
        try {
            prototype.start()
            val scope = prototype.newResourceScope { throw reason }
            prototype.closeResourceScope(scope)
            assertTrue(prototype.awaitStopped(2000))
            assertSame(reason, prototype.snapshot().failure)
            assertEquals(1, core.closes.get())
            assertEquals(1, port.closes.get())
            assertEquals(0, prototype.snapshot().resourceScopes)
        } finally { prototype.close() }
    }
    @Test fun cancellingOneEndpointLeavesTheRunnerAndOtherCommandsUsable() {
        val core = Core(emptyList())
        val prototype = LwipPrototype(Port()) { core.also { it.owner = Thread.currentThread() } }
        val scope = prototype.newCommandScope()
        val entered = CountDownLatch(1)
        val result = AtomicReference<Throwable>()
        prototype.start()
        val waiter = Thread {
            result.set(runCatching { prototype.executeCommand<Int>(scope = scope) {
                entered.countDown(); CoreAttempt.Pending
            } }.exceptionOrNull())
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            prototype.cancelCommands(scope)
            waiter.join(2000)
            assertFalse("endpoint cancellation must wake its pending caller", waiter.isAlive)
            assertEquals(BackendFailure.Kind.CANCELLED, (result.get() as BackendFailure).kind)
            assertEquals(42, prototype.executeCommand(deadlineMillis = prototype.commandClock.nowMillis() + 1000) {
                CoreAttempt.Ready(42)
            })
            assertEquals(0, prototype.snapshot().commandCount)
            assertNull(prototype.snapshot().failure)
            assertEquals(LwipPrototype.State.LISTENING, prototype.snapshot().state)
        } finally { prototype.close(); waiter.join(2000) }
    }
    @Test fun commandsRunOnTheExistingCoreThread() {
        val core = Core(emptyList())
        val prototype = LwipPrototype(Port()) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            val result = runCatching {
                prototype.executeCommand(deadlineMillis = prototype.commandClock.nowMillis() + 1_000) {
                    CoreAttempt.Ready(Thread.currentThread())
                }
            }
            assertTrue("command must be pumped before its deadline: $result", result.isSuccess)
            assertSame(core.owner, result.getOrThrow())
            assertEquals(0, prototype.snapshot().commandCount)
        } finally { prototype.close() }
    }

    @Test fun inputProgressResumesPendingCommandsAndStopCancelsWaiters() {
        val port = Port()
        val core = Core(emptyList())
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        prototype.start()
        val progress = AtomicBoolean()
        val attempted = CountDownLatch(1)
        val result = AtomicReference<Result<Int>>()
        val caller = Thread {
            result.set(runCatching { prototype.executeCommand<Int> {
                attempted.countDown()
                if (progress.get()) CoreAttempt.Ready(42) else CoreAttempt.Pending
            } })
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertNull(result.get())
            progress.set(true); port.incoming.put(frame(1))
            caller.join(2000); assertFalse(caller.isAlive)
            assertEquals(42, result.get().getOrThrow())
            val waiting = CountDownLatch(1)
            val cancelled = AtomicReference<Throwable>()
            val waiter = Thread {
                cancelled.set(runCatching { prototype.executeCommand<Int> {
                    waiting.countDown(); CoreAttempt.Pending
                } }.exceptionOrNull())
            }.apply { isDaemon = true; start() }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            prototype.requestStop()
            waiter.join(2000); assertFalse(waiter.isAlive)
            assertEquals(BackendFailure.Kind.CANCELLED, (cancelled.get() as BackendFailure).kind)
        } finally { prototype.close(); caller.join(2000) }
    }
    @Test fun preparationDoesNotWaitForUsbAndAllCoreCallsStayOnOneThread() {
        val port = Port().apply { sendGate = CountDownLatch(1) }
        val core = Core(listOf(frame(1)))
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            assertTrue(port.sending.await(2, TimeUnit.SECONDS))
            assertEquals(LwipPrototype.State.LISTENING, prototype.snapshot().state)
            await { core.linkStates.contains(false) }
            assertNotSame(core.owner, port.sender)
            port.incoming.put(frame(2))
            assertTrue(core.received.await(2, TimeUnit.SECONDS))
            assertNotSame(core.owner, port.reader)
        } finally { prototype.close() }
        assertEquals(1, port.closes.get())
        assertEquals(1, core.closes.get())
        assertEquals(LwipPrototype.State.STOPPED, prototype.snapshot().state)
        assertNull(prototype.snapshot().failure)
    }

    @Test fun notReadyIsCountedAndDoesNotImplicitlyRetryTheSameFrame() {
        val port = Port().apply { results.put(NcmSendResult.NotReady) }
        val core = Core(listOf(frame(3)))
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            await { prototype.snapshot().txNotReady == 1L }
            assertEquals(1, port.sends.get())
            assertEquals(0L, prototype.snapshot().txSent)
            assertEquals(LwipPrototype.State.LISTENING, prototype.snapshot().state)
            // A later input causes protocol output, rather than replaying an old UDP frame.
            port.incoming.put(frame(4))
            await { prototype.snapshot().txSent == 1L }
            assertEquals(LwipPrototype.State.LINK_ACTIVE, prototype.snapshot().state)
            assertEquals(listOf(3, 4), port.sent.map { it[13].toInt() })
        } finally { prototype.close() }
    }

    @Test fun failedUsbSendStopsTheBackendAndKeepsTheOriginalCause() {
        val cause = IphoneUsbException.DeviceUnavailable("unplugged")
        val port = Port().apply { results.put(NcmSendResult.Failed(cause)) }
        val core = Core(listOf(frame(5)))
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        prototype.startOrFailed()
        assertTrue(prototype.awaitStopped(2_000))
        assertEquals(BackendFailure.Kind.USB_IO, prototype.snapshot().failure!!.kind)
        assertSame(cause, prototype.snapshot().failure!!.cause)
        assertEquals(1, core.closes.get())
        assertEquals(1, port.closes.get())
        prototype.close()
    }

    @Test fun receiveQueueIsBoundedWhileTheCoreConsumerIsPaused() {
        val port = Port()
        val core = Core(emptyList()).apply { inputGate = CountDownLatch(1) }
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            port.incoming.put(frame(1))
            assertTrue(core.received.await(2, TimeUnit.SECONDS))
            repeat(600) { port.incoming.put(ByteArray(1514)) }
            await { prototype.snapshot().rxDropped > 0 }
            assertTrue(prototype.snapshot().rxQueuedBytes <= LwipPrototype.RX_QUEUE_BYTES)
            assertTrue(prototype.snapshot().rxQueuedFrames <= LwipPrototype.RX_QUEUE_FRAMES)
        } finally { core.inputGate!!.countDown(); prototype.close() }
    }

    @Test fun blockedUsbWriterCannotMakeTheCoreDrainUnlimitedOutput() {
        val port = Port().apply { sendGate = CountDownLatch(1) }
        val core = Core(List(160) { ByteArray(1514) })
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            assertTrue(port.sending.await(2, TimeUnit.SECONDS))
            await { prototype.snapshot().txQueuedFrames == LwipPrototype.TX_QUEUE_FRAMES }
            assertTrue(prototype.snapshot().txQueuedBytes <= LwipPrototype.TX_QUEUE_BYTES)
            assertTrue("undrained frames stay in the bounded native owner", core.taken.get() <= LwipPrototype.TX_QUEUE_FRAMES + 1)
            assertTrue("no busy spin while TX is full", core.polls.get() < 20)
        } finally { prototype.close() }
    }

    @Test fun closeTimeoutNeverDestroysAContextStillExecutingOnItsOwnerThread() {
        val port = Port()
        val core = Core(emptyList()).apply { inputGate = CountDownLatch(1) }
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        prototype.start()
        port.incoming.put(frame(1))
        assertTrue(core.received.await(2, TimeUnit.SECONDS))
        prototype.requestStop()
        assertFalse(prototype.awaitStopped(30))
        assertEquals(0, core.closes.get())
        assertEquals(LwipPrototype.State.STOPPING, prototype.snapshot().state)
        core.inputGate!!.countDown()
        assertTrue(prototype.awaitStopped(2_000))
        assertEquals(1, core.closes.get())
        assertEquals(0, prototype.snapshot().rxQueuedBytes)
        assertEquals(0, prototype.snapshot().txQueuedBytes)
    }

    @Test fun preparationFailureClosesOwnedPortAndCannotBeRestarted() {
        val port = Port()
        val prototype = LwipPrototype(port) { throw BackendFailure(BackendFailure.Kind.NATIVE_UNAVAILABLE) }
        try { prototype.start(); fail("must reject failed preparation") } catch (failure: BackendFailure) {
            assertEquals(BackendFailure.Kind.NATIVE_UNAVAILABLE, failure.kind)
        }
        assertTrue(prototype.awaitStopped(2_000))
        assertEquals(1, port.closes.get())
        try { prototype.start(); fail("cannot restart") } catch (_: IllegalStateException) { }
    }

    @Test fun aNotReadyTransitionCannotBeLostBehindALaterSuccessfulSend() {
        val port = Port().apply { results.put(NcmSendResult.NotReady) }
        val gate = CountDownLatch(1)
        val core = Core(listOf(frame(1), frame(2))).apply { timeoutGate = gate }
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            await { prototype.snapshot().txNotReady == 1L && prototype.snapshot().txSent == 1L }
            val previousPauses = core.linkStates.count { !it }
            gate.countDown()
            await { core.polls.get() >= 2 }
            assertTrue("core must observe the intervening link-down", core.linkStates.count { !it } > previousPauses)
        } finally { gate.countDown(); prototype.close() }
    }

    @Test fun awaitAlsoJoinsAUsbCloseWorkerCreatedAfterTheWaitBegan() {
        val gate = CountDownLatch(1)
        val port = Port().apply { closeGate = gate }
        val core = Core(emptyList())
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        val reportedStopped = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        prototype.start()
        val waiter = Thread {
            reportedStopped.set(prototype.awaitStopped(300))
            finished.countDown()
        }
        try {
            waiter.start()
            await { waiter.state == Thread.State.TIMED_WAITING }
            prototype.requestStop()
            assertTrue(port.closing.await(2, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse("USB close is still executing", reportedStopped.get())
            assertEquals(LwipPrototype.State.STOPPING, prototype.snapshot().state)
        } finally { gate.countDown(); waiter.join(2_000); prototype.close() }
    }

    @Test fun repeatedCloseTimeoutsKeepBoundedFailureHistoryAndPreserveLiveOwnership() {
        val gate = CountDownLatch(1)
        val port = Port()
        val core = Core(emptyList()).apply { inputGate = gate }
        val prototype = LwipPrototype(port, closeTimeoutMillis = 5) { core.also { it.owner = Thread.currentThread() } }
        try {
            prototype.start()
            port.incoming.put(frame(1))
            assertTrue(core.received.await(2, TimeUnit.SECONDS))
            repeat(20) {
                try { prototype.close(); fail("live core must not be reported closed") }
                catch (failure: BackendFailure) { assertEquals(BackendFailure.Kind.TIMEOUT, failure.kind) }
            }
            assertTrue(prototype.snapshot().failure!!.suppressed.size <= 4)
            assertEquals(0, core.closes.get())
        } finally { gate.countDown(); assertTrue(prototype.awaitStopped(2_000)) }
    }

    @Test fun aVeryLongExplicitWaitCannotOverflowIntoImmediateSuccessfulShutdown() {
        val gate = CountDownLatch(1)
        val port = Port()
        val core = Core(emptyList()).apply { inputGate = gate }
        val prototype = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        val result = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val waiter = Thread { result.set(prototype.awaitStopped(Long.MAX_VALUE)); done.countDown() }
        try {
            prototype.start()
            port.incoming.put(frame(1))
            assertTrue(core.received.await(2, TimeUnit.SECONDS))
            prototype.requestStop()
            waiter.start()
            await { waiter.state == Thread.State.TIMED_WAITING || waiter.state == Thread.State.TERMINATED }
            assertFalse("the core is still active", done.await(30, TimeUnit.MILLISECONDS))
            gate.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(result.get())
        } finally { gate.countDown(); waiter.join(2_000); prototype.close() }
    }

    private fun LwipPrototype.startOrFailed() {
        try { start() } catch (_: BackendFailure) { }
    }

    private class Core(initial: List<ByteArray>) : LwipPrototypeCore {
        val timeoutEntered = CountDownLatch(1)
        val failingPoll = CountDownLatch(1)
        lateinit var owner: Thread
        private val output = ArrayDeque(initial)
        val received = CountDownLatch(1)
        val closes = AtomicInteger()
        val polls = AtomicInteger()
        val taken = AtomicInteger()
        val linkStates = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        var inputGate: CountDownLatch? = null
        var timeoutGate: CountDownLatch? = null
        var pollFailure: Throwable? = null
        var onClose: (() -> Unit)? = null
        private fun checkOwner() = check(Thread.currentThread() === owner)
        override fun prepare() { checkOwner() }
        override fun input(frame: ByteArray) {
            checkOwner(); received.countDown(); inputGate?.await(); output.add(frame.copyOf())
        }
        override fun poll() { checkOwner(); polls.incrementAndGet(); pollFailure?.let { failingPoll.countDown(); throw it } }
        override fun nextTimeoutMillis(): Long { checkOwner(); timeoutEntered.countDown(); timeoutGate?.await(); return 60_000 }
        override fun takeOutput(): ByteArray? {
            checkOwner()
            return output.pollFirst()?.also { taken.incrementAndGet() }
        }
        override fun setLinkReady(ready: Boolean) { checkOwner(); linkStates.add(ready) }
        override fun close() { checkOwner(); closes.incrementAndGet(); onClose?.invoke() }
    }

    private class Port : EthernetFramePort {
        override val hostMac: ByteArray = byteArrayOf(2, 0, 0, 0, 0, 1)
        val incoming = LinkedBlockingQueue<ByteArray>()
        val results = LinkedBlockingQueue<NcmSendResult>()
        val sent = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val sending = CountDownLatch(1)
        val closes = AtomicInteger()
        val sends = AtomicInteger()
        val closing = CountDownLatch(1)
        var sendGate: CountDownLatch? = null
        var closeGate: CountDownLatch? = null
        @Volatile var closeFailure: Throwable? = null
        var onClose: (() -> Unit)? = null
        @Volatile var reader: Thread? = null
        @Volatile var sender: Thread? = null
        override fun recv(timeoutMillis: Long): ByteArray? {
            reader = Thread.currentThread()
            return incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
            sender = Thread.currentThread(); sending.countDown(); sendGate?.await()
            sent.add(frame); sends.incrementAndGet()
            return results.poll() ?: NcmSendResult.Sent
        }
        override fun close() {
            closes.incrementAndGet(); onClose?.invoke(); sendGate?.countDown(); closing.countDown(); closeGate?.await()
            closeFailure?.let { throw it }
        }
    }

    companion object {
        private fun frame(value: Int) = ByteArray(14).also { it[13] = value.toByte() }
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("condition did not become true", condition())
        }
    }
}

package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.network.backend.MonotonicClock
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun coreCommandClock(): MonotonicClock {
    val origin = System.nanoTime()
    return MonotonicClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin) }
}

/**
 * Pull boundary for a bounded native owner, not a callback from inside lwIP into Java.
 * Factory, prepare and every operation (including close) run on one core thread.
 * input borrows its array until return; takeOutput transfers an owned frame or returns null.
 * The native implementation must cap retained output at 256 KiB including metadata.
 */
interface LwipPrototypeCore : Closeable {
    fun prepare()
    fun input(frame: ByteArray)
    fun poll()
    fun nextTimeoutMillis(): Long
    fun takeOutput(): ByteArray?
    fun setLinkReady(ready: Boolean)
}

/**
 * P2 transport runner. The explicit nativeEcho factory is separate from production backend selection.
 * Owns port and core. Blocking port operations normally run only on I/O/close workers. Failures remain
 * available in snapshot after termination. A timed-out join never frees the live core externally. If
 * creating the close worker fails, an emergency inline close follows release of core ownership.
 */
class LwipPrototype(
    private val port: EthernetFramePort,
    private val closeTimeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
    internal val commandClock: MonotonicClock = coreCommandClock(),
    internal val workerFactory: (String, () -> Unit) -> Thread = { name, body ->
        Thread(body, name).apply { isDaemon = true }
    },
    private val coreFactory: () -> LwipPrototypeCore,
) : Closeable {
    init { require(closeTimeoutMillis in 1..CLOSE_TIMEOUT_MILLIS) }
    enum class State { NEW, PREPARING, LISTENING, LINK_ACTIVE, STOPPING, STOPPED }
    data class Snapshot(
        val state: State, val failure: BackendFailure?,
        val rxQueuedBytes: Int, val rxQueuedFrames: Int,
        val txQueuedBytes: Int, val txQueuedFrames: Int,
        val rxDropped: Long, val txSent: Long, val txNotReady: Long,
        val commandCount: Int = 0, val commandBytes: Int = 0,
        val resourceScopes: Int = 0, val closingResourceScopes: Int = 0,
        val directBufferAllocatedBytes: Int = 0, val directBufferLeasedBytes: Int = 0,
        val directBufferLeasedCount: Int = 0, val directBufferPeakLeasedBytes: Int = 0,
        val rxQueueHighWaterBytes: Int = 0, val rxQueueHighWaterFrames: Int = 0,
        val txQueueHighWaterBytes: Int = 0, val txQueueHighWaterFrames: Int = 0,
        val commandHighWaterCount: Int = 0, val commandHighWaterBytes: Int = 0,
    )

    private val signal = Object()
    private val commands = CoreCommandQueue<LwipPrototypeCore>(commandClock, { synchronized(signal) { wake() } })
    private val directBuffers = DirectBufferPool()
    private val prepared = CountDownLatch(1)
    private val rx = ArrayDeque<ByteArray>()
    private val tx = ArrayDeque<ByteArray>()
    private var rxBytes = 0
    private var txBytes = 0
    private var rxQueueHighWaterBytes = 0
    private var rxQueueHighWaterFrames = 0
    private var txQueueHighWaterBytes = 0
    private var txQueueHighWaterFrames = 0
    private var state = State.NEW
    private var failure: BackendFailure? = null
    private var rxDropped = 0L
    private var txSent = 0L
    private var txNotReady = 0L
    private var revision = 0L
    private var readinessRevision = 0L
    private var usbReady = false
    private var linkMustPause = false
    @Volatile private var stopping = false
    private var coreThread: Thread? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private var closeThread: Thread? = null
    private var inlineCloseInProgress = false
    private var inlineClosePendingForCore = false

    fun snapshot(): Snapshot = synchronized(signal) {
        val commandState = commands.snapshot()
        val commandHighWater = commands.highWaterSnapshot()
        val bufferState = directBuffers.snapshot()
        Snapshot(state, failure, rxBytes, rx.size, txBytes, tx.size, rxDropped, txSent, txNotReady,
            commandState.commands, commandState.bytes, commandState.resources, commandState.closingResources,
            bufferState.allocatedBytes, bufferState.leasedBytes, bufferState.leasedCount, bufferState.peakLeasedBytes,
            rxQueueHighWaterBytes, rxQueueHighWaterFrames, txQueueHighWaterBytes, txQueueHighWaterFrames,
            commandHighWater.commands, commandHighWater.bytes)
    }

    internal fun acquireDirectBuffer(bytes: Int) = directBuffers.acquire(bytes)
    internal fun newCommandScope() = commands.newScope()
    internal fun newResourceScope(release: (LwipPrototypeCore) -> Unit) = commands.newResourceScope(release)
    internal fun closeResourceScope(scope: CoreCommandQueue.Scope,
                                    reason: BackendFailure = BackendFailure(BackendFailure.Kind.CANCELLED)): Boolean =
        commands.closeResource(scope, reason)
    internal fun cancelCommands(scope: CoreCommandQueue.Scope) =
        commands.cancel(scope, BackendFailure(BackendFailure.Kind.CANCELLED))

    internal fun <T> executePreparedCommand(retainedBytes: Int = 0, deadlineMillis: Long = 0,
                                           discard: (T) -> Unit = {}, scope: CoreCommandQueue.Scope? = null,
                                           onRetired: () -> Unit = {},
                                           prepare: () -> ((LwipPrototypeCore) -> CoreAttempt<T>)): T {
        checkCommandCaller()
        return commands.callPrepared(retainedBytes, deadlineMillis, discard, scope, onRetired, prepare)
    }

    internal fun <T, R> executePreparedDelivered(retainedBytes: Int, deadlineMillis: Long,
                                                scope: CoreCommandQueue.Scope, deliver: (T) -> R, discard: (T) -> Unit = {},
                                                onRetired: () -> Unit = {},
                                                prepare: () -> ((LwipPrototypeCore) -> CoreAttempt<T>)): R {
        checkCommandCaller()
        return commands.callPreparedDeliver(retainedBytes, deadlineMillis, scope = scope, deliver = deliver,
            discard = discard, onRetired = onRetired, prepare = prepare)
    }

    internal fun <T> executeCommand(retainedBytes: Int = 0, deadlineMillis: Long = 0,
                                   discard: (T) -> Unit = {}, scope: CoreCommandQueue.Scope? = null,
                                   operation: (LwipPrototypeCore) -> CoreAttempt<T>): T {
        checkCommandCaller()
        return commands.call(retainedBytes, deadlineMillis, discard, scope, operation)
    }

    internal fun checkCommandCaller() {
        synchronized(signal) {
            if (Thread.currentThread() === coreThread) throw BackendFailure(BackendFailure.Kind.INTERNAL)
            failure?.let { throw it }
            if (state != State.LISTENING && state != State.LINK_ACTIVE) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        }
    }

    fun start() {
        var startupError: Throwable? = null
        synchronized(signal) {
            check(state == State.NEW) { "prototype cannot be restarted" }
            state = State.PREPARING
            try {
                coreThread = worker("lwip-core", ::runCore)
                readThread = worker("lwip-usb-in", ::readFrames)
                writeThread = worker("lwip-usb-out", ::writeFrames)
                coreThread!!.start(); readThread!!.start(); writeThread!!.start()
            } catch (error: Throwable) {
                startupError = error
            }
        }
        startupError?.let { stop(BackendFailure(BackendFailure.Kind.BACKEND_INIT, it)) }
        try {
            if (!prepared.await(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                stop(BackendFailure(BackendFailure.Kind.TIMEOUT))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            stop(BackendFailure(BackendFailure.Kind.CANCELLED, error))
        }
        synchronized(signal) {
            failure?.let { throw it }
            if (stopping) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        }
    }

    fun requestStop() = stop(null)

    private fun stop(error: BackendFailure?) {
        var closeInline = false
        synchronized(signal) {
            error?.let(::recordFailureLocked)
            if (stopping) return
            stopping = true
            state = State.STOPPING
            commands.close(failure ?: BackendFailure(BackendFailure.Kind.CANCELLED))
            rx.clear(); tx.clear(); rxBytes = 0; txBytes = 0
            wake()
            prepared.countDown()
            // Normal USB shutdown is isolated from the core. If worker allocation fails on the core,
            // defer emergency inline close until after core.close().
            try {
                val thread = worker("lwip-usb-close") {
                    try { port.close() } catch (closeError: Throwable) {
                        stop(BackendFailure(BackendFailure.Kind.USB_IO, closeError))
                    }
                }
                closeThread = thread
                try {
                    thread.start()
                } catch (startError: Throwable) {
                    if (thread.isAlive) {
                        recordFailureLocked(BackendFailure(BackendFailure.Kind.BACKEND_INIT, startError))
                    } else {
                        closeThread = null
                        recordFailureLocked(BackendFailure(BackendFailure.Kind.BACKEND_INIT, startError))
                        if (Thread.currentThread() === coreThread) {
                            inlineClosePendingForCore = true
                        } else {
                            inlineCloseInProgress = true
                            closeInline = true
                        }
                    }
                }
            } catch (allocationError: Throwable) {
                recordFailureLocked(BackendFailure(BackendFailure.Kind.BACKEND_INIT, allocationError))
                if (Thread.currentThread() === coreThread) {
                    inlineClosePendingForCore = true
                } else {
                    inlineCloseInProgress = true
                    closeInline = true
                }
            }
        }
        if (closeInline) closePortInline()
    }

    /** Must be called with [signal] held. */
    private fun recordFailureLocked(error: BackendFailure) {
        if (failure == null) failure = error
        else if (failure !== error && failure!!.suppressed.size < MAX_SUPPRESSED_FAILURES) failure!!.addSuppressed(error)
    }

    private fun closePortInline() {
        try {
            port.close()
        } catch (closeError: Throwable) {
            synchronized(signal) { recordFailureLocked(BackendFailure(BackendFailure.Kind.USB_IO, closeError)) }
        } finally {
            synchronized(signal) {
                inlineCloseInProgress = false
                wake()
            }
        }
    }

    override fun close() {
        requestStop()
        check(Thread.currentThread() !in workers()) { "close must join from outside the workers" }
        if (!awaitStopped(closeTimeoutMillis)) {
            val error = BackendFailure(BackendFailure.Kind.TIMEOUT)
            stop(error)
            throw error
        }
    }

    /** False leaves the workers and their native ownership intact for a later join. */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            for (thread in workers()) {
                if (thread === Thread.currentThread()) return false
                while (thread.isAlive) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return false
                    try { TimeUnit.NANOSECONDS.timedJoin(thread, left) } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            var waitingForInlineClose = false
            synchronized(signal) {
                if (!stopping) return false
                // stop may have created the close worker while the original workers were joined.
                if (workers().none { it.isAlive }) {
                    if (inlineCloseInProgress || inlineClosePendingForCore) {
                        val left = deadline - System.nanoTime()
                        if (left <= 0) return false
                        try { TimeUnit.NANOSECONDS.timedWait(signal, left) } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                        waitingForInlineClose = true
                    } else {
                        state = State.STOPPED
                        return true
                    }
                }
            }
            if (waitingForInlineClose) continue
        }
    }

    private fun workers(): List<Thread> = synchronized(signal) {
        listOfNotNull(coreThread, readThread, writeThread, closeThread)
    }

    private fun runCore() {
        var core: LwipPrototypeCore? = null
        try {
            if (stopping) return
            core = coreFactory()
            if (stopping) return
            core.prepare()
            if (stopping) return
            // Allow an initial DAD/probe without waiting for StartCarPlaySession's first USB ACK.
            core.setLinkReady(true)
            synchronized(signal) { if (!stopping) state = State.LISTENING }
            prepared.countDown()
            var seenReadiness = 0L
            var retryAt = Long.MAX_VALUE
            var timerAt = System.nanoTime()
            while (!stopping) {
                val before = synchronized(signal) { revision }
                var madeProgress = System.nanoTime() >= timerAt
                val readiness = synchronized(signal) {
                    Triple(readinessRevision, usbReady, linkMustPause).also { linkMustPause = false }
                }
                // A later success may coalesce with NotReady, but must not erase the DAD reset.
                if (readiness.third) { core.setLinkReady(false); madeProgress = true }
                if (readiness.first != seenReadiness) {
                    seenReadiness = readiness.first
                    core.setLinkReady(readiness.second)
                    retryAt = if (readiness.second) Long.MAX_VALUE else System.nanoTime() + RETRY_NANOS
                    madeProgress = true
                }
                if (System.nanoTime() >= retryAt) {
                    core.setLinkReady(true)
                    retryAt = Long.MAX_VALUE
                    madeProgress = true
                }
                repeat(BATCH_FRAMES) {
                    val frame = synchronized(signal) {
                        if (stopping) null else rx.pollFirst()?.also { rxBytes -= it.size }
                    }
                    if (frame != null && !stopping) { core.input(frame); madeProgress = true }
                }
                if (stopping) break
                core.poll()
                if (madeProgress) commands.progress()
                val commandBatch = commands.runBatch(core)
                if (commandBatch.completed) commands.progress()
                val moreCommands = commands.hasReady()
                if (stopping) break
                var drained = 0
                while (drained < BATCH_FRAMES && !stopping && canQueueTx()) {
                    val frame = core.takeOutput() ?: break
                    check(frame.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) { "invalid core output length" }
                    synchronized(signal) {
                        if (!stopping) {
                            tx.addLast(frame); txBytes += frame.size
                            txQueueHighWaterBytes = maxOf(txQueueHighWaterBytes, txBytes)
                            txQueueHighWaterFrames = maxOf(txQueueHighWaterFrames, tx.size)
                            signal.notifyAll()
                        }
                    }
                    drained++
                }
                // A queued startup probe is not a delivered DAD probe. Keep it tentative while
                // the writer is still waiting for its first result, even if USB stalls.
                if (drained > 0 && !synchronized(signal) { usbReady }) core.setLinkReady(false)
                val timeoutMillis = core.nextTimeoutMillis()
                check(timeoutMillis in 0..MAX_TIMER_MILLIS) { "invalid core timer" }
                timerAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                var waitNanos = TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMillis, commands.nextDelayMillis()))
                if (retryAt != Long.MAX_VALUE) waitNanos = minOf(waitNanos, (retryAt - System.nanoTime()).coerceAtLeast(0))
                synchronized(signal) {
                    val moreOutput = drained == BATCH_FRAMES && canQueueTx()
                    if (!stopping && revision == before && rx.isEmpty() && !moreOutput && !moreCommands && waitNanos > 0) {
                        TimeUnit.NANOSECONDS.timedWait(signal, waitNanos)
                    }
                }
            }
        } catch (error: Throwable) {
            stop(if (error is BackendFailure) error else BackendFailure(BackendFailure.Kind.INTERNAL, error))
        } finally {
            try { core?.close() } catch (error: Throwable) {
                stop(BackendFailure(BackendFailure.Kind.INTERNAL, error))
            }
            try { directBuffers.close() } catch (error: Throwable) {
                stop(BackendFailure(BackendFailure.Kind.INTERNAL, error))
            } finally {
                closePortAfterCoreIfNeeded()
                requestStop()
            }
        }
    }

    private fun closePortAfterCoreIfNeeded() {
        val closeInline = synchronized(signal) {
            if (!inlineClosePendingForCore) false else {
                inlineClosePendingForCore = false
                inlineCloseInProgress = true
                true
            }
        }
        if (closeInline) closePortInline()
    }

    private fun readFrames() {
        try {
            while (!stopping) {
                val frame = port.recv(IO_TIMEOUT_MILLIS.toLong()) ?: continue
                synchronized(signal) {
                    if (!stopping) {
                        if (frame.size !in MIN_FRAME_BYTES..MAX_FRAME_BYTES || rx.size >= RX_QUEUE_FRAMES || rxBytes + frame.size > RX_QUEUE_BYTES) {
                            rxDropped++
                        } else {
                            rx.addLast(frame); rxBytes += frame.size
                            rxQueueHighWaterBytes = maxOf(rxQueueHighWaterBytes, rxBytes)
                            rxQueueHighWaterFrames = maxOf(rxQueueHighWaterFrames, rx.size)
                            wake()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopping) stop(usbFailure(error))
        }
    }

    private fun writeFrames() {
        try {
            while (!stopping) {
                val frame = synchronized(signal) {
                    while (!stopping && tx.isEmpty()) signal.wait()
                    if (stopping) null else tx.removeFirst().also { txBytes -= it.size; wake() }
                } ?: break
                val result = port.send(frame, IO_TIMEOUT_MILLIS)
                var sendFailure: BackendFailure? = null
                synchronized(signal) {
                    if (!stopping) when (result) {
                        NcmSendResult.Sent -> { txSent++; usbReady = true; state = State.LINK_ACTIVE }
                        NcmSendResult.NotReady -> { txNotReady++; usbReady = false; linkMustPause = true; state = State.LISTENING }
                        is NcmSendResult.Failed -> { sendFailure = BackendFailure(BackendFailure.Kind.USB_IO, result.cause) }
                    }
                    readinessRevision++
                    wake()
                }
                sendFailure?.let { stop(it); return }
            }
        } catch (error: Throwable) {
            if (!stopping) stop(usbFailure(error))
        }
    }

    private fun canQueueTx(): Boolean = synchronized(signal) {
        tx.size < TX_QUEUE_FRAMES && txBytes + MAX_FRAME_BYTES <= TX_QUEUE_BYTES
    }
    private fun usbFailure(error: Throwable) = if (error is BackendFailure) error else BackendFailure(BackendFailure.Kind.USB_IO, error)
    private fun wake() { revision++; signal.notifyAll() }
    private fun worker(name: String, body: () -> Unit) = workerFactory(name, body)

    companion object {
        /** Explicit P2 echo harness; creates native state only on the runner's core thread. */
        fun nativeEcho(port: EthernetFramePort, mac: ByteArray, ipv6: ByteArray, mtu: Int = 1500): LwipPrototype {
            require(mac.size == 6 && ipv6.size == 16 && mtu in 1280..1500)
            val ownedMac = mac.copyOf()
            val ownedIp = ipv6.copyOf()
            val clock = coreCommandClock()
            return LwipPrototype(port, commandClock = clock) {
                NativePrototypeCore.create(ownedMac, ownedIp, mtu, clockMillis = clock::nowMillis)
            }
        }

        const val RX_QUEUE_BYTES = 128 * 1024
        const val RX_QUEUE_FRAMES = 128
        const val TX_QUEUE_BYTES = 192 * 1024
        const val TX_QUEUE_FRAMES = 128
        private const val MIN_FRAME_BYTES = 14
        private const val MAX_FRAME_BYTES = 1514
        private const val BATCH_FRAMES = 32
        private const val IO_TIMEOUT_MILLIS = 100
        private const val START_TIMEOUT_MILLIS = 2_000L
        private const val CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val RETRY_NANOS = 100_000_000L
        private const val MAX_TIMER_MILLIS = 0xffff_ffffL
        private const val MAX_SUPPRESSED_FAILURES = 4
    }
}

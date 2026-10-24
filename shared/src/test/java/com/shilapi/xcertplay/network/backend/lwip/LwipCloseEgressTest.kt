package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LwipCloseEgressTest {
    @Test fun waitsForInFlightUsbSendRatherThanEmptyQueue() {
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = Port { sending.countDown(); release.await(); NcmSendResult.Sent }
        val runner = LwipPrototype(port, commandClock = MonotonicClock { 0 }) { Core() }
        runner.start()
        try {
            assertTrue(sending.await(2, TimeUnit.SECONDS))
            assertEquals(LocalEgressResult.DEADLINE, runner.awaitLocalEgress(20) { false })
            release.countDown()
            assertEquals(LocalEgressResult.LOCAL_EGRESS_DRAINED, runner.awaitLocalEgress(2000) { false })
            assertEquals(0, port.closes)
        } finally { release.countDown(); runner.close() }
    }
    @Test fun notReadyIsNotReportedAsSuccessfulEgress() {
        val port = Port { NcmSendResult.NotReady }
        val runner = LwipPrototype(port, commandClock = MonotonicClock { 0 }) { Core() }
        runner.start()
        try { assertEquals(LocalEgressResult.NOT_CONFIRMED, runner.awaitLocalEgress(2000) { false }) }
        finally { runner.close() }
    }
    @Test fun detachedUsbCancelsPendingEgressWithoutReleasingNetwork() {
        val release = CountDownLatch(1)
        val port = Port { release.await(); NcmSendResult.Sent }
        val runner = LwipPrototype(port, commandClock = MonotonicClock { 0 }) { Core() }
        runner.start()
        try {
            assertEquals(LocalEgressResult.CANCELLED, runner.awaitLocalEgress(2000) { true })
            assertEquals(0, port.closes)
        } finally { release.countDown(); runner.close() }
    }
    @Test fun historicalNotReadyStillWaitsForCloseCommandBarrier() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = Port { NcmSendResult.NotReady }
        val core = Core(entered, release)
        val runner = LwipPrototype(port, commandClock = MonotonicClock { 0 }) { core }
        runner.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runner.snapshot().txNotReady == 0L && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1L, runner.snapshot().txNotReady)
            port.receive(ByteArray(14))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // No empty-output barrier can run while the core is stuck in the earlier operation.
            assertEquals(LocalEgressResult.DEADLINE, runner.awaitLocalEgress(20) { false })
            release.countDown()
            assertEquals(LocalEgressResult.NOT_CONFIRMED, runner.awaitLocalEgress(2000) { false })
        } finally { release.countDown(); runner.close() }
    }
    private class Core(private val entered: CountDownLatch? = null, private val release: CountDownLatch? = null) : LwipPrototypeCore {
        private var frame: ByteArray? = ByteArray(14)
        override fun prepare() {}
        override fun input(frame: ByteArray) { entered?.countDown(); release?.await() }
        override fun poll() {}
        override fun nextTimeoutMillis() = 60_000L
        override fun takeOutput(): ByteArray? = frame.also { frame = null }
        override fun setLinkReady(ready: Boolean) {}
        override fun close() {}
    }
    private class Port(val send: () -> NcmSendResult) : EthernetFramePort {
        override val hostMac = ByteArray(6)
        private val incoming = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        fun receive(frame: ByteArray) { incoming.offer(frame) }
        var closes = 0
        override fun recv(timeoutMillis: Long): ByteArray? = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun send(frame: ByteArray, timeoutMillis: Int) = send()
        override fun close() { closes++; incoming.offer(ByteArray(14)) }
    }
}

package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.shilapi.xcertplay.network.backend.NcmFramePort
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A NAKed NCM bulk OUT must not hold a USB transfer for the full timeout before the session (#634). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [NcmWriteConnectionShadow::class])
class NcmUsbBridgeWriteTimeoutTest {
    @Before fun reset() { NcmWriteReplay.reset() }

    @Test fun framesBeforeThePhoneTakesOneUseTheShortTimeoutThenTheCallers() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL, NcmWriteReplay.ALL))
        val diagnostics = withBridge { ncm -> repeat(4) { ncm.send(frame, 2_000) } }
        val short = NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS
        assertEquals(listOf(short, short, short, 2_000), NcmWriteReplay.timeouts)
        assertEquals(listOf("NCM bulk-out ready after 2 dropped frames"), diagnostics.filter { it.startsWith("NCM bulk-out") })
        assertTrue(diagnostics.any { it.startsWith("NCM transfer tx returned=-1") && it.contains("durationMs=") })
        assertTrue(diagnostics.any { it.startsWith("NCM transfer close") && it.contains("notReady=2") })
    }

    @Test fun theLinkOpensWithTheFirstFrameThePhoneTakes() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL))
        val opened = mutableListOf<Long?>()
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000); opened += ncm.linkOpenedAtNanos } }
        assertNull(opened[0])
        assertNull(opened[1])
        assertNotNull(opened[2])
    }

    @Test fun aTimeoutAfterThePipeWasReadyKeepsTheCallersTimeout() {
        NcmWriteReplay.results.addAll(listOf(NcmWriteReplay.ALL, -1, NcmWriteReplay.ALL))
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000) } }
        assertEquals(listOf(NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS, 2_000, 2_000), NcmWriteReplay.timeouts)
    }

    @Test fun aShorterCallerTimeoutIsKept() {
        NcmWriteReplay.results.add(-1)
        withBridge { ncm -> ncm.send(frame, 5) }
        assertEquals(listOf(5), NcmWriteReplay.timeouts)
    }

    @Test fun diagnosticConsumerFailureCannotChangeUsbResults() {
        NcmWriteReplay.results.addAll(listOf(-1, NcmWriteReplay.ALL))
        withBridge(onDiagnostic = { throw IllegalStateException("diagnostic sink unavailable") }) { ncm ->
            assertEquals(NcmSendResult.NotReady, ncm.sendFrame(frame, 100))
            assertEquals(NcmSendResult.Sent, ncm.sendFrame(frame, 100))
        }
    }

    @Test fun explicitResultsNeverCountNotReadyFramesAsSent() {
        NcmWriteReplay.results.addAll(listOf(-1, 0, NcmWriteReplay.ALL, -1))
        withBridge { ncm ->
            assertEquals(NcmSendResult.NotReady, ncm.sendFrame(frame, 100))
            assertEquals(NcmSendResult.NotReady, ncm.sendFrame(frame, 100))
            assertEquals(NcmSendResult.Sent, ncm.sendFrame(frame, 100))
            assertEquals(NcmSendResult.NotReady, ncm.sendFrame(frame, 100))
            assertTrue(ncm.trafficSummary(null).endsWith("toIphone=1"))
        }
    }

    @Test fun aShortWriteFailsWithoutOpeningTheLinkOrCountingDelivery() {
        NcmWriteReplay.results.add(1)
        withBridge { ncm ->
            val result = ncm.sendFrame(frame, 100)
            assertTrue(result is NcmSendResult.Failed)
            assertNull(ncm.linkOpenedAtNanos)
            assertTrue(ncm.trafficSummary(null).endsWith("toIphone=0"))
        }
    }

    @Test fun closedBridgeReturnsFailureWithoutTouchingUsb() {
        withBridge { ncm ->
            ncm.close()
            assertTrue(ncm.sendFrame(frame, 100) is NcmSendResult.Failed)
            assertTrue(NcmWriteReplay.timeouts.isEmpty())
        }
    }

    @Test fun detachExceptionReturnsTerminalFailureWithItsCause() {
        val detached = IllegalStateException("detached")
        NcmWriteReplay.beforeTransfer = { throw detached }
        withBridge { ncm ->
            val result = ncm.sendFrame(frame, 100) as NcmSendResult.Failed
            assertEquals(detached, result.cause.cause)
            NcmWriteReplay.beforeTransfer = null
            assertTrue(ncm.sendFrame(frame, 100) is NcmSendResult.Failed)
            assertEquals(1, NcmWriteReplay.timeouts.size)
        }
    }

    @Test fun closeDuringTheTransferCannotPublishSuccessfulDelivery() {
        withBridge { ncm ->
            NcmWriteReplay.beforeTransfer = { ncm.close() }
            assertTrue(ncm.sendFrame(frame, 100) is NcmSendResult.Failed)
            assertNull(ncm.linkOpenedAtNanos)
            assertTrue(ncm.trafficSummary(null).endsWith("toIphone=0"))
        }
    }

    @Test fun framePortOwnsTheBridgeAndRejectsOversizedOutputBeforeUsb() {
        withBridge { ncm ->
            val port = NcmFramePort(ncm)
            assertTrue(port.send(ByteArray(1515), 100) is NcmSendResult.Failed)
            assertTrue(NcmWriteReplay.timeouts.isEmpty())
            assertEquals(NcmSendResult.Sent, port.send(ByteArray(1514), 100))
            port.close()
            assertTrue(ncm.sendFrame(frame, 100) is NcmSendResult.Failed)
        }
    }

    @Test fun framePortDoesNotExposeMutableDescriptorMacStorage() {
        val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        withBridge(mac) { ncm ->
            val port = NcmFramePort(ncm)
            mac[0] = 9
            port.hostMac!![0] = 7
            assertEquals(2.toByte(), port.hostMac!![0])
        }
    }

    @Test fun readinessDiagnosticDoesNotHoldTheBridgeStateLock() {
        val reference = AtomicReference<NcmUsbBridge>()
        var closedDuringCallback = false
        var closer: Thread? = null
        withBridge(onDiagnostic = {
            val closed = CountDownLatch(1)
            closer = Thread { reference.get().close(); closed.countDown() }.apply { start() }
            closedDuringCallback = closed.await(1, TimeUnit.SECONDS)
        }) { ncm ->
            reference.set(ncm)
            ncm.sendFrame(frame, 100)
        }
        closer?.join(2_000)
        assertTrue("diagnostic must allow another thread to close", closedDuringCallback)
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacySendKeepsInvalidFrameValidation() {
        withBridge { it.send(byteArrayOf(), 100) }
    }

    private fun withBridge(
        hostMac: ByteArray? = null,
        onDiagnostic: (String) -> Unit = {},
        block: (NcmUsbBridge) -> Unit,
    ): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x88), null, emptyList(), hostMac) {
            diagnostics += it
            onDiagnostic(it)
        }
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    private companion object {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmWriteReplay {
    const val ALL = Int.MAX_VALUE
    /** One result per bulkTransfer: a byte count, or [ALL] for the whole frame. */
    val results = ArrayDeque<Int>()
    val timeouts = mutableListOf<Int>()
    var beforeTransfer: (() -> Unit)? = null
    fun reset() { results.clear(); timeouts.clear(); beforeTransfer = null }
}

@Implements(UsbDeviceConnection::class)
class NcmWriteConnectionShadow {
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        NcmWriteReplay.timeouts += timeout
        NcmWriteReplay.beforeTransfer?.invoke()
        return NcmWriteReplay.results.pollFirst()?.takeIf { it != NcmWriteReplay.ALL } ?: length
    }
    @Implementation fun close() = Unit
}

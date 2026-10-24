package com.shilapi.xcertplay.network.backend.lwip

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.network.backend.BackendRuntimeCountersProvider
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.network.backend.NetworkEndpoint
import com.shilapi.xcertplay.network.backend.TcpListener
import com.shilapi.xcertplay.network.backend.UdpEndpoint
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Repeats JNI session and native endpoint teardown without claiming physical USB coverage. */
@RunWith(AndroidJUnit4::class)
class LwipSessionLifecycleInstrumentationTest {
    @Test(timeout = 120_000)
    fun repeatedNativeSessionEndpointLifecyclesReleaseScopesAndWorkers() {
        fun liveWorkers() = Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name in LWIP_WORKER_NAMES }
            .groupingBy { it.name }
            .eachCount()

        val workerBaseline = liveWorkers()
        repeat(100) { iteration ->
            val incoming = LinkedBlockingQueue<ByteArray>()
            val portCloses = AtomicInteger()
            val port = object : EthernetFramePort {
                override val hostMac = MAC.copyOf()
                override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
                override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                override fun close() {
                    portCloses.incrementAndGet()
                    incoming.offer(ByteArray(14))
                }
            }
            val linkId = "lwip-session-lifecycle-$iteration"
            val network = LwipSessionNetwork.native(port, MAC, IP, linkId)
            var listener: TcpListener? = null
            var udp: UdpEndpoint? = null
            try {
                val boundListener = network.listen(NetworkEndpoint(IP, 0, linkId), backlog = 4)
                listener = boundListener
                val boundUdp = network.bindUdp(NetworkEndpoint(IP, 0, linkId))
                udp = boundUdp
                assertTrue("TCP listener must have a native ephemeral port", boundListener.localEndpoint.port in 1..65535)
                assertTrue("UDP endpoint must have a native ephemeral port", boundUdp.localEndpoint.port in 1..65535)
                boundUdp.send(byteArrayOf(iteration.toByte()), boundUdp.localEndpoint)
                assertEquals("each endpoint owns a native resource scope", 2L,
                    (network as BackendRuntimeCountersProvider).backendRuntimeCounters().liveResourceScopes)
                assertEquals("completed UDP command must retire its direct buffer lease", 0L,
                    (network as BackendRuntimeCountersProvider).backendRuntimeCounters().directBufferCurrentBytes)
            } finally {
                try { listener?.close() } finally {
                    try { udp?.close() } finally { network.close() }
                }
            }

            val counters = (network as BackendRuntimeCountersProvider).backendRuntimeCounters()
            assertEquals("native endpoint scopes leaked in cycle $iteration", 0L, counters.liveResourceScopes)
            assertEquals("DirectByteBuffer leases leaked in cycle $iteration", 0L, counters.directBufferCurrentBytes)
            assertEquals("frame port must close exactly once in cycle $iteration", 1, portCloses.get())
            assertEquals("lwIP workers leaked after cycle $iteration", workerBaseline, liveWorkers())
        }
    }

    private companion object {
        val LWIP_WORKER_NAMES = setOf("lwip-core", "lwip-usb-in", "lwip-usb-out", "lwip-usb-close")
        val MAC = byteArrayOf(2, 0, 0, 0, 0, 1)
        val IP = ByteArray(16).also {
            it[0] = 0xfe.toByte()
            it[1] = 0x80.toByte()
            it[15] = 1
        }
    }
}

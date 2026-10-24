package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayRetainedNetworkTest {
    @Test fun quiesceStopsListenerButPreservesNetworkUntilHandleClosed() {
        val network = Network()
        AirPlaySessionHost().use { host ->
            val lease = attach(host, network)
            val retained = checkNotNull(host.quiesce(lease))
            retained.closeSessionsGracefully()
            assertFalse(host.isAttached())
            assertEquals(0, network.closes)
            assertEquals(LocalEgressResult.UNSUPPORTED, retained.awaitLocalEgress(20) { false })
            retained.close()
            retained.close()
            assertEquals(1, network.closes)
        }
    }
    @Test fun staleQuiesceAndRetainedCloseCannotReleaseReplacement() {
        val old = Network()
        val replacement = Network()
        AirPlaySessionHost().use { host ->
            val lease = attach(host, old)
            val retained = checkNotNull(host.quiesce(lease))
            val current = attach(host, replacement)
            assertNull(host.quiesce(lease))
            retained.close()
            assertNotNull(host.boundPort(current))
            assertEquals(0, replacement.closes)
            assertEquals(1, old.closes)
        }
        assertEquals(1, replacement.closes)
    }
    @Test fun gracefulCandidateClosesEstablishedControlWithEofRatherThanReset() {
        val network = Network()
        AirPlaySessionHost().use { host ->
            val lease = attach(host, network)
            java.net.Socket(InetAddress.getByName("127.0.0.1"), checkNotNull(host.boundPort(lease))).use { peer ->
                peer.soTimeout = 2000
                peer.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                val input = peer.getInputStream()
                fun line(): String {
                    val result = StringBuilder()
                    while (true) {
                        val byte = input.read()
                        check(byte >= 0)
                        if (byte == 10) return result.toString().trimEnd('\r')
                        result.append(byte.toChar())
                    }
                }
                assertEquals("HTTP/1.1 200 OK", line())
                var length = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                }
                repeat(length) { assertTrue(input.read() >= 0) }
                val retained = checkNotNull(host.quiesce(lease))
                retained.closeSessionsGracefully()
                try { assertEquals(-1, input.read()) } finally { retained.close() }
            }
        }
    }
    @Test fun abortCanReleaseNetworkWhileGracefulSessionCallbackIsBlocked() {
        val network = Network()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val retained = AirPlaySessionHost.RetainedNetwork(network) {
            entered.countDown()
            release.await()
        }
        val worker = Thread { retained.closeSessionsGracefully() }
        try {
            worker.start()
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            retained.close()
            assertEquals(1, network.closes)
            assertTrue(worker.isAlive)
        } finally { release.countDown(); worker.join(2000); retained.close() }
    }
    private fun attach(host: AirPlaySessionHost, network: SessionNetwork) = host.attach(
        InetAddress.getByName("127.0.0.1"),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, network = network,
    )
    private class Network(private val delegate: SessionNetwork = SystemSessionNetwork("retained-test")) : SessionNetwork by delegate {
        var closes = 0
        override fun close() { closes++; delegate.close() }
    }
}

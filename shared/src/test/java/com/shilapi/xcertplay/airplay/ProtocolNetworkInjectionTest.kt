package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.network.AirPlaySessionHost
import java.net.InetAddress
import java.net.Inet6Address
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import android.Manifest
import com.shilapi.xcertplay.media.MicrophoneUplink
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ProtocolNetworkInjectionTest {
    @Test fun ipv4PreferredHostPassesSecondaryIpv6ScopeToItsSystemFactory() {
        val network = VirtualNetwork()
        var scope = 0
        AirPlaySessionHost(systemNetworkFactory = { scope = it; network }).use { host ->
            host.attach(InetAddress.getByName("192.0.2.1"),
                AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                additionalBindAddresses = listOf(Inet6Address.getByAddress(null,
                    InetAddress.getByName("fe80::1234").address, 7)))
            assertEquals(7, scope)
        }
        assertTrue(network.closed)
    }

    @Test fun microphoneNegotiationCarriesTheSameNetworkAndRealPeer() {
        val network = VirtualNetwork()
        val engine = CarPlayMediaEngine(object : MediaSink {}, microphoneEnabled = true)
        val session = session(network, engine)
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32))
        try {
            val method = CarPlayMediaEngine::class.java.getDeclaredMethod("microphoneConfig",
                AirPlaySession::class.java, Int::class.javaPrimitiveType, Map::class.java, AudioFormat::class.java)
                .apply { isAccessible = true }
            val result = method.invoke(engine, session, 100,
                mapOf("dataPort" to 34567, "streamConnectionID" to 42L),
                AudioFormat(AudioCodecKind.LPCM, 16000, 1, 100, "speechrecognition")) as MicrophoneConfig
            assertSame(network, result.network)
            assertArrayEquals(network.peer.address, result.host.address)
            assertEquals(34567, result.port)
        } finally { session.close() }
    }

    @Test fun closingOneControlSessionDoesNotCloseAnotherOnTheSameNetwork() {
        val network = VirtualNetwork()
        val first = session(network)
        val stream = network.Control()
        val second = session(network, stream = stream)
        first.close()
        assertEquals(1L, stream.done.count)
        assertFalse(network.closed)
        stream.request = "GET /info HTTP/1.1\r\n\r\n".toByteArray()
        second.start()
        assertTrue(stream.done.await(3, TimeUnit.SECONDS))
        assertTrue(stream.output.toString("UTF-8").startsWith("HTTP/1.1 200 OK"))
        assertFalse(network.closed)
    }

    @Test fun closedMediaCannotCreateNewEndpointsOnABorrowedNetwork() {
        val network = VirtualNetwork()
        val screen = ScreenStream(ByteArray(32), java.net.InetAddress.getLoopbackAddress(), network = network)
        screen.close()
        assertThrows(BackendFailure::class.java) { screen.listen(object : ScreenStream.Listener {}) }
        assertTrue(network.tcpPorts.isEmpty())
        assertFalse(network.closed)
    }

    @Test fun microphoneSendsThroughTheInjectedNetwork() {
        val network = VirtualNetwork()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    audioData.fill(1, offsetInBytes, offsetInBytes + sizeInBytes)
                    return sizeInBytes
                }
            }
        }
        val config = MicrophoneConfig("speechrecognition", 8000, 1, 100, 20,
            InetAddress.getByAddress(network.peer.address), network.peer.port, ByteArray(32), network = network)
        val uplink = MicrophoneUplink(config)
        try {
            assertTrue(uplink.start())
            val sent = network.sent.poll(2, TimeUnit.SECONDS)!!
            assertEquals(network.peer, sent.second)
            assertTrue(sent.first.size > config.frameBytes)
        } finally { uplink.close(); ShadowAudioRecord.clearSource() }
        assertFalse(network.closed)
        assertTrue(network.endpoints.all { it.count == 0L })
    }

    @Test fun failedControlInitializationStillClosesTheStream() {
        val network = VirtualNetwork()
        network.control.failLiveness = true
        session(network).start()
        assertTrue(network.control.done.await(3, TimeUnit.SECONDS))
        assertFalse(network.closed)
    }

    @Test fun hostUsesBackendFallbackPortAndOwnsTheTransferredNetwork() {
        val network = VirtualNetwork().apply { failListenAt = 2 }
        AirPlaySessionHost().use { host ->
            val lease = host.attach(
                InetAddress.getByAddress(network.local.address),
                AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                additionalBindAddresses = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))),
                network = network,
            )
            assertEquals(7001, host.boundPort(lease))
            assertEquals(0L, network.endpoints.first().count)
            assertFalse(network.closed)
            host.detach(lease)
            assertTrue(network.closed)
            assertTrue(network.endpoints.all { it.count == 0L })
        }
    }

    private fun session(network: VirtualNetwork, media: AirPlayMediaHandler = object : AirPlayMediaHandler {}, stream: TcpStream = network.control) = AirPlaySession(
        network = network, stream = stream,
        config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), videoInCar = true),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = media,
    )

    @Test fun controlUsesVirtualStreamAndPreservesPeerMetadata() {
        val network = VirtualNetwork()
        val session = session(network)
        assertArrayEquals(network.peer.address, session.remoteAddress!!.address)
        network.control.request = "GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray()
        session.start()
        assertTrue(network.control.done.await(3, TimeUnit.SECONDS))
        assertTrue(network.control.output.toString("UTF-8").startsWith("HTTP/1.1 200 OK"))
        assertTrue(network.control.liveness)
        assertFalse(network.closed)
    }

    @Test fun setupAndEveryMediaPortComeFromInjectedNetwork() {
        val network = VirtualNetwork()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(network, engine)
        val shared = PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
        shared.set(session.pairVerify, ByteArray(32))
        try {
            val setup = AirPlaySession::class.java.getDeclaredMethod("handleSetup", RtspMessage.Request::class.java)
                .apply { isAccessible = true }
            val body = BplistCodec.encode(mapOf("timingPort" to 1234L, "keepAliveLowPower" to true))
            val reply = setup.invoke(session, RtspMessage.Request("SETUP", "/", "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response
            val fields = BplistCodec.decode(reply.body) as Map<*, *>
            assertTrue(network.tcpPorts.contains((fields["eventPort"] as Number).toInt()))
            assertTrue(network.udpPorts.contains((fields["timingPort"] as Number).toInt()))
            val screen = engine.onScreen(session, 110, mapOf("streamConnectionID" to 42L))!!
            assertTrue(network.tcpPorts.contains(screen))
            val audio = engine.onAudio(session, 96, mapOf("streamConnectionID" to 43L, "audioFormat" to 0x800000L))!!
            assertTrue(network.udpPorts.contains(audio["dataPort"]))
            assertTrue(network.udpPorts.contains(audio["controlPort"]))
            val buffered = engine.onBufferedAudio(session, mapOf("shk" to ByteArray(32), "audioFormat" to 0x800000L))!!
            assertTrue(network.tcpPorts.contains(buffered["dataPort"]))
            val iap = engine.onDataStream(session, mapOf("clientTypeUUID" to "E9459FD0-BCAD-4C45-820F-1E72447EF2F2", "seed" to 1L))!!
            assertTrue(network.tcpPorts.contains(iap["dataPort"]))
            val settings = engine.onDataStream(session, mapOf("clientTypeUUID" to VideoInCar.SETTINGS_CHANNEL_UUID, "seed" to 2L))!!
            assertTrue(network.tcpPorts.contains(settings["dataPort"]))
            assertEquals(6, network.tcpPorts.size)
            assertEquals(4, network.udpPorts.size)
        } finally { session.close() }
        assertFalse(network.closed)
        assertTrue(network.endpoints.all { it.count == 0L })
    }

    private class VirtualNetwork : SessionNetwork {
        override val linkId = "virtual-usb"
        override val clock = MonotonicClock { System.nanoTime() / 1_000_000 }
        val peer = NetworkEndpoint(ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 2 }, 50000, linkId)
        val local = NetworkEndpoint(ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 1 }, 7000, linkId)
        val tcpPorts = mutableListOf<Int>()
        val udpPorts = mutableListOf<Int>()
        val endpoints = mutableListOf<CountDownLatch>()
        val sent = LinkedBlockingQueue<Pair<ByteArray, NetworkEndpoint>>(8)
        var closed = false
        var failListenAt = 0
        private var listenCount = 0
        val control = Control()
        inner class Control : TcpStream {
            override val localEndpoint = local
            override val remoteEndpoint = peer
            var request = ByteArray(0)
            var cursor = 0
            var liveness = false
            var failLiveness = false
            val output = ByteArrayOutputStream()
            val done = CountDownLatch(1)
            override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int {
                if (length == 0) return 0
                if (cursor == request.size) return -1
                val count = minOf(length, request.size - cursor)
                request.copyInto(bytes, offset, cursor, cursor + count); cursor += count
                return count
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) { output.write(bytes, offset, length) }
            override fun shutdownOutput() {}
            override fun configure(options: TcpOptions) {}
            override fun configureLiveness(diagnostic: (String) -> Unit) {
                if (failLiveness) throw BackendFailure(BackendFailure.Kind.INTERNAL)
                liveness = true
            }
            override fun close() { done.countDown() }
        }
        override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream = error("unexpected connect")
        private fun bound(local: NetworkEndpoint, ports: MutableList<Int>): NetworkEndpoint {
            check(local.linkId == linkId)
            return NetworkEndpoint(local.address, if (local.port == 0) 40000 + tcpPorts.size + udpPorts.size else local.port, linkId)
                .also { ports.add(it.port) }
        }
        override fun listen(local: NetworkEndpoint, backlog: Int, reuseAddress: Boolean): TcpListener {
            if (++listenCount == failListenAt) throw BackendFailure(BackendFailure.Kind.ADDRESS_CONFLICT)
            val endpoint = bound(local, tcpPorts)
            val stopped = CountDownLatch(1).also { endpoints.add(it) }
            return object : TcpListener {
                override val localEndpoint = endpoint
                override fun accept(deadlineMillis: Long): TcpStream { stopped.await(); throw BackendFailure(BackendFailure.Kind.CANCELLED) }
                override fun close() { stopped.countDown() }
            }
        }
        override fun bindUdp(local: NetworkEndpoint): UdpEndpoint {
            val endpoint = bound(local, udpPorts)
            val stopped = CountDownLatch(1).also { endpoints.add(it) }
            return object : UdpEndpoint {
                override val localEndpoint = endpoint
                override fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long) {
                    check(destination.linkId == linkId)
                    sent.offer(bytes.copyOf() to destination)
                }
                override fun receive(bytes: ByteArray, deadlineMillis: Long): ReceivedDatagram { stopped.await(); throw BackendFailure(BackendFailure.Kind.CANCELLED) }
                override fun close() { stopped.countDown() }
            }
        }
        override fun close() { closed = true; endpoints.forEach { it.countDown() } }
    }
}

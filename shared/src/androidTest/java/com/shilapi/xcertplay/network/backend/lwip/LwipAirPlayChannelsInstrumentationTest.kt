package com.shilapi.xcertplay.network.backend.lwip

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.PairVerify
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.network.backend.BackendRuntimeCountersProvider
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.network.backend.NetworkEndpoint
import com.shilapi.xcertplay.network.backend.TcpOptions
import com.shilapi.xcertplay.network.backend.TcpStream
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the AirPlay channel negotiation against the real JNI SessionNetwork adapter. */
@RunWith(AndroidJUnit4::class)
class LwipAirPlayChannelsInstrumentationTest {
    @Test fun allAirPlayChannelsBindOnOneNativeWiredSessionAndReleaseOnClose() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val port = object : EthernetFramePort {
            override val hostMac = MAC.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
            override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val network = LwipSessionNetwork.native(port, MAC, IP, LINK)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = AirPlaySession(
            network = network,
            stream = ControlStream(),
            config = AirPlayConfig(
                "test", "test", "", "1", AirPlayDisplayConfig(800, 480),
                videoInCar = true, mainBufferedAudio = true,
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = engine,
        )
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32))

        try {
            val setup = AirPlaySession::class.java.getDeclaredMethod(
                "handleSetup", RtspMessage.Request::class.java,
            ).apply { isAccessible = true }
            val baseReply = setup.invoke(
                session,
                RtspMessage.Request(
                    "SETUP", "/", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(mapOf("timingPort" to 1234L, "keepAliveLowPower" to true)),
                ),
            ) as RtspMessage.Response
            val baseFields = BplistCodec.decode(baseReply.body) as Map<*, *>
            val tcpPorts = mutableListOf<Int>()
            val udpPorts = mutableListOf<Int>()
            tcpPorts += (baseFields["eventPort"] as Number).toInt()
            udpPorts += (baseFields["timingPort"] as Number).toInt()
            udpPorts += (baseFields["keepAlivePort"] as Number).toInt()

            val mediaReply = setup.invoke(
                session,
                RtspMessage.Request(
                    "SETUP", "/", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(mapOf("streams" to listOf(
                        mapOf("type" to 110L, "streamConnectionID" to 42L),
                        mapOf("type" to 100L, "streamConnectionID" to 43L, "audioFormat" to 0x800000L),
                        mapOf("type" to 103L, "shk" to ByteArray(32), "audioFormat" to 0x800000L),
                        mapOf("type" to 130L, "clientTypeUUID" to IAP_UUID, "seed" to 1L),
                        mapOf("type" to 130L, "clientTypeUUID" to VideoInCar.SETTINGS_CHANNEL_UUID, "seed" to 2L),
                    ))),
                ),
            ) as RtspMessage.Response
            val responseStreams = ((BplistCodec.decode(mediaReply.body) as Map<*, *>) ["streams"] as List<*>)
                .map { it as Map<*, *> }
            assertEquals("all requested media channels must be negotiated", 5, responseStreams.size)
            for (stream in responseStreams) {
                when ((stream["type"] as Number).toInt()) {
                    110, 103, 130 -> tcpPorts += (stream["dataPort"] as Number).toInt()
                    100 -> {
                        udpPorts += (stream["dataPort"] as Number).toInt()
                        udpPorts += (stream["controlPort"] as Number).toInt()
                    }
                    else -> throw AssertionError("unexpected negotiated channel type: ${stream["type"]}")
                }
            }

            assertEquals("all negotiated TCP channels must be bound", 5, tcpPorts.size)
            assertEquals("all negotiated UDP channels must be bound", 4, udpPorts.size)
            assertTrue(tcpPorts.all { it in 1..65535 })
            assertTrue(udpPorts.all { it in 1..65535 })
            assertEquals(tcpPorts.size, tcpPorts.toSet().size)
            assertEquals(udpPorts.size, udpPorts.toSet().size)
        } finally {
            session.close()
        }

        val counters = network as BackendRuntimeCountersProvider
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while ((counters.backendRuntimeCounters().liveResourceScopes ?: 0L) != 0L &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
        assertEquals(0L, counters.backendRuntimeCounters().liveResourceScopes)
        network.close()
    }

    private class ControlStream : TcpStream {
        override val localEndpoint = NetworkEndpoint(ByteArray(16), 7000, LINK)
        override val remoteEndpoint = NetworkEndpoint(PEER.copyOf(), 50000, LINK)
        override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = Unit
        override fun shutdownOutput() = Unit
        override fun configureLiveness(diagnostic: (String) -> Unit) = Unit
        override fun configure(options: TcpOptions) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val LINK = "lwip-airplay-integration"
        const val IAP_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        val MAC = byteArrayOf(2, 0, 0, 0, 0, 1)
        val IP = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val PEER = IP.copyOf().also { it[15] = 2 }
    }
}

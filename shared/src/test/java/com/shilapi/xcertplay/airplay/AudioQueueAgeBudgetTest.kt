package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.backend.*
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioQueueAgeBudgetTest {
    @Test fun queueAgeIsFourNegotiatedPacketDurationsWithTwentyMillisecondFallback() {
        assertEquals(93L, AudioQueueAgeBudget.maxAgeMillis(
            AudioStreamCodec.fromFormatBits(0x400000L, 100), 1024,
        ))
        assertEquals(80L, AudioQueueAgeBudget.maxAgeMillis(
            AudioStreamCodec.fromFormatBits(0x800000L, 100), 960,
        ))
        assertEquals(80L, AudioQueueAgeBudget.maxAgeMillis(
            AudioStreamCodec.fromFormatBits(0x10000000L, 100), 960,
        ))
        assertEquals(80L, AudioQueueAgeBudget.maxAgeMillis(
            AudioStreamCodec.fromFormatBits(0x800000L, 100), null,
        ))
        assertEquals(80L, AudioQueueAgeBudget.maxAgeMillis(
            AudioStreamCodec.fromFormatBits(0x800000L, 100), 0,
        ))
    }

    @Test fun listenAppliesQueueAgeOnlyToTheRtpDataEndpoint() {
        val network = RecordingNetwork()
        val stream = AudioStream(
            key = ByteArray(32),
            network = network,
            receiveMaxAgeMillis = 93,
        )
        try {
            stream.listen(object : AudioStream.Listener {})
            assertEquals(listOf(93L, null), network.udpEndpoints.map { it.receiveMaxAgeMillis })
        } finally {
            stream.close()
        }
    }

    private class RecordingNetwork : SessionNetwork {
        override val linkId = "audio-queue-age-test"
        override val clock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val udpEndpoints = mutableListOf<RecordingUdpEndpoint>()

        override fun listen(local: NetworkEndpoint, backlog: Int, reuseAddress: Boolean): TcpListener =
            error("unexpected TCP listen")
        override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream =
            error("unexpected TCP connect")
        override fun bindUdp(local: NetworkEndpoint): UdpEndpoint = RecordingUdpEndpoint(
            NetworkEndpoint(local.address, 50_000 + udpEndpoints.size, linkId),
        ).also(udpEndpoints::add)

        override fun close() = udpEndpoints.forEach(RecordingUdpEndpoint::close)
    }

    private class RecordingUdpEndpoint(override val localEndpoint: NetworkEndpoint) :
        UdpEndpoint, UdpQueueAgeControl {
        private val closed = CountDownLatch(1)
        var receiveMaxAgeMillis: Long? = null
            private set

        override fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long) = Unit
        override fun receive(bytes: ByteArray, deadlineMillis: Long): ReceivedDatagram {
            closed.await()
            throw BackendFailure(BackendFailure.Kind.CANCELLED)
        }
        override fun setReceiveMaxAge(maxAgeMillis: Long) { receiveMaxAgeMillis = maxAgeMillis }
        override fun expiredDatagrams(): Long = 0
        override fun close() { closed.countDown() }
    }
}

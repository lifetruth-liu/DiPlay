package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.backend.*
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayEventCloseRaceTest {
    private fun session() = AirPlaySession(
        Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )
    @Test fun eventAcceptedAfterCloseCannotEnableResetOrPublish() {
        val session = session()
        val server = Listener()
        val accepted = Stream()
        session.close()
        assertFalse(session.publishEventSocket(server, accepted))
        assertTrue(accepted.events.isEmpty())
    }
    @Test fun publishedEventSocketClosesWithSession() {
        val session = session()
        val server = Listener()
        val accepted = Stream()
        AirPlaySession::class.java.getDeclaredField("eventServer").apply { isAccessible = true }.set(session, server)
        assertTrue(session.publishEventSocket(server, accepted))
        session.close()
        assertEquals(listOf("reset=true", "close"), accepted.events)
    }
    @Test fun tunnelAcceptedAfterCloseCannotEnableResetOrPublish() {
        val accepted = Stream()
        val tunnel = IapTunnel(ByteArray(32))
        tunnel.close()
        assertFalse(tunnel.publishSocket(accepted))
        assertTrue(accepted.events.isEmpty())
    }
    private class Listener : TcpListener {
        override val localEndpoint = NetworkEndpoint(byteArrayOf(127, 0, 0, 1), 7000, "test")
        override fun accept(deadlineMillis: Long): TcpStream = error("not used")
        override fun close() {}
    }
    private class Stream : TcpStream {
        val events = mutableListOf<String>()
        override val localEndpoint = NetworkEndpoint(byteArrayOf(127, 0, 0, 1), 7000, "test")
        override val remoteEndpoint = NetworkEndpoint(byteArrayOf(127, 0, 0, 1), 7001, "test")
        override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int = error("not used")
        override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = error("not used")
        override fun shutdownOutput() = error("not used")
        override fun configureLiveness(diagnostic: (String) -> Unit) = error("not used")
        override fun configure(options: TcpOptions) { events.add("reset=${options.resetOnClose}") }
        override fun close() { events.add("close") }
    }
}

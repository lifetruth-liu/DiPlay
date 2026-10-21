package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlaySessionHostTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    private fun attach(host: AirPlaySessionHost, failed: (Throwable) -> Unit = {}) = host.attach(
        address, config, identity, PairingStore(), null, object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {}, onFailure = failed,
    )

    @Test fun hostServesControlWithoutAnAndroidServiceAndOldLeaseCannotStopReplacement() {
        AirPlaySessionHost().use { host ->
            val old = attach(host)
            val current = attach(host)
            host.detach(old)
            assertNull(host.boundPort(old))
            val port = checkNotNull(host.boundPort(current))
            Socket(address, port).use { peer ->
                peer.soTimeout = 3000
                peer.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 200 OK", peer.getInputStream().bufferedReader().readLine())
            }
            host.detach(current)
            assertFalse(host.isAttached())
            host.detach(current)
            ServerSocket(port, 50, address).use { assertTrue(it.isBound) }
        }
    }

    @Test fun delayedFailureOfOldListenerCannotCloseOrNotifyReplacement() {
        val failures = LinkedBlockingQueue<() -> Unit>()
        val bound = LinkedBlockingQueue<ServerSocket>()
        var oldNotified = false
        var newNotified = false
        AirPlaySessionHost(
            bindListeners = { addresses, port ->
                listOf(AirPlayPortSelector.bind(addresses.single(), port).also { bound.add(it) })
            }, dispatchFailure = { failures.add(it) },
        ).use { host ->
            val old = attach(host) { oldNotified = true }
            bound.take().close()
            val delayed = checkNotNull(failures.poll(3, TimeUnit.SECONDS))
            val current = attach(host) { newNotified = true }
            delayed()
            assertFalse(oldNotified)
            assertFalse(newNotified)
            assertNull(host.boundPort(old))
            assertNotNull(host.boundPort(current))
            assertTrue(host.isAttached())
        }
    }

    @Test fun closedHostRejectsQueuedLateAttachment() {
        val host = AirPlaySessionHost()
        attach(host)
        host.close()
        assertThrows(IllegalStateException::class.java) { attach(host) }
        assertFalse(host.isAttached())
    }

    @Test fun cancelledAttachmentCannotReplaceAnExistingListener() {
        AirPlaySessionHost().use { host ->
            val current = attach(host)
            assertThrows(IllegalStateException::class.java) {
                host.attach(address, config, identity, PairingStore(), null, object : AirPlaySessionListener {},
                    object : AirPlayMediaHandler {}, canAttach = { false })
            }
            assertNotNull(host.boundPort(current))
        }
    }
}

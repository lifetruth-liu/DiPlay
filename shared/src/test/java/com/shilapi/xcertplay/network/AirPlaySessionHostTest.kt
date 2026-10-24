package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import java.net.ServerSocket
import com.shilapi.xcertplay.network.backend.*
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

    @Test fun wiredAttachmentReportsAcceptedTcpWithAnIdentityUniqueToItsLease() {
        val accepted = LinkedBlockingQueue<AirPlayTcpAccepted>()
        val listener = object : AirPlaySessionListener {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) { accepted.add(event) }
        }
        AirPlaySessionHost().use { host ->
            fun wired() = host.attach(address, config, identity, PairingStore(), null, listener,
                object : AirPlayMediaHandler {})
            val first = wired()
            val firstEvent = Socket(address, checkNotNull(host.boundPort(first))).use {
                checkNotNull(accepted.poll(3, TimeUnit.SECONDS)) { "Wired attachment did not report TCP acceptance" }
            }
            // A wired acceptance identity must not acquire wireless detach/filter semantics.
            assertFalse(host.detachWireless(firstEvent.listener))
            assertTrue(host.isAttached())
            val second = wired()
            val secondEvent = Socket(address, checkNotNull(host.boundPort(second))).use {
                checkNotNull(accepted.poll(3, TimeUnit.SECONDS)) { "Replacement wired attachment did not report TCP acceptance" }
            }
            assertNotSame(firstEvent.listener, secondEvent.listener)
            assertTrue(firstEvent.internal)
            assertTrue(secondEvent.acceptedAtNanos >= firstEvent.acceptedAtNanos)
        }
    }

    @Test fun delayedFailureOfOldListenerCannotCloseOrNotifyReplacement() {
        val failures = LinkedBlockingQueue<() -> Unit>()
        val bound = LinkedBlockingQueue<TcpListener>()
        var oldNotified = false
        var newNotified = false
        AirPlaySessionHost(
            bindListeners = { network, addresses, port ->
                AirPlayPortSelector.bindAll(network, addresses, port).also { bound.add(it.single()) }
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

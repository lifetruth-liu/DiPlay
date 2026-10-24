package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Exercises Android's scoped IPv6 socket behavior on a real active interface. */
class SystemSessionNetworkIpv6ScopeTest {
    @Test fun linkLocalTcpAndUdpUseOsScopeWithoutChangingSessionIdentity() {
        val enumerated = NetworkInterface.getNetworkInterfaces()
        assumeNotNull(enumerated)
        val scopedAddress = Collections.list(enumerated)
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet6Address>()
            .firstOrNull { it.isLinkLocalAddress && it.scopeId > 0 }
        assumeNotNull(scopedAddress)
        val linkLocal = scopedAddress!!
        val network = SystemSessionNetwork("android-ipv6-scope", linkLocal.scopeId)
        network.use {
            val local = NetworkEndpoint(linkLocal.address, 0, network.linkId)
            val deadline = { network.clock.nowMillis() + 5_000 }

            network.listen(local).use { listener ->
                network.connect(listener.localEndpoint, deadline()).use { client ->
                    listener.accept(deadline()).use { peer ->
                        val payload = byteArrayOf(0x16, 0x28, 0x64)
                        client.write(payload, deadlineMillis = deadline())
                        val received = ByteArray(payload.size)
                        assertEquals(payload.size, peer.read(received, deadlineMillis = deadline()))
                        assertArrayEquals(payload, received)
                        assertArrayEquals(linkLocal.address, client.localEndpoint.address)
                        assertEquals(network.linkId, peer.localEndpoint.linkId)
                    }
                }
            }

            network.bindUdp(local).use { receiver ->
                network.bindUdp(local).use { sender ->
                    val payload = byteArrayOf(0x64, 0x28)
                    sender.send(payload, receiver.localEndpoint, deadline())
                    val received = ByteArray(payload.size)
                    val datagram = receiver.receive(received, deadline())
                    assertArrayEquals(payload, received)
                    assertArrayEquals(linkLocal.address, datagram.source.address)
                    assertEquals(network.linkId, datagram.source.linkId)
                }
            }
        }
    }
}

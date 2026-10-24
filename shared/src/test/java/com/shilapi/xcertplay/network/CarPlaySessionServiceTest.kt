package com.shilapi.xcertplay.network

import android.net.VpnService
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.backend.SessionNetwork
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 33], manifest = Config.NONE, shadows = [VpnScopeBuilderShadow::class])
class CarPlaySessionServiceTest {
    @Before fun forbidVpn() {
        VpnScopeBoundary.reset()
        VpnScopeBoundary.establishFailure = SecurityException("VPN forbidden")
    }

    @Test fun ordinaryServiceAttachesWithoutVpnAndDestructionClosesListeners() {
        assertFalse(VpnService::class.java.isAssignableFrom(CarPlaySessionService::class.java))
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val address = InetAddress.getByName("127.0.0.1")
        assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWireless(address,
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
        val port = checkNotNull(service.boundPort())
        assertTrue(service.isAttached())
        assertTrue(VpnScopeBoundary.calls.isEmpty())
        controller.destroy()
        assertFalse(service.isAttached())
        ServerSocket(port, 50, address).use { assertTrue(it.isBound) }
    }

    @Test fun userSpaceWiredAttachmentUsesItsNetworkAndStaleOwnerCannotDetachReplacement() {
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val address = InetAddress.getByName("127.0.0.1")
        val firstNetwork = CloseTrackingNetwork("wired-first")
        val firstOwner = CarPlaySessionService.WiredOwner()
        assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWiredUserspace(
            firstOwner, firstNetwork, address,
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        ))
        assertNotNull(service.boundPort(firstOwner))
        assertFalse(firstNetwork.closed)

        val secondNetwork = CloseTrackingNetwork("wired-second")
        val secondOwner = CarPlaySessionService.WiredOwner()
        assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWiredUserspace(
            secondOwner, secondNetwork, address,
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        ))
        val secondPort = checkNotNull(service.boundPort(secondOwner))
        assertTrue(firstNetwork.closed)

        service.detachWired(firstOwner)
        assertEquals(secondPort, service.boundPort(secondOwner))
        assertFalse(secondNetwork.closed)
        assertTrue(VpnScopeBoundary.calls.isEmpty())

        service.detachWired(secondOwner)
        assertTrue(secondNetwork.closed)
        controller.destroy()
    }

    @Test fun wirelessUserspaceWirelessTransitionPreservesPairingsAndClosesEachOwnerWithoutVpn() {
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        val address = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val pairings = PairingStore().also { it.save("paired-controller", byteArrayOf(2, 4, 6, 8)) }

        try {
            assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWireless(
                address, config, identity, pairings, null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            ))
            val firstWirelessPort = checkNotNull(service.boundPort())

            val userspaceNetwork = CloseTrackingNetwork("wired-transition")
            val owner = CarPlaySessionService.WiredOwner()
            assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWiredUserspace(
                owner, userspaceNetwork, address, config, identity, pairings, null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            ))
            val userspacePort = checkNotNull(service.boundPort(owner))
            assertNotEquals(firstWirelessPort, userspacePort)
            assertFalse(userspaceNetwork.closed)
            assertArrayEquals(byteArrayOf(2, 4, 6, 8), pairings.get("paired-controller"))
            ServerSocket(firstWirelessPort, 50, address).use { assertTrue(it.isBound) }

            assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWireless(
                address, config, identity, pairings, null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            ))
            val replacementWirelessPort = checkNotNull(service.boundPort())
            assertTrue(userspaceNetwork.closed)

            service.detachWired(owner)
            assertEquals(replacementWirelessPort, service.boundPort())
            assertArrayEquals(byteArrayOf(2, 4, 6, 8), pairings.get("paired-controller"))
            assertTrue(VpnScopeBoundary.calls.isEmpty())

            service.detach()
            assertFalse(service.isAttached())
            ServerSocket(replacementWirelessPort, 50, address).use { assertTrue(it.isBound) }
        } finally {
            controller.destroy()
        }
    }

    private class CloseTrackingNetwork(label: String) : SessionNetwork {
        private val delegate = SystemSessionNetwork(label)
        override val linkId get() = delegate.linkId
        override val clock get() = delegate.clock
        var closed = false
            private set
        override fun listen(local: com.shilapi.xcertplay.network.backend.NetworkEndpoint,
            backlog: Int, reuseAddress: Boolean) = delegate.listen(local, backlog, reuseAddress)
        override fun connect(remote: com.shilapi.xcertplay.network.backend.NetworkEndpoint,
            deadlineMillis: Long) = delegate.connect(remote, deadlineMillis)
        override fun bindUdp(local: com.shilapi.xcertplay.network.backend.NetworkEndpoint) = delegate.bindUdp(local)
        override fun close() { closed = true; delegate.close() }
    }
}

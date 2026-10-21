package com.shilapi.xcertplay.network

import android.net.VpnService
import com.shilapi.xcertplay.airplay.*
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
}

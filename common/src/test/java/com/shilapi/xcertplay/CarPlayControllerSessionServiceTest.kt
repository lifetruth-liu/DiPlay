package com.shilapi.xcertplay

import android.content.*
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.CarPlaySessionService
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.backend.WiredBackend
import com.shilapi.xcertplay.network.backend.WiredBackendFailure
import com.shilapi.xcertplay.network.backend.WiredBackendPreference
import com.shilapi.xcertplay.network.backend.WiredBackendSelector
import com.shilapi.xcertplay.network.backend.WiredBackendSuccessCache
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerSessionServiceTest {
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    private inner class BindingContext(val service: CarPlaySessionService, val delayed: Boolean = false) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val bound = CountDownLatch(1)
        val requested = mutableListOf<String>()
        var pending: ServiceConnection? = null
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            requested.add(checkNotNull(intent.component).className)
            assertNotEquals(CarPlayVpnService::class.java.name, intent.component!!.className)
            pending = connection
            bound.countDown()
            if (!delayed) connection.onServiceConnected(intent.component, service.onBind(intent))
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound++ }
    }

    private fun controller(context: Context, statuses: MutableList<CarPlayStatus> = mutableListOf(),
        airPlayConfig: AirPlayConfig = config) =
        CarPlayController(context, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", carPlayUsbInterfaceNumber = 3)),
            airPlayConfig, identity, PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add)

    private fun awaitService(controller: CarPlayController): CarPlaySessionService? =
        ReflectionHelpers.callInstanceMethod(controller, "awaitSessionService")

    @Test fun cachedVpnFallbackAdvertisesActualUserspacePortWhenPreferredPortIsBusy() {
        val lifecycle = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val address = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(0, 50, address).use { occupied ->
                val airPlayConfig = config.copy(port = occupied.localPort)
                val controller = controller(BindingContext(lifecycle.get()), airPlayConfig = airPlayConfig)
                try {
                    val selector = WiredBackendSelector(WiredBackendPreference.AUTO, false,
                        WiredBackendSuccessCache(WiredBackendSelector.STACK_VERSION, "arm64-v8a"),
                        WiredBackendSelector.STACK_VERSION, "arm64-v8a")
                    assertEquals(WiredBackend.VPN, selector.begin())
                    assertEquals(WiredBackend.USERSPACE,
                        selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
                    val service = checkNotNull(awaitService(controller))
                    val owner = ReflectionHelpers.getField<CarPlaySessionService.WiredOwner>(controller, "wiredOwner")
                    assertEquals(CarPlaySessionService.AttachResult.Started, service.attachWiredUserspace(
                        owner, SystemSessionNetwork("fallback-port-test"), address, airPlayConfig,
                        identity, PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}))
                    val actualPort = checkNotNull(service.boundPort(owner))
                    assertNotEquals(occupied.localPort, actualPort)

                    val advertisedPort = ReflectionHelpers.callInstanceMethod<Int>(controller, "wiredAirPlayPort",
                        ReflectionHelpers.ClassParameter.from(WiredBackendSelector::class.java, selector))
                    assertEquals(actualPort, advertisedPort)
                } finally {
                    controller.close()
                    assertTrue(controller.awaitClosed(2000))
                }
            }
        } finally { lifecycle.destroy() }
    }

    @Test fun wirelessBindsOnlyOrdinaryServiceAndReportsItsLiveAttachment() {
        val lifecycle = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val context = BindingContext(lifecycle.get())
        val controller = controller(context)
        try {
            assertSame(lifecycle.get(), awaitService(controller))
            val service = lifecycle.get()
            service.attachWireless(java.net.InetAddress.getByName("127.0.0.1"), config, identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
            assertTrue(controller.hasActiveAirPlayAttachment())
            assertEquals(listOf(CarPlaySessionService::class.java.name), context.requested)
        } finally { controller.close(); assertTrue(controller.awaitClosed(2000)); lifecycle.destroy() }
        assertEquals(1, context.unbound)
    }

    @Test fun closingCancelsPendingBindAndLateBinderCannotReviveController() {
        val lifecycle = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val context = BindingContext(lifecycle.get(), delayed = true)
        val controller = controller(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit(Callable { awaitService(controller) })
            assertTrue(context.bound.await(2, TimeUnit.SECONDS))
            controller.close()
            assertNull(waiting.get(2, TimeUnit.SECONDS))
            context.pending!!.onServiceConnected(ComponentName(context, CarPlaySessionService::class.java),
                lifecycle.get().onBind(Intent()))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "sessionService"))
            assertFalse(controller.hasActiveAirPlayAttachment())
            assertTrue(controller.awaitClosed(2000))
            assertEquals(1, context.unbound)
        } finally { controller.close(); executor.shutdownNow(); lifecycle.destroy() }
    }

    @Test fun unexpectedServiceDisconnectReportsFailureThenClosesController() {
        val lifecycle = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val context = BindingContext(lifecycle.get())
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = controller(context, statuses)
        try {
            awaitService(controller)
            lifecycle.destroy()
            context.pending!!.onServiceDisconnected(ComponentName(context, CarPlaySessionService::class.java))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
            assertTrue(controller.awaitClosed(2000))
            assertEquals(1, context.unbound)
        } finally { controller.close(); controller.awaitClosed(2000) }
    }
}

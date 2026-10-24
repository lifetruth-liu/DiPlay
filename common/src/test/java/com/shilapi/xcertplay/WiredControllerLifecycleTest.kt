package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WiredControllerLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val usb get() = context.getSystemService(UsbManager::class.java)
    private val controllers = mutableListOf<CarPlayController>()
    private val statuses = mutableListOf<CarPlayStatus>()
    private val logs = mutableListOf<String>()

    @After fun closeAll() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2000)) }
        IphoneUsbLifecycle.forProcess(context, usb).close()
    }

    @Test fun unplugCancelsOwnersAndClosesWithoutWaitingForIoTimeout() {
        val phone = attach()
        val controller = preparing(phone)
        phase(controller, "CONTROL")
        shadowOf(usb).removeUsbDevice(phone)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, phone)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.isClosed())
        assertTrue(controller.awaitClosed(2000))
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().count { it.message.contains("disconnected") })
        for (name in listOf("vpnOwner", "wiredOwner")) {
            val owner = ReflectionHelpers.getField<Any>(controller, name)
            assertTrue(ReflectionHelpers.getField<AtomicBoolean>(owner, "cancelled").get())
        }
    }

    @Test fun missingDetachBroadcastIsDetectedByProcessPoll() {
        val phone = attach()
        val controller = preparing(phone)
        phase(controller, "CONTROL")
        shadowOf(usb).removeUsbDevice(phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(controller.isClosed())
    }

    @Test fun expectedReenumerationDetachKeepsControllerAndAdoptsFreshToken() {
        val old = attach()
        val controller = preparing(old)
        val first = enumeration(controller)
        phase(controller, "REENUMERATION")
        shadowOf(usb).removeUsbDevice(old)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, old)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isClosed())
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
        val replacement = attach()
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(first.token, enumeration(controller).token)
        assertFalse(controller.isClosed())
    }

    @Test fun delayedOldDetachAndUnrelatedDetachDoNotCloseReplacement() {
        val old = attach()
        val controller = preparing(old)
        phase(controller, "REENUMERATION")
        shadowOf(usb).removeUsbDevice(old)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, old)
        shadowOf(Looper.getMainLooper()).idle()
        val replacement = attach()
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        val current = enumeration(controller)
        phase(controller, "CONTROL")
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, old)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, device("other", 0x0781))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isClosed())
        assertEquals(current, enumeration(controller))
    }

    @Test fun lateOpenedPipeIsClosedAndCannotStartNcm() {
        val phone = attach()
        var completion: ((IphoneUsbHost.Iap2SessionResult) -> Unit)? = null
        val host = mock(IphoneUsbHost::class.java, org.mockito.stubbing.Answer<Any?> { call ->
            when (call.method.name) {
                "discover" -> usb.deviceList.values.toList()
                "requestPermission" -> IphoneUsbHost.PermissionRequest.Requested(phone)
                "openIap2UsbSessionAsync" -> { completion = call.getArgument(2); null }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
            }
        })
        val controller = preparing(phone, host)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "openDataPaths", ClassParameter.from(UsbDevice::class.java, phone))
        assertNotNull(completion)
        shadowOf(usb).removeUsbDevice(phone)
        IphoneUsbLifecycle.forProcess(context, usb).reconcile()
        shadowOf(Looper.getMainLooper()).idle()
        val pipe = mock(Iap2UsbSession::class.java)
        checkNotNull(completion)(IphoneUsbHost.Iap2SessionResult.Connected(pipe))
        verify(pipe, times(1)).close()
        assertNull(ReflectionHelpers.getField<Any?>(controller, "locallyOwnedNcm"))
        assertTrue(controller.isClosed())
    }

    @Test fun queuedPermissionWorkCannotRunAgainstDetachedEnumeration() {
        val phone = attach()
        val controller = controller()
        phase(controller, "IPHONE")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, phone))
        shadowOf(usb).removeUsbDevice(phone)
        IphoneUsbLifecycle.forProcess(context, usb).reconcile()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logs.none { it.contains("USB permission already granted") || it.contains("USB permission requested") })
    }

    @Test fun pendingOldAdbGrantDoesNotBlockSameNodeReenumeration() {
        val old = attach()
        val controller = controller()
        controller.start()
        phase(controller, "REENUMERATION")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, old))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, logs.count { it.contains("USB permission route=ADB") })
        shadowOf(usb).removeUsbDevice(old)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, old)
        shadowOf(Looper.getMainLooper()).idle()
        val replacement = attach()
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, logs.count { it.contains("USB permission route=ADB") })
        assertFalse(controller.isClosed())
    }

    @Test fun inFlightTimeoutCannotLeaveFailureMarkerAfterCancellation() {
        val controller = preparing(attach())
        ReflectionHelpers.setField(controller, "wiredDirectAttempt", true)
        val attempt = ReflectionHelpers.getField<Any>(controller, "wiredAttempt")
        val enumeration = enumeration(controller)
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "failWiredStartup", ClassParameter.from(attempt.javaClass, attempt))
        assertNull(memoryField(enumeration, "directFailureAtNanos"))
    }

    @Test fun activeSessionWinsAgainstInFlightStartupTimeout() {
        val controller = preparing(attach())
        ReflectionHelpers.setField(controller, "wiredDirectAttempt", true)
        ReflectionHelpers.setField(controller, "wiredSessionActive", true)
        val attempt = ReflectionHelpers.getField<Any>(controller, "wiredAttempt")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "failWiredStartup", ClassParameter.from(attempt.javaClass, attempt))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(memoryField(enumeration(controller), "directFailureAtNanos"))
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun latePermissionDenialCannotFailAnAlreadyOpenedGeneration() {
        val phone = attach()
        val controller = preparing(phone)
        phase(controller, "CONTROL")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onIphonePermission",
            ClassParameter.from(IphoneUsbHost.PermissionResult::class.java, IphoneUsbHost.PermissionResult.Denied(phone)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
        assertFalse(controller.isClosed())
    }

    @Test fun compatibilityTransitionKeepsItsTenSecondBound() {
        val phone = attach()
        val controller = preparing(phone)
        phase(controller, "REENUMERATION")
        ReflectionHelpers.setField(controller, "reenumerationAttempts", 1)
        val attempt = ReflectionHelpers.getField<Any>(controller, "wiredAttempt")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration", ClassParameter.from(attempt.javaClass, attempt))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9999))
        assertTrue(logs.none { it.contains("transition unanswered") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(logs.any { it.contains("transition unanswered after 10000ms") })
    }

    @Test fun releaseBuildIgnoresExperimentalRecoveryPolicy() {
        context.applicationInfo.flags = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val controller = controller(experiment = WiredRecoveryExperiment.COMPATIBILITY)
        val strategy = ReflectionHelpers.callInstanceMethod<Enum<*>>(controller, "getWiredRecoveryStrategy")
        assertEquals("DEFAULT", strategy.name)
    }

    @Test fun lateAcceptedTcpFromOldAttachmentCannotAdvanceReplacementAttempt() {
        val old = attach()
        val controller = preparing(old)
        val oldListener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(controller, "wiredSessionListener")
        phase(controller, "REENUMERATION")
        shadowOf(usb).removeUsbDevice(old)
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, old)
        shadowOf(Looper.getMainLooper()).idle()
        val replacement = attach()
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        statuses.clear()
        oldListener.onTcpAccepted(AirPlayTcpAccepted(AirPlayListenerIdentity(0), false, System.nanoTime()))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun networkStageNeverRegressesAfterTcpEvidence() {
        val controller = preparing(attach())
        val attempt = ReflectionHelpers.getField<Any>(controller, "wiredAttempt")
        reportNetworkStage(controller, attempt, false, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayStatus.EstablishingWiredSession, statuses.last())
        reportNetworkStage(controller, attempt, false, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayStatus.EstablishingWiredSession, statuses.last())
    }

    @Test fun cancelledAttemptCannotPublishNetworkStages() {
        val controller = preparing(attach())
        val attempt = ReflectionHelpers.getField<Any>(controller, "wiredAttempt")
        reportNetworkStage(controller, attempt, false, false)
        controller.close()
        shadowOf(Looper.getMainLooper()).idle()
        statuses.clear()
        reportNetworkStage(controller, attempt, true, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    private fun reportNetworkStage(controller: CarPlayController, attempt: Any, link: Boolean, tcp: Boolean) {
        CarPlayController::class.java.declaredMethods.single { it.name == "reportWiredNetworkStage" }
            .apply { isAccessible = true }.invoke(controller, attempt, link, tcp)
    }

    @Test fun releaseBuildIgnoresInjectedStartQuietWindow() {
        context.applicationInfo.flags = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val controller = controller(quiet = com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy.HALF_SECOND)
        assertEquals(com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy.DEFAULT,
            ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy>(controller, "getWiredStartQuiet"))
    }

    @Test fun debugBuildAcceptsInjectedStartQuietWindow() {
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val controller = controller(quiet = com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy.HALF_SECOND)
        assertEquals(com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy.HALF_SECOND,
            ReflectionHelpers.callInstanceMethod<com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy>(controller, "getWiredStartQuiet"))
    }

    @Test fun debugBuildCanInjectExperimentalRecoveryPolicy() {
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val controller = controller(experiment = WiredRecoveryExperiment.COMPATIBILITY)
        val strategy = ReflectionHelpers.callInstanceMethod<Enum<*>>(controller, "getWiredRecoveryStrategy")
        assertEquals("COMPATIBILITY", strategy.name)
    }

    @Test fun failedResourceCloseDoesNotReleaseTheRecoveryBarrier() {
        val controller = preparing(attach())
        val ncm = mock(NcmUsbBridge::class.java)
        doThrow(IllegalStateException("close incomplete")).`when`(ncm).close()
        ReflectionHelpers.setField(controller, "locallyOwnedNcm", ncm)
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        assertNull(releasedAt(enumeration(controller)))
    }

    @Test fun incompleteWorkerTerminationDoesNotReleaseTheRecoveryBarrier() {
        val controller = preparing(attach())
        ReflectionHelpers.setField(controller, "executor", QueuedExecutor(terminates = false))
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        assertNull(releasedAt(enumeration(controller)))
    }

    @Test fun completedTeardownReleasesTheRecoveryBarrier() {
        val controller = preparing(attach())
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        assertNotNull(releasedAt(enumeration(controller)))
    }

    @Test fun releaseDefaultReusesReleasedActiveConfigurationWithoutRequestingTransition() {
        context.applicationInfo.flags = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val phone = attach()
        val first = preparing(phone)
        val enumeration = enumeration(first)
        val memoryClass = Class.forName("com.shilapi.xcertplay.orchestration.WiredSessionMemory")
        val memory = memoryClass.getDeclaredField("INSTANCE").get(null)
        memoryClass.getDeclaredMethod("active", IphoneUsbEnumeration::class.java, Long::class.javaPrimitiveType)
            .invoke(memory, enumeration, System.nanoTime())
        ReflectionHelpers.setField(first, "wiredSessionActive", true)
        first.close()
        assertTrue(first.awaitClosed(2000))
        assertNotNull(releasedAt(enumeration))
        ReflectionHelpers.setField(phone, "mConfigurations", arrayOf(completeConfiguration(6)))
        shadowOf(usb).addOrUpdateUsbDevice(phone, true)
        logs.clear()
        val next = preparing(phone)
        assertTrue(logs.any { it.contains("action=reuse-descriptors reason=previous-active-reuse") })
        assertTrue(logs.none { it.contains("USB transition requested") })
        assertEquals(0, ReflectionHelpers.getField<Int>(next, "reenumerationAttempts"))
    }

    @Test fun failedDirectAttemptGetsOneReleasedRecoveryAndThenStops() {
        val phone = attach()
        ReflectionHelpers.setField(phone, "mConfigurations", arrayOf(completeConfiguration(6)))
        shadowOf(usb).addOrUpdateUsbDevice(phone, true)
        val first = preparing(phone)
        val enumeration = enumeration(first)
        val memoryClass = Class.forName("com.shilapi.xcertplay.orchestration.WiredSessionMemory")
        val memory = memoryClass.getDeclaredField("INSTANCE").get(null)
        memoryClass.getDeclaredMethod("directFailed", IphoneUsbEnumeration::class.java, Long::class.javaPrimitiveType)
            .invoke(memory, enumeration, System.nanoTime())
        fail(first)
        first.close()
        assertTrue(first.awaitClosed(2000))
        assertNotNull(releasedAt(enumeration))

        var requests = 0
        val host = mock(IphoneUsbHost::class.java, org.mockito.stubbing.Answer<Any?> { call ->
            when (call.method.name) {
                "discover" -> usb.deviceList.values.toList()
                "requestPermission" -> IphoneUsbHost.PermissionRequest.AlreadyGranted(phone)
                "requestCarPlayReenumerationAsync" -> {
                    requests++
                    call.getArgument<(IphoneUsbHost.TransitionResult) -> Unit>(2)(IphoneUsbHost.TransitionResult.ReenumerationRequested)
                    null
                }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
            }
        })
        val second = preparing(phone, host)
        assertEquals(1, requests)
        assertTrue(ReflectionHelpers.getField<Boolean>(second, "wiredRecoveryAttempt"))
        fail(second)
        second.close()
        assertTrue(second.awaitClosed(2000))

        val third = controller(host)
        third.start()
        phase(third, "IPHONE")
        ReflectionHelpers.callInstanceMethod<Unit>(third, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertTrue(third.isClosed())
        assertTrue(statuses.filterIsInstance<CarPlayStatus.Failed>().any { !it.retryAllowed })
    }

    @Test fun gracefulCandidateClosesSessionsBeforeControlAndNetwork() {
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val phone = attach()
        val controller = controller(closeExperiment = WiredCloseExperiment.GRACEFUL)
        controller.start()
        phase(controller, "IPHONE")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, phone))
        shadowOf(Looper.getMainLooper()).idle()
        phase(controller, "CONTROL")
        val service = mock(com.shilapi.xcertplay.network.CarPlaySessionService::class.java)
        val retained = mock(com.shilapi.xcertplay.network.AirPlaySessionHost.RetainedNetwork::class.java) { call ->
            if (call.method.name == "awaitLocalEgress") com.shilapi.xcertplay.network.backend.LocalEgressResult.LOCAL_EGRESS_DRAINED
            else org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
        }
        val control = mock(com.shilapi.xcertplay.iap2.session.Iap2Session::class.java)
        val owner = ReflectionHelpers.getField<com.shilapi.xcertplay.network.CarPlaySessionService.WiredOwner>(controller, "wiredOwner")
        `when`(service.quiesceWired(owner)).thenReturn(retained)
        ReflectionHelpers.setField(controller, "sessionService", service)
        ReflectionHelpers.setField(controller, "csm", control)
        ReflectionHelpers.setField(controller, "wiredSessionActive", true)
        ReflectionHelpers.setField(controller, "effectiveWiredBackend", com.shilapi.xcertplay.network.backend.WiredBackend.USERSPACE)
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        val order = inOrder(service, retained, control)
        order.verify(service).quiesceWired(owner)
        order.verify(retained).closeSessionsGracefully()
        order.verify(control).close()
        order.verify(retained).close()
        assertTrue(logs.any { it.contains("result=LOCAL_EGRESS_DRAINED") })
    }

    @Test fun aFailedSessionDoesNotUseGracefulCandidate() {
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val phone = attach()
        val controller = controller(closeExperiment = WiredCloseExperiment.GRACEFUL)
        controller.start()
        phase(controller, "IPHONE")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, phone))
        shadowOf(Looper.getMainLooper()).idle()
        val service = mock(com.shilapi.xcertplay.network.CarPlaySessionService::class.java)
        ReflectionHelpers.setField(controller, "sessionService", service)
        ReflectionHelpers.setField(controller, "wiredSessionActive", true)
        ReflectionHelpers.setField(controller, "effectiveWiredBackend", com.shilapi.xcertplay.network.backend.WiredBackend.USERSPACE)
        fail(controller)
        controller.close()
        assertTrue(controller.awaitClosed(2000))
        assertTrue(mockingDetails(service).invocations.none { it.method.name == "quiesceWired" })
    }

    private fun fail(controller: CarPlayController) {
        CarPlayController::class.java.getDeclaredMethod("failAttempt", Throwable::class.java,
            Int::class.javaObjectType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(controller, IphoneUsbException.TimedOut("injected startup failure"), null, true)
    }

    private fun releasedAt(enumeration: IphoneUsbEnumeration): Long? = memoryField(enumeration, "releasedAtNanos")

    private fun memoryField(enumeration: IphoneUsbEnumeration, field: String): Long? {
        val memoryClass = Class.forName("com.shilapi.xcertplay.orchestration.WiredSessionMemory")
        val memory = memoryClass.getDeclaredField("INSTANCE").get(null)
        val snapshot = memoryClass.getDeclaredMethod("snapshot", IphoneUsbEnumeration::class.java).invoke(memory, enumeration)
        return snapshot?.let { ReflectionHelpers.getField<Long?>(it, field) }
    }

    private fun completeConfiguration(id: Int): UsbConfiguration = ReflectionHelpers.newInstance(UsbConfiguration::class.java).apply {
        ReflectionHelpers.setField(this, "mId", id)
        fun usbInterface(id: Int, type: Int, subtype: Int, protocol: Int): android.hardware.usb.UsbInterface =
            ReflectionHelpers.newInstance(android.hardware.usb.UsbInterface::class.java).apply {
                ReflectionHelpers.setField(this, "mId", id)
                ReflectionHelpers.setField(this, "mClass", type)
                ReflectionHelpers.setField(this, "mSubclass", subtype)
                ReflectionHelpers.setField(this, "mProtocol", protocol)
                ReflectionHelpers.setField(this, "mEndpoints", emptyArray<android.hardware.usb.UsbEndpoint>())
            }
        ReflectionHelpers.setField(this, "mInterfaces", arrayOf(
            usbInterface(0, 0xff, 0xfe, 2), usbInterface(1, 2, 0x0d, 0), usbInterface(2, 0xff, 0xfd, 1)))
    }

    private fun preparing(phone: UsbDevice, host: IphoneUsbHost? = null): CarPlayController {
        val controller = controller(host)
        controller.start()
        phase(controller, "IPHONE")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "requestIphonePermission", ClassParameter.from(UsbDevice::class.java, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ReflectionHelpers.getField<Any?>(controller, "wiredAttempt"))
        return controller
    }

    private fun controller(host: IphoneUsbHost? = null, experiment: WiredRecoveryExperiment = WiredRecoveryExperiment.DEFAULT,
        quiet: com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy = com.shilapi.xcertplay.orchestration.WiredStartQuietPolicy.DEFAULT, closeExperiment: WiredCloseExperiment = WiredCloseExperiment.LEGACY): CarPlayController {
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, wiredRecoveryExperiment = experiment, wiredStartQuiet = quiet, wiredCloseExperiment = closeExperiment,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener { override fun onDebugLog(message: String) { logs += message } },
            object : AirPlayMediaHandler {}, statuses::add)
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(controller, "executor").shutdownNow()
        ReflectionHelpers.setField(controller, "executor", QueuedExecutor())
        if (host != null) ReflectionHelpers.setField(controller, "iphoneHost$" + "delegate", lazyOf(host))
        controllers += controller
        return controller
    }

    private fun enumeration(controller: CarPlayController): IphoneUsbEnumeration =
        ReflectionHelpers.getField(controller, "wiredEnumeration")

    private fun phase(controller: CarPlayController, name: String) {
        ReflectionHelpers.setField(controller, "phase",
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController$" + "Phase")
                .enumConstants.single { (it as Enum<*>).name == name })
    }

    private fun broadcast(action: String, phone: UsbDevice) {
        context.sendBroadcast(Intent(action).putExtra(UsbManager.EXTRA_DEVICE, phone))
    }

    private fun attach(): UsbDevice = device("same-node").also { shadowOf(usb).addOrUpdateUsbDevice(it, false) }
    private fun device(name: String, vendor: Int = 0x05ac): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", name)
        ReflectionHelpers.setField(this, "mVendorId", vendor)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        ReflectionHelpers.setField(this, "mConfigurations", emptyArray<UsbConfiguration>())
    }

    private class QueuedExecutor(private val terminates: Boolean = true) : AbstractExecutorService() {
        private var stopped = false
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { if (!stopped) queue.addLast(command) }
        override fun shutdown() { stopped = true; queue.clear() }
        override fun shutdownNow(): MutableList<Runnable> = queue.toMutableList().also { shutdown() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && terminates
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped && terminates
    }
}

package com.shilapi.xcertplay.transport

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.HashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28, 33], manifest = Config.NONE,
    application = UsbLifecycleCountingApplication::class,
    shadows = [FailingLifecycleUsbManagerShadow::class],
)
class IphoneUsbLifecycleInitializationTest {
    @Test fun failedInitialInventoryUnregistersReceiverAndAllowsFactoryRetry() {
        verifyFailedInitialization(unregisterFailure = null)
    }

    @Test fun cleanupFailureIsSuppressedWithoutReplacingOriginalInventoryFailure() {
        verifyFailedInitialization(unregisterFailure = IllegalArgumentException("unregister failed"))
    }

    private fun verifyFailedInitialization(unregisterFailure: RuntimeException?) {
        val context = RuntimeEnvironment.getApplication() as UsbLifecycleCountingApplication
        val usb = context.getSystemService(UsbManager::class.java)
        val original = IllegalStateException("inventory unavailable")
        context.unregisterFailure = unregisterFailure
        FailingLifecycleUsbManagerShadow.failure = original
        val registrationsBefore = context.registrations
        val unregistrationsBefore = context.unregistrations
        try {
            val thrown = try {
                IphoneUsbLifecycle.forProcess(context, usb)
                fail("initial inventory must fail")
                throw AssertionError()
            } catch (failure: IllegalStateException) { failure }
            assertSame(original, thrown)
            assertEquals(registrationsBefore + 1, context.registrations)
            assertEquals(unregistrationsBefore + 1, context.unregistrations)
            assertEquals(listOfNotNull(unregisterFailure), thrown.suppressed.toList())
        } finally {
            FailingLifecycleUsbManagerShadow.failure = null
            context.unregisterFailure = null
        }
        val retry = IphoneUsbLifecycle.forProcess(context, usb)
        try {
            assertEquals(registrationsBefore + 2, context.registrations)
            assertTrue(retry.reconcile().isEmpty())
        } finally { retry.close() }
        assertEquals(unregistrationsBefore + 2, context.unregistrations)
    }
}

class UsbLifecycleCountingApplication : Application() {
    var registrations = 0
    var unregistrations = 0
    var unregisterFailure: RuntimeException? = null

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        super.registerReceiver(receiver, filter).also { registrations++ }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? =
        super.registerReceiver(receiver, filter, flags).also { registrations++ }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        super.unregisterReceiver(receiver)
        unregistrations++
        unregisterFailure?.let { throw it }
    }
}

@Implements(UsbManager::class)
class FailingLifecycleUsbManagerShadow : ShadowUsbManager() {
    companion object { var failure: RuntimeException? = null }

    @Implementation
    override fun getDeviceList(): HashMap<String, UsbDevice> {
        failure?.let { throw it }
        return super.getDeviceList()
    }
}

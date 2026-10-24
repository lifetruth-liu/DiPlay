package com.shilapi.xcertplay.transport

import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class IphoneUsbLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val usb get() = context.getSystemService(UsbManager::class.java)
    private val lifecycle get() = IphoneUsbLifecycle.forProcess(context, usb)

    @After fun closeTracker() { lifecycle.close() }

    @Test fun applicationContextSharesTrackerAcrossControllerWrappers() {
        val phone = attach()
        val firstOwner = IphoneUsbLifecycle.forProcess(ContextWrapper(context), usb)
        val first = firstOwner.current(phone)
        firstOwner.addInvalidationListener {}.close()
        val nextOwner = IphoneUsbLifecycle.forProcess(ContextWrapper(context), usb)
        assertSame(firstOwner, nextOwner)
        assertEquals(first, nextOwner.current(phone))
    }

    @Test fun absentDeviceCannotGetEnumerationAndOldEnumerationCannotRemainCurrent() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        shadowOf(usb).removeUsbDevice(phone)
        assertNull(lifecycle.current(phone))
        assertFalse(lifecycle.isCurrent(first))
    }

    @Test fun pollingInvalidatesDuringControllerGapWithoutBroadcast() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        val events = mutableListOf<IphoneUsbEnumeration>()
        lifecycle.addInvalidationListener { events += it }
        shadowOf(usb).removeUsbDevice(phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(first), events)
        val replacement = attach()
        assertNotEquals(first.token, lifecycle.current(replacement)!!.token)
    }

    @Test fun frequentReadsDoNotPostponeMissingBroadcastPoll() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        val events = mutableListOf<IphoneUsbEnumeration>()
        lifecycle.addInvalidationListener { events += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        lifecycle.current(phone)
        shadowOf(usb).removeUsbDevice(phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(first), events)
    }

    @Test fun delayedDetachAloneKeepsCurrentEnumeration() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        assertNull(lifecycle.detached(phone))
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lifecycle.isCurrent(first))
    }

    @Test fun duplicateAttachWithoutDepartureKeepsCurrentEnumeration() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        repeat(2) {
            context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, phone))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lifecycle.isCurrent(first))
    }

    @Test fun queuedDetachAttachAfterNodeReuseRotatesAndNotifiesOnce() {
        val oldDevice = attach()
        val first = lifecycle.current(oldDevice)!!
        val events = mutableListOf<IphoneUsbEnumeration>()
        lifecycle.addInvalidationListener { events += it }
        shadowOf(usb).removeUsbDevice(oldDevice)
        val replacement = attach()
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, oldDevice))
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, replacement))
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, replacement))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(lifecycle.isCurrent(first))
        assertNotEquals(first.token, lifecycle.current(replacement)!!.token)
        assertEquals(listOf(first), events)
    }

    @Test fun unrelatedVendorBroadcastsDoNotCreateDepartureHint() {
        val phone = attach()
        val first = lifecycle.current(phone)!!
        val unrelated = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", phone.deviceName)
            ReflectionHelpers.setField(this, "mVendorId", 0x1234)
            ReflectionHelpers.setField(this, "mProductId", phone.productId)
        }
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, unrelated))
        context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, phone))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lifecycle.isCurrent(first))
        assertNull(lifecycle.current(unrelated))
    }

    @Test fun closeReleasesListenersAndFactoryReplacesClosedTracker() {
        val phone = attach()
        val oldTracker = lifecycle
        val old = oldTracker.current(phone)!!
        oldTracker.addInvalidationListener { fail("closed tracker notified") }
        oldTracker.close()
        assertTrue(oldTracker.reconcile().isEmpty())
        val replacement = lifecycle
        assertNotSame(oldTracker, replacement)
        assertNotEquals(old.token, replacement.current(phone)!!.token)
    }

    private fun attach(): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
        ReflectionHelpers.setField(this, "mVendorId", 0x05ac)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        shadowOf(usb).addOrUpdateUsbDevice(this, false)
    }
}

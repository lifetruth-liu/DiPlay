package com.shilapi.xcertplay.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.IdentityHashMap

/** Process-owned USB enumeration identity, independent of any controller or host instance. */
class IphoneUsbLifecycle private constructor(
    private val appContext: Context,
    private val usbManager: UsbManager,
) : Closeable {
    private val registry = IphoneUsbEnumerationRegistry()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val schedulingLock = Any()
    @Volatile private var closed = false
    private var pollScheduled = false
    private val poll = object : Runnable {
        override fun run() {
            synchronized(schedulingLock) { pollScheduled = false }
            reconcile()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (closed || device.vendorId != APPLE_VENDOR_ID) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> detached(device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val current = registry.attached(device.identity(), ::listedDevices)
                    schedulePolling(current)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        try {
            reconcile()
        } catch (failure: Throwable) {
            synchronized(schedulingLock) {
                closed = true
                mainHandler.removeCallbacks(poll)
                pollScheduled = false
                registry.clearListeners()
            }
            try {
                appContext.unregisterReceiver(receiver)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun current(device: UsbDevice): IphoneUsbEnumeration? = reconcile().firstOrNull {
        it.deviceName == device.deviceName && it.vendorId == device.vendorId && it.productId == device.productId
    }

    fun isCurrent(enumeration: IphoneUsbEnumeration): Boolean = enumeration in reconcile()

    fun reconcile(): List<IphoneUsbEnumeration> {
        if (closed) return emptyList()
        val current = registry.reconcile(::listedDevices)
        schedulePolling(current)
        return if (closed) emptyList() else current
    }

    private fun listedDevices(): List<IphoneUsbIdentity> = if (closed) emptyList() else
        usbManager.deviceList.values.filter { it.vendorId == APPLE_VENDOR_ID }.map { it.identity() }

    private fun UsbDevice.identity() = IphoneUsbIdentity(deviceName, vendorId, productId)

    private fun schedulePolling(current: List<IphoneUsbEnumeration>) {
        synchronized(schedulingLock) {
            if (!closed && current.isNotEmpty() && !pollScheduled) {
                pollScheduled = mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
            }
            if (current.isEmpty()) {
                mainHandler.removeCallbacks(poll)
                pollScheduled = false
            }
        }
    }

    /** A delayed detach cannot invalidate a same-name device still listed by Android. */
    fun detached(device: UsbDevice): IphoneUsbEnumeration? {
        if (closed || device.vendorId != APPLE_VENDOR_ID) return null
        val previous = registry.detached(device.identity(), ::listedDevices)
        reconcile()
        return previous
    }

    fun addInvalidationListener(listener: (IphoneUsbEnumeration) -> Unit): Closeable =
        synchronized(schedulingLock) {
            if (closed) Closeable {} else registry.addInvalidationListener(listener)
        }

    /** Only isolated owners/tests should close this; controller close only closes its subscription. */
    override fun close() {
        synchronized(schedulingLock) {
            if (closed) return
            closed = true
            mainHandler.removeCallbacks(poll)
            registry.clearListeners()
        }
        appContext.unregisterReceiver(receiver)
        registry.reconcile(emptyList())
    }

    companion object {
        private const val APPLE_VENDOR_ID = 0x05ac
        private const val POLL_INTERVAL_MS = 1_000L
        private val processes = IdentityHashMap<Context, IphoneUsbLifecycle>()

        fun forProcess(context: Context, usbManager: UsbManager): IphoneUsbLifecycle {
            val application = requireNotNull(context.applicationContext) { "Application context required" }
            return synchronized(processes) {
                processes[application]?.takeUnless { it.closed }
                    ?: IphoneUsbLifecycle(application, usbManager).also { processes[application] = it }
            }
        }
    }
}

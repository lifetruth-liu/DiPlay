package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbLifecycle
import java.util.WeakHashMap

/** Keeps enumeration evidence current even between controllers. Installed at process startup. */
object WiredUsbLifecycle {
    private val observed = WeakHashMap<IphoneUsbLifecycle, Boolean>()

    fun install(context: Context): IphoneUsbLifecycle? {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        val lifecycle = IphoneUsbLifecycle.forProcess(context, manager)
        synchronized(observed) {
            if (observed.put(lifecycle, true) == null) {
                lifecycle.addInvalidationListener(WiredSessionMemory::invalidate)
            }
        }
        return lifecycle
    }
}

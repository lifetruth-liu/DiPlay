package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.util.UUID

data class IphoneUsbEnumeration(
    val token: Long,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
)

internal data class IphoneUsbIdentity(val deviceName: String, val vendorId: Int, val productId: Int)

/** Pure enumeration state. Notifications never run under [lock]. No serial numbers are read. */
internal class IphoneUsbEnumerationRegistry {
    private val lock = Any()
    private val entries = linkedMapOf<String, IphoneUsbEnumeration>()
    private val pendingDepartures = mutableMapOf<String, IphoneUsbIdentity>()
    private val listeners = linkedSetOf<(IphoneUsbEnumeration) -> Unit>()
    private var nextToken = UUID.randomUUID().mostSignificantBits

    fun reconcile(devices: List<IphoneUsbIdentity>): List<IphoneUsbEnumeration> = reconcile { devices }

    fun attached(device: IphoneUsbIdentity, devices: () -> List<IphoneUsbIdentity>): List<IphoneUsbEnumeration> =
        update(devices, attached = device)

    fun reconcile(devices: () -> List<IphoneUsbIdentity>): List<IphoneUsbEnumeration> = update(devices)

    private fun update(
        devices: () -> List<IphoneUsbIdentity>,
        attached: IphoneUsbIdentity? = null,
        detached: IphoneUsbIdentity? = null,
    ): List<IphoneUsbEnumeration> {
        val invalidated = mutableListOf<IphoneUsbEnumeration>()
        val snapshot: List<IphoneUsbEnumeration>
        val observers: List<(IphoneUsbEnumeration) -> Unit>
        synchronized(lock) {
            val present = devices().associateBy { it.deviceName }
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val (name, old) = iterator.next()
                val now = present[name]
                if (now == null || now.vendorId != old.vendorId || now.productId != old.productId) {
                    iterator.remove()
                    pendingDepartures.remove(name)
                    invalidated += old
                }
            }
            // Android may deliver both broadcasts after the node has already been reused. Preserve
            // a present node on DETACHED, but use the ordered pair as evidence of re-enumeration.
            // A late old detach followed by a spurious attach is indistinguishable from a real pair
            // using Android's public node/VID/PID identity; the pair conservatively rotates its token.
            if (attached != null && pendingDepartures.remove(attached.deviceName) == attached &&
                present[attached.deviceName] == attached) {
                entries.remove(attached.deviceName)?.let { invalidated += it }
            }
            if (detached != null && present[detached.deviceName] == detached &&
                entries.containsKey(detached.deviceName)) {
                pendingDepartures[detached.deviceName] = detached
            }
            present.values.forEach { device ->
                entries.getOrPut(device.deviceName) {
                    IphoneUsbEnumeration(nextToken++, device.deviceName, device.vendorId, device.productId)
                }
            }
            snapshot = entries.values.toList()
            observers = listeners.toList()
        }
        invalidated.forEach { old -> observers.forEach { listener -> runCatching { listener(old) } } }
        return snapshot
    }

    fun detached(device: IphoneUsbIdentity, present: List<IphoneUsbIdentity>): IphoneUsbEnumeration? {
        return detached(device) { present }
    }

    fun detached(device: IphoneUsbIdentity, present: () -> List<IphoneUsbIdentity>): IphoneUsbEnumeration? {
        val old = synchronized(lock) {
            entries[device.deviceName]?.takeIf { it.vendorId == device.vendorId && it.productId == device.productId }
        }
        val current = update(present, detached = device)
        return old?.takeIf { previous -> current.none { it == previous } }
    }

    fun addInvalidationListener(listener: (IphoneUsbEnumeration) -> Unit): Closeable {
        synchronized(lock) { listeners += listener }
        return Closeable { synchronized(lock) { listeners -= listener } }
    }

    fun clearListeners() = synchronized(lock) { listeners.clear() }
}

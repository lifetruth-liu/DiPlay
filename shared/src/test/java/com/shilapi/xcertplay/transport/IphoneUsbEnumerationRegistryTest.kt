package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class IphoneUsbEnumerationRegistryTest {
    private val phone = IphoneUsbIdentity("/dev/bus/usb/001/002", 0x05ac, 0x1234)

    @Test fun duplicateAttachAndControllerGapPreserveEnumeration() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        assertEquals(first, registry.reconcile(listOf(phone)).single())
        assertEquals(first, registry.reconcile(listOf(phone)).single())
    }

    @Test fun observedDetachThenSameNameAttachGetsNewToken() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        registry.reconcile(emptyList())
        assertNotEquals(first.token, registry.reconcile(listOf(phone)).single().token)
    }

    @Test fun delayedDetachWithAuthoritativeDevicePresentPreservesToken() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        assertNull(registry.detached(phone, listOf(phone)))
        assertEquals(first, registry.reconcile(listOf(phone)).single())
    }

    @Test fun queuedDetachAttachPairRotatesEvenWhenInventoryAlreadyContainsReplacement() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        val events = mutableListOf<IphoneUsbEnumeration>()
        registry.addInvalidationListener { events += it }
        assertNull(registry.detached(phone, listOf(phone)))
        assertEquals(first, registry.reconcile(listOf(phone)).single())
        val replacement = registry.attached(phone) { listOf(phone) }.single()
        assertNotEquals(first.token, replacement.token)
        assertEquals(listOf(first), events)
        assertEquals(replacement, registry.attached(phone) { listOf(phone) }.single())
        assertEquals(listOf(first), events)
    }

    @Test fun observedAbsenceClearsQueuedDepartureHint() {
        val registry = IphoneUsbEnumerationRegistry()
        registry.reconcile(listOf(phone))
        registry.detached(phone, listOf(phone))
        registry.reconcile(emptyList())
        val replacement = registry.reconcile(listOf(phone)).single()
        assertEquals(replacement, registry.attached(phone) { listOf(phone) }.single())
    }

    @Test fun missingBroadcastInvalidatesAndNotifiesOnce() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        val invalidated = mutableListOf<IphoneUsbEnumeration>()
        registry.addInvalidationListener { invalidated += it }
        assertEquals(first, registry.detached(phone, emptyList()))
        registry.reconcile(emptyList())
        assertEquals(listOf(first), invalidated)
    }

    @Test fun replacementProductInvalidatesOldEnumeration() {
        val registry = IphoneUsbEnumerationRegistry()
        val first = registry.reconcile(listOf(phone)).single()
        val events = mutableListOf<IphoneUsbEnumeration>()
        registry.addInvalidationListener { events += it }
        val replacement = registry.reconcile(listOf(phone.copy(productId = 7))).single()
        assertNotEquals(first.token, replacement.token)
        assertEquals(listOf(first), events)
    }

    @Test fun independentProcessRegistriesHaveDifferentIdentity() {
        assertNotEquals(
            IphoneUsbEnumerationRegistry().reconcile(listOf(phone)).single().token,
            IphoneUsbEnumerationRegistry().reconcile(listOf(phone)).single().token,
        )
    }

    @Test fun listenerFailuresAreIsolatedAndClosedListenersAreReleased() {
        val registry = IphoneUsbEnumerationRegistry()
        val events = mutableListOf<IphoneUsbEnumeration>()
        registry.addInvalidationListener { error("broken listener") }
        val subscription = registry.addInvalidationListener { events += it }
        val first = registry.reconcile(listOf(phone)).single()
        registry.reconcile(emptyList())
        subscription.close()
        registry.reconcile(listOf(phone))
        registry.reconcile(emptyList())
        assertEquals(listOf(first), events)
    }

    @Test fun listenerCanReadRegistryFromAnotherThreadWithoutHoldingRegistryLock() {
        val registry = IphoneUsbEnumerationRegistry()
        registry.reconcile(listOf(phone))
        var worker: Thread? = null
        registry.addInvalidationListener {
            worker = Thread { registry.reconcile(emptyList()) }.also { it.start(); it.join(2_000) }
        }
        registry.reconcile(emptyList())
        assertFalse("notification held registry lock", worker!!.isAlive)
    }
}

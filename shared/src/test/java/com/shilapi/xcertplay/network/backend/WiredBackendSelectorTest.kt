package com.shilapi.xcertplay.network.backend

import org.junit.Assert.*
import org.junit.Test

class WiredBackendSelectorTest {
    @Test fun freshInstallFallsBackWithoutValidationRecord() {
        val selector = WiredBackendSelector(WiredBackendPreference.AUTO)
        assertNull(selector.currentBackend())
        assertEquals(WiredBackend.VPN, selector.begin())
        assertEquals(WiredBackend.USERSPACE,
            selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        assertEquals(WiredBackend.USERSPACE, selector.currentBackend())
        assertEquals(listOf(WiredBackend.VPN, WiredBackend.USERSPACE), selector.attemptedBackends())
    }

    @Test fun explicitPreferenceIsHonoredAndAutoStartsWithVpn() {
        for (preference in WiredBackendPreference.entries) {
            val expected = if (preference == WiredBackendPreference.USERSPACE) WiredBackend.USERSPACE else WiredBackend.VPN
            val selector = WiredBackendSelector(preference)
            assertEquals(expected, selector.begin())
            assertNull(selector.begin())
            assertEquals(listOf(expected), selector.attemptedBackends())
        }
    }

    @Test fun everyFailureExceptVpnUnavailableStopsWithoutSwitchingBackend() {
        for (failure in WiredBackendFailure.entries.filter { it != WiredBackendFailure.VPN_UNAVAILABLE }) {
            val selector = WiredBackendSelector(WiredBackendPreference.AUTO)
            selector.begin()
            assertNull("failure=$failure", selector.afterFailure(WiredBackend.VPN, failure))
            assertEquals(listOf(WiredBackend.VPN), selector.attemptedBackends())
        }
    }

    @Test fun explicitBackendsNeverFallBack() {
        for (preference in listOf(WiredBackendPreference.VPN, WiredBackendPreference.USERSPACE)) {
            val selector = WiredBackendSelector(preference)
            val backend = checkNotNull(selector.begin())
            for (failure in WiredBackendFailure.entries) assertNull(selector.afterFailure(backend, failure))
            assertEquals(listOf(backend), selector.attemptedBackends())
        }
    }

    @Test fun cancelledOrUnstartedAttemptCannotFallBack() {
        val selector = WiredBackendSelector(WiredBackendPreference.AUTO)
        assertNull(selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        selector.begin()
        assertNull(selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE, cancelled = true))
        assertEquals(listOf(WiredBackend.VPN), selector.attemptedBackends())
    }

    @Test fun duplicateFailuresAndFailedLwipCannotLoopOrResetSelection() {
        val selector = WiredBackendSelector(WiredBackendPreference.AUTO)
        selector.begin()
        selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE)
        assertNull(selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        for (failure in WiredBackendFailure.entries) assertNull(selector.afterFailure(WiredBackend.USERSPACE, failure))
        assertNull(selector.begin())
        assertEquals(WiredBackend.USERSPACE, selector.currentBackend())
        assertEquals(listOf(WiredBackend.VPN, WiredBackend.USERSPACE), selector.attemptedBackends())
    }
}

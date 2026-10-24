package com.shilapi.xcertplay.network.backend

import org.junit.Assert.*
import org.junit.Test

class WiredBackendSelectorTest {
    @Test fun currentBackendFollowsCachedAutoFallbackAndRejectedRetriesDoNotResetIt() {
        val selector = WiredBackendSelector(
            WiredBackendPreference.AUTO,
            userspaceValidatedForRelease = false,
            successCache = WiredBackendSuccessCache(WiredBackendSelector.STACK_VERSION, "arm64-v8a"),
            stackVersion = WiredBackendSelector.STACK_VERSION,
            abi = "arm64-v8a",
        )
        assertNull(selector.currentBackend())
        selector.begin()
        assertEquals(WiredBackend.VPN, selector.currentBackend())
        selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE)
        assertEquals(WiredBackend.USERSPACE, selector.currentBackend())
        assertNull(selector.begin())
        assertNull(selector.afterFailure(WiredBackend.USERSPACE, WiredBackendFailure.NATIVE_UNAVAILABLE))
        assertEquals(WiredBackend.USERSPACE, selector.currentBackend())
    }

    @Test fun unavailableFallbackKeepsTheOriginalBackend() {
        val selector = WiredBackendSelector(WiredBackendPreference.AUTO, userspaceValidatedForRelease = false)
        selector.begin()
        assertNull(selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        assertEquals(WiredBackend.VPN, selector.currentBackend())
    }

    @Test fun explicitPreferenceIsHonoredAndAutoStartsWithVpn() {
        assertEquals(WiredBackend.VPN,
            WiredBackendSelector.firstAttempt(WiredBackendPreference.VPN, userspaceValidatedForRelease = true))
        assertEquals(WiredBackend.USERSPACE,
            WiredBackendSelector.firstAttempt(WiredBackendPreference.USERSPACE, userspaceValidatedForRelease = false))
        assertEquals(WiredBackend.VPN,
            WiredBackendSelector.firstAttempt(WiredBackendPreference.AUTO, userspaceValidatedForRelease = false))
        assertEquals(WiredBackend.VPN,
            WiredBackendSelector.firstAttempt(WiredBackendPreference.AUTO, userspaceValidatedForRelease = true))
    }

    @Test fun autoFallsBackOnlyForExplicitVpnUnavailabilityAndAtMostOnce() {
        val selector = WiredBackendSelector(
            WiredBackendPreference.AUTO,
            userspaceValidatedForRelease = true,
        )
        assertEquals(WiredBackend.VPN, selector.begin())
        assertEquals(WiredBackend.USERSPACE,
            selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        assertEquals(null,
            selector.afterFailure(WiredBackend.USERSPACE, WiredBackendFailure.VPN_UNAVAILABLE))
        assertEquals(2, selector.attemptedBackends().size)
    }

    @Test fun autoDoesNotLoopWhenTheCachedUserspaceAttemptCannotLoadNativeCode() {
        val selector = WiredBackendSelector(
            WiredBackendPreference.AUTO,
            userspaceValidatedForRelease = false,
            successCache = WiredBackendSuccessCache(WiredBackendSelector.STACK_VERSION, "arm64-v8a"),
            stackVersion = WiredBackendSelector.STACK_VERSION,
            abi = "arm64-v8a",
        )
        assertEquals(WiredBackend.VPN, selector.begin())
        assertEquals(WiredBackend.USERSPACE,
            selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        assertNull(selector.afterFailure(WiredBackend.USERSPACE, WiredBackendFailure.NATIVE_UNAVAILABLE))
        assertEquals(listOf(WiredBackend.VPN, WiredBackend.USERSPACE), selector.attemptedBackends())
    }

    @Test fun autoDoesNotTreatUserRefusalCancellationOrProtocolFailuresAsVpnFailure() {
        for (failure in listOf(WiredBackendFailure.USER_CANCELLED, WiredBackendFailure.CANCELLED,
            WiredBackendFailure.USB, WiredBackendFailure.MFI, WiredBackendFailure.PROTOCOL)) {
            val selector = WiredBackendSelector(WiredBackendPreference.AUTO, userspaceValidatedForRelease = true)
            assertEquals(WiredBackend.VPN, selector.begin())
            assertEquals(null, selector.afterFailure(WiredBackend.VPN, failure))
            assertEquals(listOf(WiredBackend.VPN), selector.attemptedBackends())
        }
    }

    @Test fun explicitBackendNeverSilentlyChangesAndSuccessCacheIsBoundToVersionAndAbi() {
        val explicitUserspace = WiredBackendSelector(WiredBackendPreference.USERSPACE,
            userspaceValidatedForRelease = false)
        assertEquals(WiredBackend.USERSPACE, explicitUserspace.begin())
        assertEquals(null, explicitUserspace.afterFailure(WiredBackend.USERSPACE, WiredBackendFailure.NATIVE_UNAVAILABLE))

        val explicitVpn = WiredBackendSelector(WiredBackendPreference.VPN,
            userspaceValidatedForRelease = true)
        assertEquals(WiredBackend.VPN, explicitVpn.begin())
        assertEquals(null, explicitVpn.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))

        val cache = WiredBackendSuccessCache("lwip-2.2.0-diplay.1", "arm64-v8a")
        assertTrue(cache.matches("lwip-2.2.0-diplay.1", "arm64-v8a"))
        assertFalse(cache.matches("lwip-2.2.0-diplay.2", "arm64-v8a"))
        assertFalse(cache.matches("lwip-2.2.0-diplay.1", "armeabi-v7a"))

        val matchingCache = WiredBackendSelector(WiredBackendPreference.AUTO, false,
            successCache = cache, stackVersion = "lwip-2.2.0-diplay.1", abi = "arm64-v8a")
        assertEquals(WiredBackend.VPN, matchingCache.begin())
        assertEquals(WiredBackend.USERSPACE,
            matchingCache.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
        val staleCache = WiredBackendSelector(WiredBackendPreference.AUTO, false,
            successCache = cache, stackVersion = "lwip-2.2.0-diplay.2", abi = "arm64-v8a")
        assertEquals(WiredBackend.VPN, staleCache.begin())
        assertNull(staleCache.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
    }
}

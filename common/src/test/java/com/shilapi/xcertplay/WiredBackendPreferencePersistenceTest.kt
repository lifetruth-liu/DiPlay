package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.backend.WiredBackendPreference
import com.shilapi.xcertplay.network.backend.WiredBackendSelector
import com.shilapi.xcertplay.network.backend.WiredBackend
import com.shilapi.xcertplay.network.backend.WiredBackendFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WiredBackendPreferencePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun preferenceDefaultsToAutoAndInvalidValuesAreIgnored() {
        assertEquals(WiredBackendPreference.AUTO, AirPlayPersistence.loadWiredBackendPreference(context))
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("wired_backend_preference", "UNRECOGNIZED").commit()
        assertEquals(WiredBackendPreference.AUTO, AirPlayPersistence.loadWiredBackendPreference(context))
    }

    @Test fun allPreferencesRoundTrip() {
        for (preference in WiredBackendPreference.entries) {
            AirPlayPersistence.saveWiredBackendPreference(context, preference)
            assertEquals(preference, AirPlayPersistence.loadWiredBackendPreference(context))
        }
    }

    @Test fun obsoleteValidationRecordsDoNotGateFallbackOrChangeSavedPreference() {
        for (withOldRecord in listOf(false, true)) {
            val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            if (withOldRecord) prefs.edit().putString("wired_backend_success_version", "obsolete-stack")
                .putString("wired_backend_success_abi", "obsolete-abi").commit()
            val selector = WiredBackendSelector(AirPlayPersistence.loadWiredBackendPreference(context))
            assertEquals(WiredBackend.VPN, selector.begin())
            assertEquals(WiredBackend.USERSPACE,
                selector.afterFailure(WiredBackend.VPN, WiredBackendFailure.VPN_UNAVAILABLE))
            assertEquals(WiredBackendPreference.AUTO, AirPlayPersistence.loadWiredBackendPreference(context))
        }
    }
}

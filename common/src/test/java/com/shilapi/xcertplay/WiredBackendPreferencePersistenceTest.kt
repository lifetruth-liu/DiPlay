package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.backend.WiredBackendPreference
import com.shilapi.xcertplay.network.backend.WiredBackendSuccessCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun explicitPreferenceAndVersionedSuccessCacheRoundTrip() {
        AirPlayPersistence.saveWiredBackendPreference(context, WiredBackendPreference.USERSPACE)
        AirPlayPersistence.saveWiredBackendSuccessCache(context, "lwip-test", "arm64-v8a")

        assertEquals(WiredBackendPreference.USERSPACE, AirPlayPersistence.loadWiredBackendPreference(context))
        val cache = AirPlayPersistence.loadWiredBackendSuccessCache(context)
        assertEquals(WiredBackendSuccessCache("lwip-test", "arm64-v8a"), cache)
        assertTrue(cache!!.matches("lwip-test", "arm64-v8a"))
        assertEquals(false, cache.matches("lwip-next", "arm64-v8a"))
        assertEquals(false, cache.matches("lwip-test", "x86_64"))

        AirPlayPersistence.clearWiredBackendSuccessCache(context)
        assertNull(AirPlayPersistence.loadWiredBackendSuccessCache(context))
        assertEquals(WiredBackendPreference.USERSPACE, AirPlayPersistence.loadWiredBackendPreference(context))
    }
}

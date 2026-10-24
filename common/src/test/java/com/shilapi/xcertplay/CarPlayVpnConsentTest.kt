package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.network.backend.WiredBackendPreference
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", shadows = [VpnConsentTestShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVpnConsentTest {
    private lateinit var activity: CarPlayHostActivity
    private var launches = 0

    @Before fun setUp() {
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.preparations = 0
        VpnConsentTestShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
        invoke("initializeSessionLog")
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.VPN)
        replaceLauncher { launches += 1 }
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun autoUnavailableSkipsAdbAndCarriesFailureIntoRuntimeConfig() {
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.AUTO)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        assertFalse(field("vpnReady"))
        assertFalse(field("vpnAdbConsentTried"))
        assertEquals(com.shilapi.xcertplay.network.backend.WiredBackendFailure.VPN_UNAVAILABLE,
            ReflectionHelpers.getField<Any?>(activity, "vpnPreflightFailure"))
    }

    @Test fun autoPlatformFailureDuringPrepareSkipsAdb() {
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.AUTO)
        VpnConsentTestShadow.failure = SecurityException("VPN preparation blocked")
        invoke("requestVpnConsent")
        assertFalse(field("vpnReady"))
        assertFalse(field("vpnAdbConsentTried"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
        assertEquals(com.shilapi.xcertplay.network.backend.WiredBackendFailure.VPN_UNAVAILABLE,
            ReflectionHelpers.getField<Any?>(activity, "vpnPreflightFailure"))
    }

    @Test fun autoRefusalDoesNotBecomePlatformUnavailability() {
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.AUTO)
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        assertFalse(field("vpnReady"))
        assertFalse(field("vpnAdbConsentTried"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "vpnPreflightFailure"))
    }

    @Test fun retryClearsPreviousPlatformFailureAndLateResultCannotOverrideFallback() {
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.AUTO)
        replaceLauncher { throw ActivityNotFoundException("missing") }
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertNotNull(ReflectionHelpers.getField<Any?>(activity, "vpnPreflightFailure"))
        replaceLauncher { launches++ }
        invoke("requestVpnConsent")
        assertNull(ReflectionHelpers.getField<Any?>(activity, "vpnPreflightFailure"))
        assertTrue(field("awaitingVpnConsent"))
    }

    @Test fun pendingConsentRemainsValidAcrossAControllerRebuild() {
        invoke("requestVpnConsent")
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    @Test fun explicitVpnAdbConsentCanCompleteWhilePreviousWirelessControllerStillRuns() {
        ReflectionHelpers.setField(activity, "controller", org.mockito.Mockito.mock(
            com.shilapi.xcertplay.orchestration.CarPlayController::class.java))
        VpnConsentTestShadow.consent = null
        adbResult()
        assertTrue(field("vpnReady"))
    }

    @Test fun staleAdbResultCannotAuthorizeReplacementOrFallback() {
        VpnConsentTestShadow.consent = null
        ReflectionHelpers.setField(activity, "vpnConsentGeneration", 1)
        adbResult()
        assertFalse(field("vpnReady"))
        ReflectionHelpers.setField(activity, "vpnConsentGeneration", 0)
        ReflectionHelpers.setField(activity, "vpnPreflightFailure",
            com.shilapi.xcertplay.network.backend.WiredBackendFailure.VPN_UNAVAILABLE)
        adbResult()
        assertFalse(field("vpnReady"))
    }

    @Test fun adbConsentCanCompleteAfterControllerRebuildForTheSameRequest() {
        ReflectionHelpers.setField(activity, "restartGeneration", 1)
        VpnConsentTestShadow.consent = null
        adbResult()
        assertTrue(field("vpnReady"))
    }

    @Test fun pendingConsentCannotAuthorizeAfterSwitchingToUserspace() {
        invoke("requestVpnConsent")
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.USERSPACE)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    private fun adbResult() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnAdbConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(com.shilapi.xcertplay.adb.LocalAdb.Access::class.java,
                com.shilapi.xcertplay.adb.LocalAdb.Access.READY))
    }

    @Test fun missingConsentDialogDoesNotCrashOrLeaveAuthorizationPending() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("failureClass=ActivityNotFoundException"))
        assertTrue(log().contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
    }

    @Test fun deniedConsentLaunchDoesNotCrashOrUnlockUsb() {
        replaceLauncher { throw SecurityException("OEM blocked VPN activity") }
        invoke("requestVpnConsent")
        assertUnavailable()
        assertTrue(log().contains("operation=launch"))
        assertTrue(log().contains("failureClass=SecurityException"))
    }

    @Test fun deniedPrepareDoesNotCrashOrLeaveStaleAuthorization() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        VpnConsentTestShadow.failure = SecurityException("OEM blocked VPN preparation")
        invoke("requestVpnConsent")
        assertUnavailable()
        assertEquals(0, launches)
        assertTrue(log().contains("operation=prepare"))
    }

    @Test fun grantedAuthorizationDoesNotOpenAConsentActivity() {
        VpnConsentTestShadow.consent = null
        invoke("requestVpnConsent")
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
    }

    @Test fun explicitUserspaceStartupDoesNotPrepareOrRequestVpnConsent() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", false)
        ReflectionHelpers.setField(activity, "wiredBackendPreference", WiredBackendPreference.USERSPACE)
        VpnConsentTestShadow.failure = SecurityException("must not be reached")

        invoke("requestStartupPrerequisites")

        assertEquals(0, VpnConsentTestShadow.preparations)
        assertEquals(0, launches)
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertFalse(log().contains("VPN consent unavailable"))
    }

    @Test fun pendingAuthorizationDoesNotLaunchTwice() {
        repeat(2) { invoke("requestVpnConsent") }
        assertFalse(field("vpnReady"))
        assertTrue(field("awaitingVpnConsent"))
        assertEquals(1, launches)
    }

    @Test fun failedLaunchCanBeRetried() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        replaceLauncher { launches += 1 }
        invoke("requestVpnConsent")
        assertTrue(field("awaitingVpnConsent"))
        assertFalse(field("vpnReady"))
        assertEquals(1, launches)
    }

    @Test fun lateSuccessfulResultAfterFailedLaunchDoesNotUnlockUsb() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun resultOfPendingConsentCompletesOrDeniesAuthorization() {
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    private fun replaceLauncher(launch: (Intent) -> Unit) {
        ReflectionHelpers.setField(activity, "vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = launch(input)
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
    }

    private fun assertUnavailable() {
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("VPN authorization"))
        assertTrue(message.contains("Return to DiPlay"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("adb shell"))
    }

    private fun field(name: String): Boolean = ReflectionHelpers.getField(activity, name)
    private fun log() = File(activity.filesDir, "logs/diplay.log").readText()
    private fun invoke(name: String) {
        try { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
}

@Implements(VpnService::class)
class VpnConsentTestShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null
        var preparations = 0
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            preparations++
            failure?.let { throw it }
            return consent
        }
    }
}

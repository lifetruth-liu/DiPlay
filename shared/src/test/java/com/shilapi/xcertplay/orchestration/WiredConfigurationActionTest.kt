package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbEnumeration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WiredConfigurationActionTest {
    private val phone = IphoneUsbEnumeration(1, "same-node", 0x05ac, 1)
    @Before fun reset() = WiredSessionMemory.clear()
    private fun context(strategy: WiredRecoveryStrategy = WiredRecoveryStrategy.COMPATIBILITY) =
        RecoveryContext(phone, WiredSessionMemory.snapshot(phone), strategy)
    private fun choose(found: Boolean, mode: Boolean, tried: Int = 0) =
        WiredConfigurationAction.choose(found, mode, tried, 2, context())
    private fun previousActive() {
        WiredSessionMemory.active(phone, 10)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.ACTIVE_RECONNECT, 20)
    }

    @Test fun freshOrStartOnlyCompleteConfigurationIsUsed() {
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
        WiredSessionMemory.started(phone, 1)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 2)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
    }

    @Test fun sameEnumerationVoluntaryActiveReconnectRequestsOneCompatibilityTransition() {
        previousActive()
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, true))
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 30)
        // A replacement controller still sees the spent transition.
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(true, true, context()))
    }

    @Test fun stopReasonsAndOldTokensCannotTriggerTheCompatibilityWorkaround() {
        for (reason in WiredSessionStopReason.entries.filter { it != WiredSessionStopReason.ACTIVE_RECONNECT }) {
            WiredSessionMemory.active(phone, 1)
            WiredSessionMemory.stopped(phone, reason, 2)
            assertEquals(WiredConfigurationAction.USE, choose(true, true))
        }
        previousActive()
        val newPhone = phone.copy(token = 2)
        assertEquals(WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(true, true, 0, 2, RecoveryContext(newPhone, WiredSessionMemory.snapshot(phone))))
    }

    @Test fun missingAppleEthernetKeeps634AndRejectedRequestKeeps509And455() {
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, false))
        assertEquals(WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET, choose(true, false, 1))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(true, false, context()))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(false, false, context()))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(false, false, 1))
        assertEquals(WiredConfigurationAction.REJECT, choose(false, false, 2))
    }

    @Test fun transitionBudgetSurvivesControllerRecreationAndHonorsZeroBudget() {
        previousActive()
        assertEquals(WiredConfigurationAction.REJECT,
            WiredConfigurationAction.choose(false, false, 0, 0, context()))
        assertEquals(WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(true, true, 0, 0, context()))
        repeat(2) { WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 30L + it) }
        assertEquals(WiredConfigurationAction.REJECT, choose(false, false))
    }

    @Test fun experimentsOnlyChangePreviousActiveReconnectAndCompatibilityRemainsTenSeconds() {
        assertEquals(10_000L, WiredRecoveryPolicy.decide(true, true, 0, 2, context()).reenumerationTimeoutMs)
        previousActive()
        val direct = WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryStrategy.DIRECT))
        assertEquals(WiredConfigurationAction.USE, direct.action)
        val early = WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryStrategy.EARLY_REUSE))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, early.action)
        assertEquals(2_000L, early.reenumerationTimeoutMs)
        assertEquals(10_000L, WiredRecoveryPolicy.decide(true, false, 0, 2, context(WiredRecoveryStrategy.EARLY_REUSE)).reenumerationTimeoutMs)
    }

    @Test fun aReleasedDirectFailureCanRequestOneRecoveryEvenUnderDirectStrategy() {
        WiredSessionMemory.started(phone, 1)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 2)
        WiredSessionMemory.released(phone, 2)
        WiredSessionMemory.directFailed(phone, 3)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION,
            WiredConfigurationAction.choose(true, true, 0, 2, context(WiredRecoveryStrategy.DIRECT)))
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 4)
        assertEquals(WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(true, true, 0, 2, context(WiredRecoveryStrategy.DIRECT)))
    }

    @Test fun failedRecoveryEndsTheCycleAcrossControllerGenerationsUntilActiveOrNewEnumeration() {
        WiredSessionMemory.started(phone, 1)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 2)
        WiredSessionMemory.directFailed(phone, 3)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        WiredSessionMemory.released(phone, 4)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, true))
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 5)
        WiredSessionMemory.started(phone, 6)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 7)
        WiredSessionMemory.recoveryFailed(phone, 8)
        WiredSessionMemory.released(phone, 9)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        assertEquals("recovery-budget-exhausted", WiredRecoveryPolicy.decide(true, true, 0, 2, context()).reason)
        assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
        WiredSessionMemory.started(phone, 10)
        assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
        val fresh = phone.copy(token = 2)
        assertEquals(WiredConfigurationAction.USE,
            WiredConfigurationAction.choose(true, true, 0, 2, RecoveryContext(fresh)))
        WiredSessionMemory.active(phone, 11)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
    }

    @Test fun releasedFailureUsesRemainingTransitionBudgetAndRejectsAnExhaustedBudget() {
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 1)
        WiredSessionMemory.started(phone, 2)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 3)
        WiredSessionMemory.directFailed(phone, 4)
        WiredSessionMemory.released(phone, 5)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, true))
        assertEquals(WiredConfigurationAction.REJECT,
            WiredConfigurationAction.choose(true, true, 0, 1, context()))
        WiredSessionMemory.transition(phone, WiredTransitionResult.REQUESTED, 6)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
    }

    @Test fun diagnosticNamesKeepExistingReportWording() {
        assertEquals("reuse-descriptors", WiredConfigurationAction.USE.diagnostic)
        assertEquals("request-transition", WiredConfigurationAction.REQUEST_TRANSITION.diagnostic)
        assertEquals("reject-missing-configuration", WiredConfigurationAction.REJECT.diagnostic)
    }
}

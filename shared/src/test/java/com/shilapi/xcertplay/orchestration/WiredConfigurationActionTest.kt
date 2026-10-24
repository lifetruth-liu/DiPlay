package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbEnumeration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WiredConfigurationActionTest {
    private val phone = IphoneUsbEnumeration(1, "same-node", 0x05ac, 1)
    @Before fun reset() = WiredSessionMemory.clear()
    private fun context(strategy: WiredRecoveryExperiment = WiredRecoveryExperiment.DEFAULT) =
        RecoveryContext(phone, WiredSessionMemory.snapshot(phone), strategy)
    private fun choose(found: Boolean, mode: Boolean, tried: Int = 0) =
        WiredRecoveryPolicy.decide(found, mode, tried, 2, context()).action
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

    @Test fun sameEnumerationVoluntaryActiveReconnectReusesOnlyAfterRelease() {
        previousActive()
        assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
        WiredSessionMemory.released(phone, 30)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
        assertEquals(0, WiredSessionMemory.snapshot(phone)!!.transitionsRequested)
    }

    @Test fun anyUnreleasedActiveSessionBlocksOverlappingReuse() {
        for (reason in WiredSessionStopReason.entries) {
            WiredSessionMemory.active(phone, 1)
            assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
            WiredSessionMemory.stopped(phone, reason, 2)
            assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
            WiredSessionMemory.released(phone, 3)
            assertEquals(WiredConfigurationAction.USE, choose(true, true))
        }
    }

    @Test fun stopReasonsAndOldTokensCannotTriggerTheCompatibilityWorkaround() {
        for (reason in WiredSessionStopReason.entries.filter { it != WiredSessionStopReason.ACTIVE_RECONNECT }) {
            WiredSessionMemory.active(phone, 1)
            WiredSessionMemory.stopped(phone, reason, 2)
            WiredSessionMemory.released(phone, 3)
            assertEquals(WiredConfigurationAction.USE, choose(true, true))
        }
        previousActive()
        val newPhone = phone.copy(token = 2)
        assertEquals(WiredConfigurationAction.USE,
            WiredRecoveryPolicy.decide(true, true, 0, 2, RecoveryContext(newPhone, WiredSessionMemory.snapshot(phone))).action)
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
        WiredSessionMemory.released(phone, 30)
        assertEquals(WiredConfigurationAction.REJECT,
            WiredRecoveryPolicy.decide(false, false, 0, 0, context()).action)
        assertEquals(WiredConfigurationAction.USE,
            WiredRecoveryPolicy.decide(true, true, 0, 0, context()).action)
        repeat(2) { WiredSessionMemory.transitionRequested(phone) }
        assertEquals(WiredConfigurationAction.REJECT, choose(false, false))
    }

    @Test fun compatibilityOverrideOnlyChangesReleasedPreviousActiveReconnect() {
        assertEquals(WiredConfigurationAction.USE,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.COMPATIBILITY)).action)
        previousActive()
        assertEquals(WiredConfigurationAction.REJECT,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.COMPATIBILITY)).action)
        WiredSessionMemory.released(phone, 30)
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.COMPATIBILITY)).action)
        WiredSessionMemory.transitionRequested(phone)
        assertEquals(WiredConfigurationAction.USE,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.COMPATIBILITY)).action)
    }

    @Test fun aReleasedDirectFailureCanRequestOneRecoveryEvenUnderDirectStrategy() {
        WiredSessionMemory.started(phone, 1)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 2)
        WiredSessionMemory.released(phone, 2)
        WiredSessionMemory.directFailed(phone, 3)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.DEFAULT)).action)
        WiredSessionMemory.transitionRequested(phone)
        assertEquals(WiredConfigurationAction.USE,
            WiredRecoveryPolicy.decide(true, true, 0, 2, context(WiredRecoveryExperiment.DEFAULT)).action)
    }

    @Test fun failedRecoveryEndsTheCycleAcrossControllerGenerationsUntilActiveOrNewEnumeration() {
        WiredSessionMemory.started(phone, 1)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 2)
        WiredSessionMemory.directFailed(phone, 3)
        assertFalse(WiredSessionMemory.claimRecovery(phone))
        WiredSessionMemory.released(phone, 4)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, true))
        WiredSessionMemory.transitionRequested(phone)
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
            WiredRecoveryPolicy.decide(true, true, 0, 2, RecoveryContext(fresh)).action)
        WiredSessionMemory.active(phone, 11)
        assertEquals(WiredConfigurationAction.REJECT, choose(true, true))
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.ACTIVE_RECONNECT, 12)
        WiredSessionMemory.released(phone, 13)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
    }

    @Test fun releasedFailureUsesRemainingTransitionBudgetAndRejectsAnExhaustedBudget() {
        WiredSessionMemory.transitionRequested(phone)
        WiredSessionMemory.started(phone, 2)
        WiredSessionMemory.stopped(phone, WiredSessionStopReason.STARTUP_FAILURE, 3)
        WiredSessionMemory.directFailed(phone, 4)
        WiredSessionMemory.released(phone, 5)
        assertTrue(WiredSessionMemory.claimRecovery(phone))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(true, true))
        assertEquals(WiredConfigurationAction.REJECT,
            WiredRecoveryPolicy.decide(true, true, 0, 1, context()).action)
        WiredSessionMemory.transitionRequested(phone)
        assertEquals(WiredConfigurationAction.USE, choose(true, true))
    }

    @Test fun diagnosticNamesKeepExistingReportWording() {
        assertEquals("reuse-descriptors", WiredConfigurationAction.USE.diagnostic)
        assertEquals("request-transition", WiredConfigurationAction.REQUEST_TRANSITION.diagnostic)
        assertEquals("reject-missing-configuration", WiredConfigurationAction.REJECT.diagnostic)
    }
}

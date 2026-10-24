package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class WiredConnectionStageTest {
    @Test fun startWaitsForNetworkUntilThereIsLinkOrTcpEvidence() {
        assertEquals(CarPlayStatus.WaitingForWiredNetwork, WiredConnectionStage.afterStart(false, false))
        assertEquals(CarPlayStatus.EstablishingWiredSession, WiredConnectionStage.afterStart(true, false))
        assertEquals(CarPlayStatus.EstablishingWiredSession, WiredConnectionStage.afterStart(false, true))
    }
}

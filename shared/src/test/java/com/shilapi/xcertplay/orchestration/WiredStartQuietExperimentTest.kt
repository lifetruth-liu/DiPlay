package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain
import org.junit.Assert.assertEquals
import org.junit.Test

class WiredStartQuietExperimentTest {
    @Test fun productionAlwaysKeepsTwoSeconds() {
        for (value in listOf(500L, 1_000L, 2_000L, -1L, 750L)) {
            assertEquals(2_000L, WiredStartQuietPolicy.resolve(value, debuggable = false).millis)
        }
    }

    @Test fun debugAcceptsOnlyTheThreeMeasuredCandidates() {
        for (value in listOf(500L, 1_000L, 2_000L)) {
            assertEquals(value, WiredStartQuietPolicy.resolve(value, debuggable = true).millis)
        }
        for (value in listOf(null, -1L, 0L, 750L, Long.MAX_VALUE)) {
            assertEquals(2_000L, WiredStartQuietPolicy.resolve(value, debuggable = true).millis)
        }
    }

    @Test fun everyCandidateWaitsUntilItsExactBoundary() {
        for (quiet in WiredStartQuietPolicy.entries) {
            val opened = 10_000_000_000L
            val boundary = opened + quiet.millis * 1_000_000L
            assertEquals(StartAgain.WAIT, WiredStartAgain.decide(boundary - 1, 0, opened, false, false, quiet))
            assertEquals(StartAgain.SEND, WiredStartAgain.decide(boundary, 0, opened, false, false, quiet))
            assertEquals(StartAgain.DONE, WiredStartAgain.decide(boundary, 0, opened, true, false, quiet))
            assertEquals(StartAgain.DONE, WiredStartAgain.decide(boundary, 0, opened, false, true, quiet))
            assertEquals(StartAgain.DONE, WiredStartAgain.decide(boundary, opened + 1, opened, false, false, quiet))
        }
    }
}

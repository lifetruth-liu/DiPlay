package com.shilapi.xcertplay.network.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendDiagnosticsTest {
    @Test
    fun fixedSchemaContainsOnlySanitizedAggregatesAndIgnoresLateGeneration() {
        val store = BackendDiagnosticStore()
        store.record(
            BackendDiagnosticSnapshot(
                requested = WiredBackendPreference.AUTO,
                effective = WiredBackend.USERSPACE,
                attempt = 2,
                generation = 9,
                stage = BackendDiagnosticStage.FAILED,
                failureCode = BackendDiagnosticFailure.NATIVE_UNAVAILABLE,
                fallbackReason = WiredBackendFailure.VPN_UNAVAILABLE,
                stackVersion = "lwip-2.2.1-diplay.1",
                abi = "arm64-v8a\nwired.extra=secret",
                nativeLoadResult = BackendNativeLoadResult.FAILED,
                mtu = 1500,
                runtime = BackendRuntimeCounters(
                    ncmTxSent = 14,
                    ncmTxNotReady = 3,
                    ncmTxShortWrite = 1,
                    ncmRxResync = 2,
                    ncmRxRejected = 4,
                    lwipRxDropped = 5,
                    lwipTxSent = 12,
                    lwipTxNotReady = 3,
                    rxQueueHighWaterBytes = 8192,
                    rxQueueHighWaterFrames = 8,
                    txQueueHighWaterBytes = 4096,
                    txQueueHighWaterFrames = 4,
                    commandHighWaterCount = 6,
                    commandHighWaterBytes = 2048,
                    directBufferCurrentBytes = 1024,
                    directBufferPeakBytes = 4096,
                    liveResourceScopes = 2,
                ),
            ),
        )

        assertFalse(store.update(8) { it.copy(stage = BackendDiagnosticStage.SESSION_ACTIVE) })
        val report = store.report()
        val keys = report.lineSequence().filter { it.isNotBlank() }.map { it.substringBefore('=') }.toList()

        assertEquals(
            listOf(
                "wired.backend.requested", "wired.backend.effective", "wired.backend.attempt",
                "wired.backend.generation", "wired.backend.stage", "wired.backend.failureCode",
                "wired.backend.fallbackReason", "wired.stack.version", "wired.stack.abi",
                "wired.stack.nativeLoadResult", "wired.link.mtu", "wired.link.ncmTxSent",
                "wired.link.txNotReady", "wired.link.txShortWrite", "wired.link.rxResync",
                "wired.link.rxRejected", "wired.queue.rxHighWaterBytes", "wired.queue.rxHighWaterFrames",
                "wired.queue.txHighWaterBytes", "wired.queue.txHighWaterFrames", "wired.queue.commandHighWater",
                "wired.queue.commandHighWaterBytes", "wired.memory.currentBytes", "wired.memory.peakBytes",
                "wired.memory.liveHandles", "wired.udp.dropped", "wired.udp.txSent",
            ),
            keys,
        )
        assertTrue(report.contains("wired.backend.stage=FAILED"))
        assertTrue(report.contains("wired.backend.fallbackReason=VPN_UNAVAILABLE"))
        assertTrue(report.contains("wired.link.ncmTxSent=14"))
        assertTrue(report.contains("wired.queue.commandHighWaterBytes=2048"))
        assertTrue(report.contains("wired.stack.abi=unavailable"))
        assertFalse(report.contains("fe80::"))
        assertFalse(report.contains("aa:bb:cc"))
        assertFalse(report.contains("secret"))
        assertEquals(9, store.snapshot().generation)
    }

    @Test
    fun invalidMetricsAndLabelsAreOmitted() {
        val store = BackendDiagnosticStore()
        store.record(
            BackendDiagnosticSnapshot(
                generation = 1,
                stackVersion = "private device version",
                abi = "arm64-v8a",
                mtu = 1200,
                runtime = BackendRuntimeCounters(ncmTxSent = -1, lwipRxDropped = -1),
            ),
        )
        val report = store.report()
        assertTrue(report.contains("wired.stack.version=unavailable"))
        assertTrue(report.contains("wired.stack.abi=arm64-v8a"))
        assertTrue(report.contains("wired.link.mtu=unavailable"))
        assertTrue(report.contains("wired.link.ncmTxSent=unavailable"))
        assertTrue(report.contains("wired.udp.dropped=unavailable"))
    }
}

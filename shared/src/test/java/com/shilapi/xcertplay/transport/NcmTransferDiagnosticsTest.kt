package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class NcmTransferDiagnosticsTest {
    @Test fun negativeReturnIsReportedWithoutInventingAUsbCauseAndLogsAreBounded() {
        var now = 0L
        val diagnostic = NcmTransferDiagnostics { now }
        val samples = (1..100).mapNotNull {
            diagnostic.write(100, -1, 20, 20_000_000L, ByteArray(14))
        }
        assertEquals(4, samples.size)
        assertTrue(samples.first().contains("returned=-1"))
        assertTrue(samples.first().contains("timeoutMs=20"))
        assertTrue(samples.first().contains("durationMs=20"))
        assertFalse(samples.any { it.contains("NAK") })
        now = 1_000_000_000L
        assertNotNull(diagnostic.write(100, -1, 20, 20_000_000L, ByteArray(14)))
        assertTrue(diagnostic.summary().contains("notReady=101"))
    }

    @Test fun firstSuccessBypassesThrottleAndPreservesElapsedAndWriteTotals() {
        var now = 0L
        val diagnostic = NcmTransferDiagnostics { now }
        repeat(10) { diagnostic.write(100, 0, 20, 20_000_000L, ByteArray(14)) }
        now = 18_000_000_000L
        val first = diagnostic.write(100, 100, 20, 5_000_000L, ByteArray(14))!!
        assertTrue(first.contains("firstSuccessMs=18000"))
        assertTrue(first.contains("success=1"))
        assertTrue(first.contains("writeTotalMs=205"))
        assertTrue(first.contains("writeMaxMs=20"))
        assertNull(diagnostic.write(100, 100, 2000, 1L, ByteArray(14)))
        assertNotNull(diagnostic.write(100, 1, 2000, 1L, ByteArray(14)))
        assertTrue(diagnostic.summary().contains("short=1"))
    }

    @Test fun receiveEvidenceBeforeWritesIsHeaderOnlyAndFirstSynIsNotThrottled() {
        val diagnostic = NcmTransferDiagnostics { 0L }
        repeat(10) { diagnostic.received(ByteArray(100) { 0x7f }) }
        val syn = ByteArray(74) { 0x7f }.apply {
            this[12] = 0x86.toByte(); this[13] = 0xdd.toByte()
            this[20] = 6; this[56] = 0x1b; this[57] = 0x58; this[67] = 2
        }
        val line = diagnostic.received(syn)!!
        assertTrue(line.contains("tcpSyn=true"))
        assertTrue(line.contains("dstPort=7000"))
        assertFalse(line.contains("7f7f"))
        assertTrue(diagnostic.summary().contains("rxFrames=11"))
    }

    @Test fun statusReportsStandardFieldsWithoutRawPayloadAndIsBounded() {
        val diagnostic = NcmTransferDiagnostics { 0L }
        val status = byteArrayOf(0xa1.toByte(), 0, 1, 0, 3, 0, 0, 0, 0x7f)
        val first = diagnostic.status(status, status.size)!!
        assertTrue(first.contains("notification=0 value=1 index=3 length=0"))
        assertFalse(first.contains("7f"))
        val samples = (1..100).mapNotNull { diagnostic.status(status, status.size) }
        assertEquals(7, samples.size)
        assertNotNull(NcmTransferDiagnostics { 0L }.status(byteArrayOf(1), 1))
    }

    @Test fun establishedTrafficStopsSamplingAndMalformedHeadersNeverBecomeTcpEvidence() {
        val diagnostic = NcmTransferDiagnostics { 2_000_000_000L }
        val invalidIpv4 = ByteArray(64).apply { this[12] = 8; this[23] = 6; this[27] = 2 }
        assertFalse(NcmFrameHeader.read(invalidIpv4).tcpSyn)
        val truncated = ByteArray(70).apply { this[12] = 0x86.toByte(); this[13] = 0xdd.toByte(); this[20] = 6; this[67] = 2 }
        assertFalse(NcmFrameHeader.read(truncated).tcpSyn)
        diagnostic.write(100, 100, 20, 1, ByteArray(14))
        repeat(100) { assertNull(diagnostic.received(ByteArray(100))) }
        assertTrue(diagnostic.summary().contains("rxFrames=100"))
    }
}

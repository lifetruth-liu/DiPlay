package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test fun aCyclicNdpDoesNotDuplicateItsDatagrams() {
        val frame = ByteArray(14) { it.toByte() }
        val block = Ntb16Codec.build(frame, 1)
        block[18] = 12 // NDP next pointer loops back to the current NDP.
        assertEquals(1, Ntb16Codec.parse(block).size)
    }

    @Test(expected = Ntb16Codec.ReceiveLimitExceeded::class)
    fun boundedParseRejectsExcessFramesBeforeCopyingThem() {
        Ntb16Codec.parseBounded(twoFrames(ByteArray(14), ByteArray(14)), 0, 60, 1, 100, 14, 1514)
    }

    @Test(expected = Ntb16Codec.ReceiveLimitExceeded::class)
    fun boundedParseRejectsExcessPayload() {
        Ntb16Codec.parseBounded(twoFrames(ByteArray(14), ByteArray(14)), 0, 60, 2, 27, 14, 1514)
    }

    @Test fun rejectedFrameLengthsAreCountedWithoutCopyingTheirPayload() {
        val valid = ByteArray(14) { 7 }
        val block = twoFrames(ByteArray(1515), valid)
        var rejected = 0
        val parsed = Ntb16Codec.parseBounded(block, 0, block.size, 1, 14, 14, 1514) { rejected++ }
        assertEquals(1, rejected)
        assertArrayEquals(valid, parsed.single())
    }

    private fun twoFrames(first: ByteArray, second: ByteArray): ByteArray {
        val block = ByteArray(32 + first.size + second.size)
        fun u16(offset: Int, value: Int) { block[offset] = value.toByte(); block[offset + 1] = (value ushr 8).toByte() }
        Ntb16Codec.build(first, 1).copyInto(block, 0, 0, 20)
        u16(8, block.size); u16(16, 20)
        u16(20, 32); u16(22, first.size)
        u16(24, 32 + first.size); u16(26, second.size)
        first.copyInto(block, 32); second.copyInto(block, 32 + first.size)
        return block
    }
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }
}

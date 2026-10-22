package com.shilapi.xcertplay.network.backend.lwip

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.transport.NcmSendResult
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePrototypeInstrumentationTest {
    @Test fun nativeUdpBindingsPreserveDatagramsAndRejectStaleTokens() {
        LwipNativeBindings.ensureLoaded()
        assertEquals(2, LwipNativeBindings.abiVersion())
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        val context = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(context > 0)
        var lastEndpoint = 0L
        try {
            val bound = LongArray(2)
            assertEquals(-1, LwipNativeBindings.udpBind(context, 0, LongArray(1)))
            assertEquals(0, LwipNativeBindings.udpBind(context, 0, bound))
            val endpoint = bound[0]
            val port = bound[1].toInt()
            assertTrue(endpoint > 0 && endpoint != context && port in 1..65535)
            assertEquals(-10, LwipNativeBindings.udpBind(context, port, LongArray(2)))
            val source = ByteArray(16)
            val metadata = IntArray(3)
            assertEquals(-11, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(0), source, metadata))
            assertEquals(0, LwipNativeBindings.setReady(context, true))
            repeat(16) { n ->
                assertEquals(0, LwipNativeBindings.supply(context, 350L + n * 250, null))
                assertEquals(0, LwipNativeBindings.poll(context))
            }
            fun drainRaw(): List<ByteArray> {
                val frames = mutableListOf<ByteArray>()
                val bytes = ByteArray(1514)
                while (true) {
                    val length = LwipNativeBindings.takeOutput(context, bytes)
                    assertTrue(length >= 0)
                    if (length == 0) return frames
                    frames.add(bytes.copyOf(length))
                }
            }
            drainRaw()
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte(); solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62); solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80); checksum(solicitation, 58, 56)
            assertEquals(0, LwipNativeBindings.input(context, solicitation))
            drainRaw()
            fun incoming(payload: ByteArray) {
                val frame = packet(17, 8 + payload.size)
                write16(frame, 54, 50000); write16(frame, 56, port); write16(frame, 58, 8 + payload.size)
                payload.copyInto(frame, 62); checksum(frame, 17, 60)
                assertEquals(0, LwipNativeBindings.input(context, frame))
                frame.fill(0)
            }
            incoming("hello".toByteArray())
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(2), ByteArray(15), metadata))
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(2), source, IntArray(2)))
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, source, source, metadata))
            val target = ByteArray(2)
            assertEquals(0, LwipNativeBindings.udpReceive(context, endpoint, target, source, metadata))
            assertArrayEquals("he".toByteArray(), target)
            assertArrayEquals(intArrayOf(50000, 5, 2), metadata)
            val peer = ip.copyOf().also { it[15] = 2 }
            assertArrayEquals(peer, source)
            incoming(byteArrayOf())
            assertEquals(0, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(0), source, metadata))
            assertArrayEquals(intArrayOf(50000, 0, 0), metadata)
            val payload = byteArrayOf(99, 111, 107, 88)
            assertEquals(-1, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, Int.MAX_VALUE, 2))
            assertEquals(-1, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, 0, -1))
            assertEquals(0, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, 1, 2))
            val sent = drainRaw().first { it[20] == 17.toByte() }
            assertEquals(port, read16(sent, 54))
            assertArrayEquals(byteArrayOf(111, 107), sent.copyOfRange(62, sent.size))
            val wrongThread = AtomicInteger()
            val worker = Thread { wrongThread.set(LwipNativeBindings.udpClose(context, endpoint)) }
            worker.start(); worker.join(2000)
            assertFalse(worker.isAlive); assertEquals(-3, wrongThread.get())
            assertEquals(-1, LwipNativeBindings.udpClose(context, context))
            assertEquals(0, LwipNativeBindings.udpClose(context, endpoint))
            assertEquals(-1, LwipNativeBindings.udpClose(context, endpoint))
            assertEquals(0, LwipNativeBindings.udpBind(context, port, bound))
            lastEndpoint = bound[0]
            assertNotEquals(endpoint, lastEndpoint)
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, target, source, metadata))
        } finally { assertEquals(0, LwipNativeBindings.destroy(context)) }
        val next = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(next > 0)
        try {
            assertEquals(-1, LwipNativeBindings.udpClose(next, lastEndpoint))
            assertEquals(-1, LwipNativeBindings.udpBind(context, 0, LongArray(2)))
        } finally { assertEquals(0, LwipNativeBindings.destroy(next)) }
    }
    @Test fun realIpv6InputReachesNativeUdpAndTcpEchoWithoutKernelSockets() {
        var now = 100L
        NativePrototypeCore.create(mac, ip, 1500) { now }.use { core ->
            core.prepare()
            core.setLinkReady(true)
            repeat(16) { now += 250; core.poll() }
            drain(core)
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte()
            solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62)
            solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80)
            checksum(solicitation, 58, 56)
            core.input(solicitation)
            assertTrue(drain(core).any { it[20] == 58.toByte() && it[54] == 136.toByte() })

            val payload = "JNI echo".toByteArray(Charsets.US_ASCII)
            val udp = packet(17, 8 + payload.size)
            write16(udp, 54, 50000); write16(udp, 56, 6000); write16(udp, 58, 8 + payload.size)
            payload.copyInto(udp, 62)
            checksum(udp, 17, 60)
            core.input(udp)
            val echoed = drain(core).first { it[20] == 17.toByte() }
            assertEquals(6000, read16(echoed, 54))
            assertEquals(50000, read16(echoed, 56))
            assertArrayEquals(payload, echoed.copyOfRange(62, echoed.size))

            fun tcp(flags: Int, sequence: Long, ack: Long, data: ByteArray = byteArrayOf()): ByteArray {
                val frame = packet(6, 20 + data.size)
                write16(frame, 54, 50000); write16(frame, 56, 7000)
                write32(frame, 58, sequence); write32(frame, 62, ack)
                frame[66] = 0x50; frame[67] = flags.toByte(); write16(frame, 68, 32768)
                data.copyInto(frame, 74); checksum(frame, 6, 70)
                return frame
            }
            core.input(tcp(2, 1000, 0))
            val synAck = drain(core).first { it[20] == 6.toByte() }
            assertEquals(0x12, synAck[67].toInt() and 0x12)
            val server = (0..3).fold(0L) { value, n -> (value shl 8) or (synAck[58 + n].toLong() and 255) }
            core.input(tcp(16, 1001, server + 1))
            core.input(tcp(24, 1001, server + 1, payload))
            assertTrue(drain(core).any { frame ->
                frame[20] == 6.toByte() && frame.copyOfRange(54 + ((frame[66].toInt() and 255) ushr 4) * 4, frame.size).contentEquals(payload)
            })
        }
    }

    @Test fun explicitNativeFactoryRunsThroughTheOwnedTransportWorkers() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val closed = java.util.concurrent.CountDownLatch(1)
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                sent.offer(frame.copyOf())
                return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long): ByteArray? {
                closed.await(timeoutMillis, TimeUnit.MILLISECONDS)
                return null
            }
            override fun close() { closed.countDown() }
        }
        val prototype = LwipPrototype.nativeEcho(port, mac, ip, 1500)
        prototype.use {
            it.start()
            val frame = sent.poll(5, TimeUnit.SECONDS)
            assertNotNull("native timer output must reach the port", frame)
            assertEquals(0x86, frame!![12].toInt() and 255)
            assertEquals(0xdd, frame[13].toInt() and 255)
        }
        assertEquals(LwipPrototype.State.STOPPED, prototype.snapshot().state)
        assertNull(prototype.snapshot().failure)
        assertEquals(0L, closed.count)
    }

    @Test fun loadsAndRunsRealNativeTimersAndCopiesQueuedFrames() {
        var now = 100L
        NativePrototypeCore.create(mac, ip, 1500) { now }.use { core ->
            core.prepare()
            assertEquals(1, core.stats().tcpListeners)
            assertEquals(1, core.stats().udpEndpoints)
            assertTrue(core.stats().configuredBytes <= 256 * 1024)
            core.setLinkReady(true)
            now += 2000
            core.poll()
            val first = core.takeOutput()!!
            assertEquals(0x86, first[12].toInt() and 255)
            assertEquals(0xdd, first[13].toInt() and 255)
            assertTrue(core.nextTimeoutMillis() in 0..0xffff_ffffL)
        }
    }

    @Test fun generationsAndThreadAffinitySurviveRepeatedNativeRecreation() {
        LwipNativeBindings.ensureLoaded()
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        var previous = 0L
        repeat(100) {
            val h = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
            assertTrue(h > 0)
            try {
            if (previous != 0L) assertEquals(-1, LwipNativeBindings.poll(previous))
            val result = AtomicInteger()
            Thread { result.set(LwipNativeBindings.poll(h)) }.apply { start(); join(2_000); assertFalse(isAlive) }
            assertEquals(-3, result.get())
            } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
            previous = h
        }
    }

    @Test fun jniRejectsInvalidArraysAndPreservesOutputOnASmallDestination() {
        LwipNativeBindings.ensureLoaded()
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        assertEquals(-1L, LwipNativeBindings.create(ByteArray(5), ip, 1500, 100, entropy))
        val h = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(h > 0)
        try {
            assertEquals(-1, LwipNativeBindings.input(h, ByteArray(1515)))
            assertEquals(-1, LwipNativeBindings.supply(h, 100, ByteArray(1025)))
            assertEquals(0, LwipNativeBindings.setReady(h, true))
            assertEquals(0, LwipNativeBindings.supply(h, 2100, null))
            assertEquals(0, LwipNativeBindings.poll(h))
            assertEquals(-2, LwipNativeBindings.takeOutput(h, ByteArray(1)))
            val frame = ByteArray(1514)
            assertTrue(LwipNativeBindings.takeOutput(h, frame) >= 14)
            assertEquals(-8, LwipNativeBindings.supply(h, 2099, null))
            assertEquals(-8, LwipNativeBindings.poll(h))
        } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
    }

    companion object {
        private val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        private val peerMac = byteArrayOf(2, 0, 0, 0, 0, 2)
        private val ip = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        private fun drain(core: NativePrototypeCore): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            while (true) frames.add(core.takeOutput() ?: return frames)
        }
        private fun read16(frame: ByteArray, offset: Int) = ((frame[offset].toInt() and 255) shl 8) or (frame[offset + 1].toInt() and 255)
        private fun write16(frame: ByteArray, offset: Int, value: Int) {
            frame[offset] = (value ushr 8).toByte(); frame[offset + 1] = value.toByte()
        }
        private fun write32(frame: ByteArray, offset: Int, value: Long) {
            repeat(4) { frame[offset + it] = (value ushr (24 - it * 8)).toByte() }
        }
        private fun packet(protocol: Int, payloadLength: Int) = ByteArray(54 + payloadLength).also {
            mac.copyInto(it); peerMac.copyInto(it, 6); write16(it, 12, 0x86dd)
            it[14] = 0x60; write16(it, 18, payloadLength); it[20] = protocol.toByte(); it[21] = 64
            ip.copyInto(it, 22); it[37] = 2; ip.copyInto(it, 38)
        }
        private fun checksum(frame: ByteArray, protocol: Int, checksumOffset: Int) {
            var sum = protocol + frame.size - 54
            for (offset in 22 until frame.size step 2) {
                sum += (frame[offset].toInt() and 255) shl 8
                if (offset + 1 < frame.size) sum += frame[offset + 1].toInt() and 255
            }
            while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
            val value = sum.inv() and 65535
            write16(frame, checksumOffset, if (value == 0) 65535 else value)
        }
    }
}

package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Session-owned direct storage. A buffer cannot be reused until its command has retired. */
internal class DirectBufferPool(
    private val maxBytes: Int = MAX_BYTES,
    private val allocateDirect: (Int) -> ByteBuffer = { ByteBuffer.allocateDirect(it) },
) : AutoCloseable {
    init { require(maxBytes in 1..MAX_BYTES) }

    internal data class Snapshot(
        val allocatedBytes: Int,
        val leasedBytes: Int,
        val leasedCount: Int,
        val peakLeasedBytes: Int,
    )

    internal class Slot(val buffer: ByteBuffer) {
        var leased = false
    }

    internal inner class Lease internal constructor(private val slot: Slot) : AutoCloseable {
        private val released = AtomicBoolean()
        val buffer: ByteBuffer get() = slot.buffer
        val capacity: Int get() = slot.buffer.capacity()
        override fun close() {
            if (released.compareAndSet(false, true)) release(slot)
        }
    }

    private val slots = ArrayList<Slot>()
    private var allocatedBytes = 0
    private var leasedBytes = 0
    private var leasedCount = 0
    private var peakLeasedBytes = 0
    private var closed = false

    @Synchronized
    fun acquire(requestedBytes: Int): Lease {
        require(requestedBytes >= 0)
        if (closed) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        val needed = requestedBytes.coerceAtLeast(1)
        val capacity = bucketSize(needed)
        if (capacity > maxBytes) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        val slot = slots.asSequence().filter { !it.leased && it.buffer.capacity() >= needed }
            .minByOrNull { it.buffer.capacity() } ?: run {
            if (capacity > maxBytes - allocatedBytes) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
            val buffer = try { allocateDirect(capacity) }
            catch (error: OutOfMemoryError) { throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, error) }
            Slot(buffer).also { slots.add(it); allocatedBytes += capacity }
        }
        slot.leased = true
        slot.buffer.clear()
        slot.buffer.limit(requestedBytes)
        leasedBytes += slot.buffer.capacity()
        leasedCount++
        peakLeasedBytes = maxOf(peakLeasedBytes, leasedBytes)
        return Lease(slot)
    }

    @Synchronized
    fun snapshot() = Snapshot(allocatedBytes, leasedBytes, leasedCount, peakLeasedBytes)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val iterator = slots.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            if (!slot.leased) {
                allocatedBytes -= slot.buffer.capacity()
                iterator.remove()
            }
        }
    }

    @Synchronized
    private fun release(slot: Slot) {
        if (!slot.leased) return
        slot.leased = false
        leasedBytes -= slot.buffer.capacity()
        leasedCount--
        if (closed) {
            slots.remove(slot)
            allocatedBytes -= slot.buffer.capacity()
        }
    }

    private fun bucketSize(needed: Int): Int {
        var value = 1
        while (value < needed && value <= maxBytes / 2) value = value shl 1
        return if (value < needed) needed else value
    }

    companion object { const val MAX_BYTES = 256 * 1024 }
}

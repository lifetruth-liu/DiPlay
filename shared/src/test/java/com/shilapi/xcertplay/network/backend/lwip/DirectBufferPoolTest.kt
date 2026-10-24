package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DirectBufferPoolTest {
    @Test fun leasesAreDirectExclusiveAndReusedAfterReturn() {
        val pool = DirectBufferPool(32)
        try {
            val first = pool.acquire(9)
            assertTrue(first.buffer.isDirect)
            assertTrue(first.buffer.capacity() >= 9)
            first.buffer.put(0, 41)

            val second = pool.acquire(9)
            assertNotSame(first.buffer, second.buffer)
            assertEquals(32, pool.snapshot().leasedBytes)
            assertEquals(32, pool.snapshot().peakLeasedBytes)

            first.close(); first.close()
            val reused = pool.acquire(1)
            assertSame(first.buffer, reused.buffer)
            assertEquals(0, reused.buffer.position())
            assertEquals(1, reused.buffer.limit())
            second.close(); reused.close()
            assertEquals(0, pool.snapshot().leasedBytes)
            assertEquals(0, pool.snapshot().leasedCount)
        } finally { pool.close() }
    }

    @Test fun fixedNativeCapacityCannotGrowPastBudgetAndRecoversOnSessionClose() {
        val pool = DirectBufferPool(16)
        val active = pool.acquire(9)
        try {
            val error = runCatching { pool.acquire(9) }.exceptionOrNull()
            assertTrue("$error", error is BackendFailure)
            assertEquals(BackendFailure.Kind.RESOURCE_LIMIT, (error as BackendFailure).kind)
            assertEquals(16, pool.snapshot().allocatedBytes)
            assertEquals(16, pool.snapshot().leasedBytes)
        } finally {
            pool.close()
            assertEquals(16, pool.snapshot().allocatedBytes)
            active.close()
            assertEquals(0, pool.snapshot().allocatedBytes)
            assertEquals(0, pool.snapshot().leasedBytes)
        }
    }

    @Test fun oversizedRequestAndAcquireAfterCloseAreRejectedWithoutAllocation() {
        val pool = DirectBufferPool(8)
        assertEquals(BackendFailure.Kind.RESOURCE_LIMIT,
            (runCatching { pool.acquire(9) }.exceptionOrNull() as BackendFailure).kind)
        pool.close()
        assertEquals(BackendFailure.Kind.CANCELLED,
            (runCatching { pool.acquire(1) }.exceptionOrNull() as BackendFailure).kind)
        assertEquals(0, pool.snapshot().allocatedBytes)
    }

    @Test fun directAllocationOutOfMemoryMapsToResourceLimitAndCanRetry() {
        var attempts = 0
        val pool = DirectBufferPool(16) { capacity ->
            attempts++
            if (attempts == 1) throw OutOfMemoryError("injected direct allocation failure")
            ByteBuffer.allocateDirect(capacity)
        }
        try {
            val failure = runCatching { pool.acquire(9) }.exceptionOrNull()
            assertTrue("$failure", failure is BackendFailure)
            assertEquals(BackendFailure.Kind.RESOURCE_LIMIT, (failure as BackendFailure).kind)
            assertTrue(failure.cause is OutOfMemoryError)
            assertEquals(0, pool.snapshot().allocatedBytes)
            assertEquals(0, pool.snapshot().leasedBytes)
            assertEquals(0, pool.snapshot().leasedCount)

            val lease = pool.acquire(9)
            assertTrue(lease.buffer.isDirect)
            assertEquals(16, pool.snapshot().allocatedBytes)
            lease.close()
            assertEquals(0, pool.snapshot().leasedBytes)
            assertEquals(0, pool.snapshot().leasedCount)
        } finally {
            pool.close()
        }
    }

    @Test fun realDirectMemoryExhaustionMapsToResourceLimitInIsolatedJvm() {
        val classpath = listOf(
            DirectBufferPool::class.java,
            BackendFailure::class.java,
            DirectBufferPoolTest::class.java,
            Unit::class.java,
        ).map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-XX:MaxDirectMemorySize=8k",
            "-cp", classpath,
            DirectBufferPoolTest::class.java.name,
            "direct-oom-child",
        ).redirectErrorStream(true).start()
        try {
            assertTrue("Direct OOM child timed out", child.waitFor(10, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            assertTrue(output, output.contains("real-direct-oom-mapped"))
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    @Test fun realHeapExhaustionDuringAllocationMapsToResourceLimitInIsolatedJvm() {
        val classpath = listOf(
            DirectBufferPool::class.java,
            BackendFailure::class.java,
            DirectBufferPoolTest::class.java,
            Unit::class.java,
        ).map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx32m",
            "-XX:MaxDirectMemorySize=1m",
            "-cp", classpath,
            DirectBufferPoolTest::class.java.name,
            "heap-oom-child",
        ).redirectErrorStream(true).start()
        try {
            assertTrue("Heap OOM child timed out", child.waitFor(10, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            assertTrue(output, output.contains("real-heap-oom-mapped"))
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            when (args.singleOrNull()) {
                "direct-oom-child" -> {
                    val pool = DirectBufferPool()
                    try {
                        val failure = runCatching { pool.acquire(DirectBufferPool.MAX_BYTES) }.exceptionOrNull()
                        check(failure is BackendFailure && failure.kind == BackendFailure.Kind.RESOURCE_LIMIT)
                        check(failure.cause is OutOfMemoryError)
                        check(pool.snapshot().allocatedBytes == 0)
                        check(pool.snapshot().leasedBytes == 0)
                        check(pool.snapshot().leasedCount == 0)
                        println("real-direct-oom-mapped")
                    } finally {
                        pool.close()
                    }
                }
                "heap-oom-child" -> {
                    val pool = DirectBufferPool(16) { capacity ->
                        val heapPressure = ByteArray(64 * 1024 * 1024)
                        check(heapPressure.size == 64 * 1024 * 1024)
                        ByteBuffer.allocateDirect(capacity)
                    }
                    try {
                        val failure = runCatching { pool.acquire(9) }.exceptionOrNull()
                        check(failure is BackendFailure && failure.kind == BackendFailure.Kind.RESOURCE_LIMIT)
                        check(failure.cause is OutOfMemoryError)
                        check(pool.snapshot().allocatedBytes == 0)
                        check(pool.snapshot().leasedBytes == 0)
                        check(pool.snapshot().leasedCount == 0)
                        println("real-heap-oom-mapped")
                    } finally {
                        pool.close()
                    }
                }
            }
        }
    }
}

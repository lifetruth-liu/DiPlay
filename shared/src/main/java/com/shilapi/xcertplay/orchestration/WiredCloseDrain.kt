package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.backend.LocalEgressResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounds the entire experimental session close, including a blocked media callback. */
internal object WiredCloseDrain {
    fun await(
        closeSessions: () -> Unit,
        awaitEgress: (Long, () -> Boolean) -> LocalEgressResult,
        cancelled: () -> Boolean,
        timeoutMillis: Long = 750,
    ): LocalEgressResult {
        require(timeoutMillis in 1..2000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val abort = AtomicBoolean()
        val result = AtomicReference(LocalEgressResult.NOT_CONFIRMED)
        val complete = CountDownLatch(1)
        val shouldAbort = { abort.get() || cancelled() }
        if (cancelled()) return LocalEgressResult.CANCELLED
        val worker = Thread({
            try {
                if (shouldAbort()) { result.set(LocalEgressResult.CANCELLED); return@Thread }
                closeSessions()
                if (shouldAbort()) { result.set(LocalEgressResult.CANCELLED); return@Thread }
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                result.set(if (remaining <= 0) LocalEgressResult.DEADLINE else awaitEgress(remaining, shouldAbort))
            } catch (_: Exception) {
                result.set(LocalEgressResult.NOT_CONFIRMED)
            } finally { complete.countDown() }
        }, "wired-session-drain").apply { isDaemon = true; start() }
        try {
            while (true) {
                if (cancelled()) return LocalEgressResult.CANCELLED
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return LocalEgressResult.DEADLINE
                if (complete.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) {
                    return if (cancelled()) LocalEgressResult.CANCELLED else result.get()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return LocalEgressResult.CANCELLED
        } finally {
            abort.set(true)
            worker.interrupt()
        }
    }
}

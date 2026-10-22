package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.MonotonicClock
import java.util.ArrayDeque

internal sealed interface CoreAttempt<out T> {
    data class Ready<T>(val value: T) : CoreAttempt<T>
    data object Pending : CoreAttempt<Nothing>
}

internal class CoreCommandQueue<C>(
    private val clock: MonotonicClock,
    private val wake: () -> Unit,
    private val maxCommands: Int = 128,
    private val maxBytes: Int = 256 * 1024,
    private val maxResources: Int = 80,
) {
    init { require(maxCommands in 1..128 && maxBytes in 0..256 * 1024 && maxResources in 1..80) }
    /** Release must be nonblocking and capture only bounded endpoint identity/metadata. */
    fun newResourceScope(release: (C) -> Unit): Scope = synchronized(signal) {
        closed?.let { throw it }
        if (resources.size >= maxResources) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        Scope(scopeIdentity, true).also { resources[it] = Resource(it, release) }
    }
    /** Cancels waiters and schedules one owner-thread release without using a business slot. */
    fun closeResource(scope: Scope, reason: BackendFailure): Boolean {
        val scheduled = synchronized(signal) {
            require(scope.identity === scopeIdentity && scope.resource) { "Invalid resource scope" }
            if (closed != null) return@synchronized false
            val resource = resources[scope] ?: return@synchronized false
            if (resource.phase != ResourcePhase.OPEN) return@synchronized false
            cancel(scope, reason)
            resource.phase = ResourcePhase.QUEUED
            closing.addLast(resource)
            true
        }
        if (scheduled) wake()
        return scheduled
    }
    /** Plain scopes are not registered; resource scopes remain registered through release. */
    class Scope internal constructor(internal val identity: Any, internal val resource: Boolean = false) {
        internal var failure: BackendFailure? = null
    }
    private val scopeIdentity = Any()
    fun newScope(): Scope = synchronized(signal) {
        closed?.let { throw it }
        Scope(scopeIdentity)
    }
    /** Cancellation needs no command slot, including when all slots are occupied. */
    fun cancel(scope: Scope, reason: BackendFailure) = synchronized(signal) {
        require(scope.identity === scopeIdentity) { "Scope belongs to another core queue" }
        if (scope.failure == null) {
            scope.failure = reason
            for (task in live) if (task.scope === scope) task.cancel(reason)
        }
    }
    data class Snapshot(val commands: Int, val bytes: Int, val resources: Int = 0, val closingResources: Int = 0)
    data class Batch(val more: Boolean, val completed: Boolean)
    private enum class Phase { PREPARING, QUEUED, RUNNING, WAITING, DONE }
    private enum class ResourcePhase { OPEN, QUEUED, RUNNING }
    private val signal = Object()
    private val live = linkedSetOf<Task<*>>()
    private val ready = ArrayDeque<Task<*>>(maxCommands)
    private val resources = linkedMapOf<Scope, Resource>()
    private val closing = ArrayDeque<Resource>(maxResources)
    private var retained = 0
    private var closed: BackendFailure? = null
    private var owner: Thread? = null

    /** Internal operations and discard handlers must not block. retainedBytes
     * includes all owned payloads retained by the operation/completion. */
    fun <T> call(retainedBytes: Int = 0, deadlineMillis: Long = 0, discard: (T) -> Unit = {}, scope: Scope? = null,
                 operation: (C) -> CoreAttempt<T>): T = callPrepared(retainedBytes, deadlineMillis, discard, scope) { operation }

    /** Reserves count/bytes before preparing owned payloads on the calling thread.
     * prepare must only perform bounded Java copying/allocation, never native I/O. */
    fun <T> callPrepared(retainedBytes: Int = 0, deadlineMillis: Long = 0, discard: (T) -> Unit = {},
                         scope: Scope? = null, prepare: () -> ((C) -> CoreAttempt<T>)): T {
        require(retainedBytes >= 0 && deadlineMillis >= 0)
        if (Thread.currentThread().isInterrupted) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        val task = synchronized(signal) {
            require(scope == null || scope.identity === scopeIdentity) { "Scope belongs to another core queue" }
            closed?.let { throw it }
            scope?.failure?.let { throw it }
            if (Thread.currentThread() === owner) throw BackendFailure(BackendFailure.Kind.INTERNAL)
            if (deadlineMillis != 0L && clock.nowMillis() >= deadlineMillis) throw BackendFailure(BackendFailure.Kind.TIMEOUT)
            if (live.size >= maxCommands || retainedBytes > maxBytes - retained) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
            Task(retainedBytes, deadlineMillis, discard, scope).also {
                live.add(it); retained += retainedBytes
            }
        }
        if (task.prepare(prepare)) {
            try { wake() } catch (error: Throwable) { synchronized(signal) { task.cancel(error) } }
        }
        return task.await()
    }
    fun snapshot() = synchronized(signal) {
        Snapshot(live.size, retained, resources.size, resources.values.count { it.phase != ResourcePhase.OPEN })
    }
    fun hasReady() = synchronized(signal) { ready.isNotEmpty() || closing.isNotEmpty() }

    /** A real input/timer/readiness event (or another completed operation) may
     * make a previous WOULD_BLOCK operation runnable. No retry busy loop. */
    fun progress() = synchronized(signal) {
        if (closed == null) for (task in live) {
            if (task.phase == Phase.WAITING && task.failure == null) {
                task.phase = Phase.QUEUED
                ready.addLast(task)
            }
        }
    }
    fun runBatch(core: C, limit: Int = 32): Batch {
        require(limit in 1..32)
        synchronized(signal) {
            if (owner != null && owner !== Thread.currentThread()) throw BackendFailure(BackendFailure.Kind.INTERNAL)
            owner = Thread.currentThread()
            for (task in live) if (task.expired()) task.cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
        }
        var completed = false
        repeat(limit) {
            val resource = synchronized(signal) {
                closing.pollFirst()?.also { it.phase = ResourcePhase.RUNNING }
            }
            if (resource != null) {
                try { resource.release(core) } finally {
                    synchronized(signal) { resources.remove(resource.scope) }
                }
                completed = true
                return@repeat
            }
            val task = synchronized(signal) {
                ready.pollFirst()?.also { it.active = true; it.phase = Phase.RUNNING }
            } ?: return Batch(hasReady(), completed)
            if (task.run(core)) completed = true
        }
        return Batch(hasReady(), completed)
    }
    fun nextDelayMillis(): Long = synchronized(signal) {
        val now = clock.nowMillis()
        live.asSequence().filter { it.failure == null && it.result == null && it.deadline != 0L }
            .map { (it.deadline - now).coerceAtLeast(0) }.minOrNull() ?: Long.MAX_VALUE
    }
    fun close(reason: BackendFailure) = synchronized(signal) {
        if (closed == null) {
            closed = reason
            for (task in live) task.cancel(reason)
            // Context destruction releases all native endpoints. Never invoke an unstarted
            // endpoint callback after global stop; a running callback still retains its slot.
            closing.clear()
            val iterator = resources.values.iterator()
            while (iterator.hasNext()) if (iterator.next().phase != ResourcePhase.RUNNING) iterator.remove()
        }
    }
    private inner class Resource(val scope: Scope, val release: (C) -> Unit) {
        var phase = ResourcePhase.OPEN
    }
    private fun retire(task: Task<*>) {
        if (task.acknowledged && !task.active && live.remove(task)) {
            ready.remove(task)
            task.operation = null
            retained -= task.bytes
        }
    }
    private inner class Task<T>(
        val bytes: Int, val deadline: Long,
        val discard: (T) -> Unit, val scope: Scope?,
    ) {
        var operation: ((C) -> CoreAttempt<T>)? = null
        var phase = Phase.PREPARING
        var active = true
        var acknowledged = false
        var result: CoreAttempt.Ready<T>? = null
        var failure: Throwable? = null
        fun expired() = result == null && failure == null && deadline != 0L && clock.nowMillis() >= deadline
        fun cancel(error: Throwable) {
            if (result != null || failure != null) return
            failure = error
            phase = Phase.DONE
            if (!active) ready.remove(this)
            signal.notifyAll()
        }
        fun prepare(factory: () -> ((C) -> CoreAttempt<T>)): Boolean {
            synchronized(signal) {
                if (expired()) cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
                if (failure != null) { active = false; return false }
            }
            var prepared: ((C) -> CoreAttempt<T>)? = null
            var error: Throwable? = null
            try { prepared = factory() } catch (caught: Throwable) {
                error = when (caught) {
                    is OutOfMemoryError -> BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT, caught)
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        BackendFailure(BackendFailure.Kind.CANCELLED, caught)
                    }
                    else -> caught
                }
            }
            return synchronized(signal) {
                if (error != null) cancel(error)
                if (Thread.currentThread().isInterrupted) cancel(BackendFailure(BackendFailure.Kind.CANCELLED))
                if (expired()) cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
                active = false
                if (failure != null) false else {
                    operation = checkNotNull(prepared)
                    phase = Phase.QUEUED
                    ready.addLast(this)
                    true
                }
            }
        }
        fun await(): T {
            synchronized(signal) {
                while (true) {
                    result?.let { acknowledged = true; retire(this); return it.value }
                    failure?.let { acknowledged = true; retire(this); throw it }
                    if (expired()) { cancel(BackendFailure(BackendFailure.Kind.TIMEOUT)); continue }
                    try {
                        if (deadline == 0L) signal.wait()
                        else signal.wait((deadline - clock.nowMillis()).coerceAtLeast(1))
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cancel(BackendFailure(BackendFailure.Kind.CANCELLED, error))
                    }
                }
            }
        }
        fun run(core: C): Boolean {
            synchronized(signal) {
                if (expired()) cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
                if (failure != null) { active = false; retire(this); return true }
            }
            val attempt = try { checkNotNull(operation).invoke(core) } catch (error: Throwable) {
                synchronized(signal) {
                    cancel(error); active = false; retire(this)
                }
                if (error is VirtualMachineError) throw error
                return true
            }
            when (attempt) {
                is CoreAttempt.Ready -> {
                    val abandoned = synchronized(signal) {
                        if (expired()) cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
                        if (failure == null) {
                            result = attempt; phase = Phase.DONE; active = false
                            signal.notifyAll(); retire(this); false
                        } else true
                    }
                    if (abandoned) try { discard(attempt.value) } finally {
                        synchronized(signal) { active = false; retire(this) }
                    }
                    return true
                }
                CoreAttempt.Pending -> synchronized(signal) {
                    if (expired()) cancel(BackendFailure(BackendFailure.Kind.TIMEOUT))
                    active = false
                    phase = if (failure == null) Phase.WAITING else Phase.DONE
                    retire(this)
                    return false
                }
            }
        }
    }
}

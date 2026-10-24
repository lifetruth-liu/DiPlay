package com.shilapi.xcertplay.network.backend

/** Closed set of phases written to a local diagnostic report; never stores raw exception text. */
enum class BackendDiagnosticStage {
    NOT_STARTED, SELECTED, VPN_ATTACH, USERSPACE_ATTACH, NETWORK_ATTACHED, SESSION_ACTIVE, FAILED, STOPPED,
}

enum class BackendDiagnosticFailure {
    VPN_UNAVAILABLE, USER_CANCELLED, CANCELLED, USB, MFI, PROTOCOL, NATIVE_UNAVAILABLE, INITIALIZATION, UNKNOWN,
}

enum class BackendNativeLoadResult { NOT_USED, PENDING, LOADED, FAILED }

data class BackendDiagnosticSnapshot(
    val requested: WiredBackendPreference? = null,
    val effective: WiredBackend? = null,
    val attempt: Int = 0,
    val generation: Int = 0,
    val stage: BackendDiagnosticStage = BackendDiagnosticStage.NOT_STARTED,
    val failureCode: BackendDiagnosticFailure? = null,
    val fallbackReason: WiredBackendFailure? = null,
    val stackVersion: String? = null,
    val abi: String? = null,
    val nativeLoadResult: BackendNativeLoadResult = BackendNativeLoadResult.NOT_USED,
    val mtu: Int? = null,
    val runtime: BackendRuntimeCounters = BackendRuntimeCounters(),
)

/** Small independently testable store; one immutable latest snapshot is retained. */
class BackendDiagnosticStore {
    @Volatile private var latest = BackendDiagnosticSnapshot()

    @Synchronized fun record(snapshot: BackendDiagnosticSnapshot) {
        if (snapshot.generation >= latest.generation) latest = sanitize(snapshot)
    }

    fun snapshot(): BackendDiagnosticSnapshot = latest

    /** A late callback from an older connection attempt cannot overwrite the current report. */
    @Synchronized fun update(generation: Int, transform: (BackendDiagnosticSnapshot) -> BackendDiagnosticSnapshot): Boolean {
        if (generation != latest.generation) return false
        latest = sanitize(transform(latest))
        return true
    }

    /** Fixed field names and enum values only; no device addresses, exception text or payloads. */
    fun report(): String {
        val value = snapshot()
        fun field(item: Any?) = item?.toString() ?: "unavailable"
        val runtime = value.runtime
        return buildString {
            appendLine("wired.backend.requested=${field(value.requested)}")
            appendLine("wired.backend.effective=${field(value.effective)}")
            appendLine("wired.backend.attempt=${value.attempt}")
            appendLine("wired.backend.generation=${value.generation}")
            appendLine("wired.backend.stage=${value.stage}")
            appendLine("wired.backend.failureCode=${field(value.failureCode)}")
            appendLine("wired.backend.fallbackReason=${field(value.fallbackReason)}")
            appendLine("wired.stack.version=${field(value.stackVersion)}")
            appendLine("wired.stack.abi=${field(value.abi)}")
            appendLine("wired.stack.nativeLoadResult=${value.nativeLoadResult}")
            appendLine("wired.link.mtu=${field(value.mtu)}")
            appendLine("wired.link.ncmTxSent=${field(runtime.ncmTxSent)}")
            appendLine("wired.link.txNotReady=${field(runtime.ncmTxNotReady ?: runtime.lwipTxNotReady)}")
            appendLine("wired.link.txShortWrite=${field(runtime.ncmTxShortWrite)}")
            appendLine("wired.link.rxResync=${field(runtime.ncmRxResync)}")
            appendLine("wired.link.rxRejected=${field(runtime.ncmRxRejected)}")
            appendLine("wired.queue.rxHighWaterBytes=${field(runtime.rxQueueHighWaterBytes)}")
            appendLine("wired.queue.rxHighWaterFrames=${field(runtime.rxQueueHighWaterFrames)}")
            appendLine("wired.queue.txHighWaterBytes=${field(runtime.txQueueHighWaterBytes)}")
            appendLine("wired.queue.txHighWaterFrames=${field(runtime.txQueueHighWaterFrames)}")
            appendLine("wired.queue.commandHighWater=${field(runtime.commandHighWaterCount)}")
            appendLine("wired.queue.commandHighWaterBytes=${field(runtime.commandHighWaterBytes)}")
            appendLine("wired.memory.currentBytes=${field(runtime.directBufferCurrentBytes)}")
            appendLine("wired.memory.peakBytes=${field(runtime.directBufferPeakBytes)}")
            appendLine("wired.memory.liveHandles=${field(runtime.liveResourceScopes)}")
            appendLine("wired.udp.dropped=${field(runtime.lwipRxDropped)}")
            appendLine("wired.udp.txSent=${field(runtime.lwipTxSent)}")
        }
    }

    private fun sanitize(value: BackendDiagnosticSnapshot): BackendDiagnosticSnapshot {
        fun safeCount(count: Long?) = count?.takeIf { it >= 0 }
        val runtime = value.runtime.copy(
            ncmTxSent = safeCount(value.runtime.ncmTxSent),
            ncmTxNotReady = safeCount(value.runtime.ncmTxNotReady),
            ncmTxShortWrite = safeCount(value.runtime.ncmTxShortWrite),
            ncmRxResync = safeCount(value.runtime.ncmRxResync),
            ncmRxRejected = safeCount(value.runtime.ncmRxRejected),
            lwipRxDropped = safeCount(value.runtime.lwipRxDropped),
            lwipTxSent = safeCount(value.runtime.lwipTxSent),
            lwipTxNotReady = safeCount(value.runtime.lwipTxNotReady),
            rxQueueHighWaterBytes = safeCount(value.runtime.rxQueueHighWaterBytes),
            rxQueueHighWaterFrames = safeCount(value.runtime.rxQueueHighWaterFrames),
            txQueueHighWaterBytes = safeCount(value.runtime.txQueueHighWaterBytes),
            txQueueHighWaterFrames = safeCount(value.runtime.txQueueHighWaterFrames),
            commandHighWaterCount = safeCount(value.runtime.commandHighWaterCount),
            commandHighWaterBytes = safeCount(value.runtime.commandHighWaterBytes),
            directBufferCurrentBytes = safeCount(value.runtime.directBufferCurrentBytes),
            directBufferPeakBytes = safeCount(value.runtime.directBufferPeakBytes),
            liveResourceScopes = safeCount(value.runtime.liveResourceScopes),
        )
        return value.copy(
            attempt = value.attempt.coerceIn(0, WiredBackendSelector.MAX_ATTEMPTS),
            generation = value.generation.coerceAtLeast(0),
            stackVersion = value.stackVersion?.takeIf { it.matches(SAFE_LABEL) },
            abi = value.abi?.takeIf { it.matches(SAFE_LABEL) },
            mtu = value.mtu?.takeIf { it in 1280..1500 },
            runtime = runtime,
        )
    }

    private companion object {
        val SAFE_LABEL = Regex("[A-Za-z0-9_.-]{1,48}")
    }
}

/** Process-local report store. It retains neither a controller nor a network object. */
object BackendDiagnostics {
    private val store = BackendDiagnosticStore()
    fun record(snapshot: BackendDiagnosticSnapshot) = store.record(snapshot)
    fun snapshot(): BackendDiagnosticSnapshot = store.snapshot()
    fun update(generation: Int, transform: (BackendDiagnosticSnapshot) -> BackendDiagnosticSnapshot): Boolean =
        store.update(generation, transform)
    fun report(): String = store.report()
}

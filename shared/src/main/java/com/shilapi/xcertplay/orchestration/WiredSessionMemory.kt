package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbEnumeration

internal enum class WiredSessionStopReason { ACTIVE_RECONNECT, STARTUP_FAILURE, DETACHED, ERROR }
internal enum class WiredTransitionResult { REQUESTED, REJECTED, DETACHED, ATTACHED, UNCHANGED_TIMEOUT }

/** Process-local session evidence. A USB node alone never identifies an enumeration. */
internal object WiredSessionMemory {
    data class Record(
        val enumeration: IphoneUsbEnumeration,
        val startedAtNanos: Long? = null,
        val activeAtNanos: Long? = null,
        val stoppedAtNanos: Long? = null,
        val stopReason: WiredSessionStopReason? = null,
        val transitionResult: WiredTransitionResult? = null,
        val transitionAtNanos: Long? = null,
        val transitionsRequested: Int = 0,
        val directFailureAtNanos: Long? = null,
        val recoveryClaimed: Boolean = false,
        val recoveryClaimedAtTransitionCount: Int? = null,
        val releasedAtNanos: Long? = null,
        val recoveryFailedAtNanos: Long? = null,
    )

    private val records = mutableMapOf<IphoneUsbEnumeration, Record>()
    // Never evict a revocation: old callbacks may outlive any controller. Only opaque tokens
    // remain for the process lifetime; removed phone identities and session records do not.
    private val revokedTokens = mutableSetOf<Long>()

    @Synchronized fun started(enumeration: IphoneUsbEnumeration, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: Record(enumeration)
        records[enumeration] = old.copy(startedAtNanos = nowNanos, stoppedAtNanos = null, stopReason = null, releasedAtNanos = null)
    }

    /** A successful session starts a new recovery cycle, unlike merely sending Start. */
    @Synchronized fun active(enumeration: IphoneUsbEnumeration, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        records[enumeration] = Record(enumeration,
            startedAtNanos = records[enumeration]?.startedAtNanos, activeAtNanos = nowNanos)
    }

    @Synchronized fun stopped(enumeration: IphoneUsbEnumeration, reason: WiredSessionStopReason, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: Record(enumeration)
        records[enumeration] = old.copy(stoppedAtNanos = nowNanos, stopReason = reason)
    }

    @Synchronized fun transition(enumeration: IphoneUsbEnumeration, result: WiredTransitionResult, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: Record(enumeration)
        records[enumeration] = old.copy(transitionResult = result, transitionAtNanos = nowNanos,
            transitionsRequested = old.transitionsRequested + if (result == WiredTransitionResult.REQUESTED) 1 else 0)
    }

    @Synchronized fun directFailed(enumeration: IphoneUsbEnumeration, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: Record(enumeration)
        records[enumeration] = old.copy(directFailureAtNanos = nowNanos)
    }

    @Synchronized fun released(enumeration: IphoneUsbEnumeration, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: return
        if (old.stoppedAtNanos == null) return
        records[enumeration] = old.copy(releasedAtNanos = nowNanos)
    }

    @Synchronized fun recoveryFailed(enumeration: IphoneUsbEnumeration, nowNanos: Long) {
        if (enumeration.token in revokedTokens) return
        val old = records[enumeration] ?: return
        if (!old.recoveryClaimed) return
        records[enumeration] = old.copy(recoveryFailedAtNanos = nowNanos)
    }

    /** Exactly one controller may recover, and only after teardown has completed. */
    @Synchronized fun claimRecovery(enumeration: IphoneUsbEnumeration): Boolean {
        if (enumeration.token in revokedTokens) return false
        val old = records[enumeration] ?: return false
        if (old.directFailureAtNanos == null || old.recoveryClaimed || old.releasedAtNanos == null ||
            old.recoveryFailedAtNanos != null ||
            old.stopReason != WiredSessionStopReason.STARTUP_FAILURE) return false
        records[enumeration] = old.copy(recoveryClaimed = true, recoveryClaimedAtTransitionCount = old.transitionsRequested)
        return true
    }

    @Synchronized fun snapshot(enumeration: IphoneUsbEnumeration): Record? = records[enumeration]
    @Synchronized fun invalidate(enumeration: IphoneUsbEnumeration) {
        revokedTokens.add(enumeration.token)
        records.keys.removeAll { it.token == enumeration.token }
    }
    @Synchronized internal fun clear() {
        records.clear()
        revokedTokens.clear()
    }
}

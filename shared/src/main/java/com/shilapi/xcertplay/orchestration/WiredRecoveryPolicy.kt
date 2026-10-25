package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbEnumeration

internal data class RecoveryContext(
    val enumeration: IphoneUsbEnumeration,
    val record: WiredSessionMemory.Record? = null,
) {
    // Even an accidentally supplied stale snapshot must not affect this enumeration.
    val currentRecord: WiredSessionMemory.Record? get() = record?.takeIf { it.enumeration == enumeration }
    val previousActiveReconnect: Boolean get() = currentRecord?.let {
        it.activeAtNanos != null && it.stopReason == WiredSessionStopReason.ACTIVE_RECONNECT
    } == true
}

/** Pure USB configuration policy. Resource ownership and lifecycle validation remain with the caller. */
internal object WiredRecoveryPolicy {
    data class Decision(val action: WiredConfigurationAction, val reason: String)

    fun decide(found: Boolean, carPlayMode: Boolean, transitionsTried: Int, maximumTransitions: Int,
               context: RecoveryContext): Decision {
        val record = context.currentRecord
        val tried = maxOf(transitionsTried, record?.transitionsRequested ?: 0)
        val canTransition = tried < maximumTransitions
        val complete = found && carPlayMode
        val claimedRecovery = record?.recoveryClaimed == true && record.directFailureAtNanos != null
        return when {
            record?.recoveryFailedAtNanos != null -> Decision(WiredConfigurationAction.REJECT, "recovery-budget-exhausted")
            claimedRecovery && tried == record?.recoveryClaimedAtTransitionCount && !canTransition ->
                Decision(WiredConfigurationAction.REJECT, "recovery-budget-exhausted")
            complete && claimedRecovery && tried == record?.recoveryClaimedAtTransitionCount && canTransition ->
                Decision(WiredConfigurationAction.REQUEST_TRANSITION, "released-direct-startup-failure")
            complete && record?.activeAtNanos != null && record.releasedAtNanos == null ->
                Decision(WiredConfigurationAction.REJECT, "previous-session-not-released")
            complete -> Decision(WiredConfigurationAction.USE,
                if (context.previousActiveReconnect) "previous-active-reuse" else "complete-configuration")
            found && tried > 0 -> Decision(WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET, "mode-request-already-tried")
            canTransition -> Decision(WiredConfigurationAction.REQUEST_TRANSITION, "missing-carplay-configuration")
            else -> Decision(WiredConfigurationAction.REJECT, "transition-budget-exhausted")
        }
    }
}

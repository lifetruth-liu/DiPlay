package com.shilapi.xcertplay.orchestration

/** Configuration actions preserve #634's mode request and #509/#455's rejected-request fallback. */
internal enum class WiredConfigurationAction(val diagnostic: String) {
    USE("reuse-descriptors"),
    USE_WITHOUT_APPLE_ETHERNET("reuse-without-apple-ethernet"),
    REQUEST_TRANSITION("request-transition"),
    REJECT("reject-missing-configuration"),
    ;

    companion object {
        /** Older iPhones and locked devices may reject a mode request yet offer usable interfaces. */
        fun fallbackAfterRejection(found: Boolean, carPlayMode: Boolean, context: RecoveryContext): Boolean =
            found && (!carPlayMode || context.previousActiveReconnect || context.currentRecord?.recoveryClaimed == true)
    }
}

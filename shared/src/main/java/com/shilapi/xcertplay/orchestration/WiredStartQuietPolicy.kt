package com.shilapi.xcertplay.orchestration

/** Internal launch-only experiment; production keeps the established two-second quiet window. */
enum class WiredStartQuietPolicy(val millis: Long) {
    DEFAULT(2_000L), ONE_SECOND(1_000L), HALF_SECOND(500L);

    companion object {
        fun resolve(requestedMillis: Long?, debuggable: Boolean): WiredStartQuietPolicy =
            if (debuggable) entries.firstOrNull { it.millis == requestedMillis } ?: DEFAULT else DEFAULT
    }
}

package com.shilapi.xcertplay.orchestration

/** Internal, non-persisted close-order experiment. Production keeps the legacy close path. */
enum class WiredCloseExperiment { LEGACY, GRACEFUL }

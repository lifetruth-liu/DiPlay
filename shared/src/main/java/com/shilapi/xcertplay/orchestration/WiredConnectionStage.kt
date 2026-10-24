package com.shilapi.xcertplay.orchestration

/** The first Start must not hide network evidence already observed before its successful write. */
internal object WiredConnectionStage {
    fun afterStart(linkOpen: Boolean, tcpObserved: Boolean): CarPlayStatus =
        if (linkOpen || tcpObserved) CarPlayStatus.EstablishingWiredSession else CarPlayStatus.WaitingForWiredNetwork
}

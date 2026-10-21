package com.shilapi.xcertplay.transport

/** Result of one complete NTB transfer, not acceptance into a software queue. */
sealed interface NcmSendResult {
    data object Sent : NcmSendResult
    /** Android reported no transferred bytes. This alone cannot distinguish NAK from detach. */
    data object NotReady : NcmSendResult
    data class Failed(val cause: IphoneUsbException) : NcmSendResult
}

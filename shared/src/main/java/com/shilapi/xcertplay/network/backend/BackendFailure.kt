package com.shilapi.xcertplay.network.backend

import java.io.IOException

class BackendFailure(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind {
        VPN_UNAVAILABLE, VPN_DENIED, NATIVE_UNAVAILABLE, BACKEND_INIT, USB_DETACHED, USB_IO,
        ADDRESS_CONFLICT, RESOURCE_LIMIT, PEER_CLOSED, PEER_RESET, PROTOCOL, CANCELLED,
        TIMEOUT, INTERNAL,
    }
}

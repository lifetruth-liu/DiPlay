package com.shilapi.xcertplay.network.backend.system

import java.net.Inet6Address
import java.net.InetAddress

/** One system adapter represents one interface even when IPv4 is the preferred listener. */
internal fun systemIpv6ScopeId(addresses: List<InetAddress>): Int {
    val scopes = addresses.filterIsInstance<Inet6Address>().map { it.scopeId }.filter { it != 0 }.distinct()
    require(scopes.size <= 1) { "Listener addresses belong to different IPv6 interfaces" }
    return scopes.singleOrNull() ?: 0
}

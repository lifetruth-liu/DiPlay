package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.system.systemIpv6ScopeId
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SystemAddressScopeTest {
    private fun scoped(scope: Int) = Inet6Address.getByAddress(null,
        InetAddress.getByName("fe80::1234").address, scope)

    @Test fun ipv4PreferredListenerKeepsTheSecondaryIpv6Interface() {
        assertEquals(7, systemIpv6ScopeId(listOf(InetAddress.getByName("192.0.2.1"), scoped(7))))
    }

    @Test fun equalAddressBytesOnDifferentInterfacesAreRejectedBeforeDeduplication() {
        assertThrows(IllegalArgumentException::class.java) { systemIpv6ScopeId(listOf(scoped(7), scoped(8))) }
        assertEquals(7, systemIpv6ScopeId(listOf(scoped(7), scoped(7))))
        assertEquals(0, systemIpv6ScopeId(listOf(InetAddress.getByName("::1"))))
    }
}

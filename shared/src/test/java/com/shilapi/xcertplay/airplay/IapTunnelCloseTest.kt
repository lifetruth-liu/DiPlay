package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class IapTunnelCloseTest {
    @Test fun sessionGracefulModeChangesMediaTunnelCloseToEof() {
        var graceful = false
        val opened = CountDownLatch(1)
        val tunnel = IapTunnel(ByteArray(32), gracefulClose = { graceful })
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) { opened.countDown() }
        })
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { peer ->
                peer.soTimeout = 2000
                assertTrue(opened.await(2, TimeUnit.SECONDS))
                graceful = true
                tunnel.close()
                assertEquals(-1, peer.getInputStream().read())
            }
        } finally { tunnel.close() }
    }
}

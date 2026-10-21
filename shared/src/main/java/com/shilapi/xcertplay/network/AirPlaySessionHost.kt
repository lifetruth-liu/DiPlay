package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Listener/session owner independent of Android services and VPN resources.
 * A lease identifies one attachment; stale teardown never closes a replacement.
 * The current system-socket boundary will be migrated to SessionNetwork separately.
 */
class AirPlaySessionHost(
    private val bindListeners: (List<InetAddress>, Int) -> List<ServerSocket> = { addresses, port ->
        val fallback: (Int, Int) -> Unit = { busy, bound ->
            Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
        }
        if (addresses.size == 1) listOf(AirPlayPortSelector.bind(addresses.single(), port, onFallback = fallback))
        else AirPlayPortSelector.bindAll(addresses, port, onFallback = fallback)
    },
    private val dispatchFailure: (() -> Unit) -> Unit = { action ->
        Thread(action, "airplay-teardown").apply { isDaemon = true; start() }
    },
) : Closeable {
    class Lease internal constructor()

    private data class Attachment(
        val lease: Lease,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val listenerIdentity: AirPlayListenerIdentity?,
        val onFailure: (Throwable) -> Unit,
    )

    @Volatile private var attachment: Attachment? = null
    private var closed = false
    private var servers = emptyList<ServerSocket>()
    private val sessions = mutableSetOf<AirPlaySession>()

    /**
     * The caller retains its transport and must generation-check [onFailure] before releasing it.
     * No VPN, USB, DNS discovery or transport readiness is required to prepare the listener.
     * [canAttach] is a nonblocking cancellation check, evaluated before replacing any listener.
     */
    @Synchronized
    fun attach(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
        onFailure: (Throwable) -> Unit = {},
        canAttach: () -> Boolean = { true },
    ): Lease {
        check(!closed) { "AirPlay host is closed" }
        check(canAttach()) { "AirPlay attachment is cancelled" }
        releaseLocked()
        val bound = bindListeners((listOf(bindAddress) + additionalBindAddresses).distinct(), config.port)
        try {
            require(bound.isNotEmpty()) { "No AirPlay listener" }
            val lease = Lease()
            val current = Attachment(lease, config.copy(port = bound.first().localPort), identity,
                pairings, mfi, listener, media, listenerIdentity, onFailure)
            servers = bound
            attachment = current
            bound.forEach { server ->
                runCatching { listener.onDebugLog(
                    "airplay listener ready family=${family(server.inetAddress)} port=${server.localPort}",
                ) }
                Thread({ acceptLoop(current, server) }, "airplay-accept").apply { isDaemon = true; start() }
            }
            return lease
        } catch (error: Throwable) {
            bound.forEach { server -> runCatching { server.close() }.exceptionOrNull()?.let(error::addSuppressed) }
            releaseLocked()
            throw error
        }
    }

    @Synchronized fun isAttached(): Boolean = attachment != null
    @Synchronized fun boundPort(lease: Lease? = null): Int? = attachment
        ?.takeIf { lease == null || it.lease === lease }?.config?.port

    @Synchronized fun detach(lease: Lease) {
        if (attachment?.lease === lease) releaseLocked()
    }

    @Synchronized fun detachWireless(owner: AirPlayListenerIdentity): Boolean {
        if (attachment?.listenerIdentity !== owner) return false
        releaseLocked()
        return true
    }

    @Synchronized internal fun detachAll() = releaseLocked()
    @Synchronized override fun close() { closed = true; releaseLocked() }

    private fun acceptLoop(current: Attachment, server: ServerSocket) {
        try {
            while (attachment === current) {
                val socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                var transferred = false
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.setSoLinger(true, 0)
                    val session = synchronized(this) {
                        if (attachment !== current || servers.none { it === server }) return
                        val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                        current.listenerIdentity?.let { owner ->
                            current.listener.onTcpAccepted(AirPlayTcpAccepted(owner, internalPeer, acceptedAtNanos))
                        }
                        runCatching { current.listener.onDebugLog("airplay TCP accepted family=${family(socket.inetAddress)}") }
                        // Listener callbacks can synchronously stop/restart the host.
                        if (attachment !== current) return
                        AirPlaySession(socket, current.config, current.identity, current.pairings,
                            current.mfi, object : AirPlaySessionListener by current.listener {
                                override fun onSessionActive(session: AirPlaySession) {
                                    if (attachment === current && (current.listenerIdentity == null || !internalPeer)) {
                                        current.listener.onSessionActive(session)
                                    }
                                }
                                override fun onSessionEnded(session: AirPlaySession) {
                                    synchronized(this@AirPlaySessionHost) { sessions.remove(session) }
                                    if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                                }
                            }, current.media).also { sessions.add(it); transferred = true }
                    }
                    session.start()
                } finally {
                    if (!transferred) socket.close()
                }
            }
        } catch (error: Exception) {
            dispatchFailure {
                val failed = synchronized(this) {
                    if (attachment !== current) return@dispatchFailure
                    releaseLocked()
                    current
                }
                failed.onFailure(error)
            }
        }
    }

    /** Invalidate first, then close a snapshot: callbacks may remove their own sessions. */
    private fun releaseLocked() {
        attachment = null
        val oldServers = servers
        servers = emptyList()
        oldServers.forEach { server -> runCatching { server.close() }.onFailure { Log.w(TAG, "AirPlay listener close failed", it) } }
        val oldSessions = sessions.toList()
        sessions.clear()
        oldSessions.forEach { session -> runCatching { session.close() }.onFailure { Log.w(TAG, "AirPlay session close failed", it) } }
    }

    private fun family(address: InetAddress): String = if (address is Inet6Address) "IPv6" else "IPv4"
    private companion object { const val TAG = "xcertplay-usb" }
}

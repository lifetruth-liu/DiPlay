package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.network.backend.system.systemIpv6ScopeId

/**
 * Listener/session owner independent of Android services and VPN resources.
 * A lease identifies one attachment; stale teardown never closes a replacement.
 * One attachment owns one network; its control sessions borrow it for every protocol channel.
 */
class AirPlaySessionHost(
    private val bindListeners: (SessionNetwork, List<NetworkEndpoint>, Int) -> List<TcpListener> = { network, addresses, port ->
        val fallback: (Int, Int) -> Unit = { busy, bound ->
            Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
        }
        AirPlayPortSelector.bindAll(network, addresses, port, onFallback = fallback)
    },
    private val dispatchFailure: (() -> Unit) -> Unit = { action ->
        Thread(action, "airplay-teardown").apply { isDaemon = true; start() }
    },
    private val systemNetworkFactory: (Int) -> SessionNetwork = { scope -> SystemSessionNetwork("airplay-host", scope) },
) : Closeable {
    class Lease internal constructor()

    private data class Attachment(
        val lease: Lease,
        val network: SessionNetwork,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val listenerIdentity: AirPlayListenerIdentity?,
        val acceptanceIdentity: AirPlayListenerIdentity,
        val onFailure: (Throwable) -> Unit,
    )

    @Volatile private var attachment: Attachment? = null
    private var closed = false
    private var servers = emptyList<TcpListener>()
    private val sessions = mutableSetOf<AirPlaySession>()

    /**
     * The caller retains its transport and must generation-check [onFailure] before releasing it.
     * No VPN, USB, DNS discovery or transport readiness is required to prepare the listener.
     * [canAttach] is a nonblocking cancellation check, evaluated before replacing any listener.
     * Ownership of [network] transfers after these checks; every accepted channel borrows it.
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
        network: SessionNetwork? = null,
    ): Lease {
        check(!closed) { "AirPlay host is closed" }
        check(canAttach()) { "AirPlay attachment is cancelled" }
        val addresses = listOf(bindAddress) + additionalBindAddresses
        val systemScope = if (network == null) systemIpv6ScopeId(addresses) else 0
        releaseLocked()
        val sessionNetwork = network ?: systemNetworkFactory(systemScope)
        val bound = try {
            bindListeners(sessionNetwork, addresses.distinct()
                .map { sessionNetwork.endpoint(it, config.port) }, config.port)
        } catch (error: Throwable) { sessionNetwork.close(); throw error }
        try {
            require(bound.isNotEmpty()) { "No AirPlay listener" }
            val lease = Lease()
            val current = Attachment(lease, sessionNetwork, config.copy(port = bound.first().localEndpoint.port), identity,
                pairings, mfi, listener, media, listenerIdentity, listenerIdentity ?: AirPlayListenerIdentity(0), onFailure)
            servers = bound
            attachment = current
            bound.forEach { server ->
                runCatching { listener.onDebugLog(
                    "airplay listener ready family=${family(InetAddress.getByAddress(server.localEndpoint.address))} port=${server.localEndpoint.port}",
                ) }
                Thread({ acceptLoop(current, server) }, "airplay-accept").apply { isDaemon = true; start() }
            }
            return lease
        } catch (error: Throwable) {
            bound.forEach { server -> runCatching { server.close() }.exceptionOrNull()?.let(error::addSuppressed) }
            releaseLocked()
            sessionNetwork.close()
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

    private fun acceptLoop(current: Attachment, server: TcpListener) {
        try {
            while (attachment === current) {
                val socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                var transferred = false
                try {
                    socket.configure(TcpOptions(resetOnClose = true))
                    val session = synchronized(this) {
                        if (attachment !== current || servers.none { it === server }) return
                        val internalPeer = isInternalAirPlayPeer(InetAddress.getByAddress(socket.remoteEndpoint.address), InetAddress.getByAddress(socket.localEndpoint.address))
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(current.acceptanceIdentity, internalPeer, acceptedAtNanos))
                        runCatching { current.listener.onDebugLog("airplay TCP accepted family=${family(InetAddress.getByAddress(socket.remoteEndpoint.address))}") }
                        // Listener callbacks can synchronously stop/restart the host.
                        if (attachment !== current) return
                        AirPlaySession(current.network, socket, current.config, current.identity, current.pairings,
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
        val oldNetwork = attachment?.network
        attachment = null
        val oldServers = servers
        servers = emptyList()
        oldServers.forEach { server -> runCatching { server.close() }.onFailure { Log.w(TAG, "AirPlay listener close failed", it) } }
        val oldSessions = sessions.toList()
        sessions.clear()
        oldSessions.forEach { session -> runCatching { session.close() }.onFailure { Log.w(TAG, "AirPlay session close failed", it) } }
        runCatching { oldNetwork?.close() }.onFailure { Log.w(TAG, "AirPlay network close failed", it) }
    }

    private fun family(address: InetAddress): String = if (address is Inet6Address) "IPv6" else "IPv4"
    private companion object { const val TAG = "xcertplay-usb" }
}

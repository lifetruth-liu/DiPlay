package com.shilapi.xcertplay.network

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.backend.SessionNetwork
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Bound session host. Wireless and user-space wired sessions never bind a VpnService. */
class CarPlaySessionService : Service() {
    inner class LocalBinder : Binder() {
        val service: CarPlaySessionService get() = this@CarPlaySessionService
    }
    sealed class AttachResult {
        data object Started : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }
    /** Identity for one wired user-space attachment; cancelling it cannot affect its replacement. */
    class WiredOwner {
        private val cancelled = AtomicBoolean(false)
        internal fun cancel() { cancelled.set(true) }
        internal fun isCancelled(): Boolean = cancelled.get()
    }
    val host = AirPlaySessionHost()
    private val binder = LocalBinder()
    private var generation = 0L
    private var wiredOwner: WiredOwner? = null
    private var wiredLease: AirPlaySessionHost.Lease? = null
    private var destroyed = false
    @Volatile var lastAttachFailure: String? = null
        private set
    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress, config: AirPlayConfig, identity: AirPlayIdentity,
        pairings: PairingStore, mfi: MfiAuthenticator?, listener: AirPlaySessionListener,
        media: AirPlayMediaHandler, additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        clearWiredOwnerLocked()
        val current = ++generation
        lastAttachFailure = null
        return try {
            host.attach(bindAddress, config, identity, pairings, mfi, listener, media,
                additionalBindAddresses, listenerIdentity, onFailure = { error ->
                    synchronized(this) {
                        if (current == generation) listener.onTransportError(error.message ?: error.javaClass.simpleName)
                    }
                })
            AttachResult.Started
        } catch (error: Exception) {
            lastAttachFailure = error.javaClass.simpleName + ": " + (error.message ?: "no message")
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Takes ownership of [network] for one wired attachment, without requiring a VPN service. */
    @Synchronized
    fun attachWiredUserspace(
        owner: WiredOwner, network: SessionNetwork, bindAddress: InetAddress,
        config: AirPlayConfig, identity: AirPlayIdentity, pairings: PairingStore,
        mfi: MfiAuthenticator?, listener: AirPlaySessionListener, media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
    ): AttachResult {
        clearWiredOwnerLocked()
        val current = ++generation
        wiredOwner = owner
        lastAttachFailure = null
        return try {
            val lease = host.attach(bindAddress, config, identity, pairings, mfi, listener, media,
                additionalBindAddresses, onFailure = { error ->
                    synchronized(this) {
                        if (current == generation && wiredOwner === owner) {
                            listener.onTransportError(error.message ?: error.javaClass.simpleName)
                        }
                    }
                }, canAttach = { current == generation && wiredOwner === owner && !owner.isCancelled() },
                network = network)
            wiredLease = lease
            AttachResult.Started
        } catch (error: Exception) {
            owner.cancel()
            if (wiredOwner === owner) wiredOwner = null
            wiredLease = null
            runCatching { network.close() }
            lastAttachFailure = error.javaClass.simpleName + ": " + (error.message ?: "no message")
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    fun isAttached(): Boolean = host.isAttached()
    fun boundPort(): Int? = host.boundPort()
    @Synchronized fun isAttached(owner: WiredOwner): Boolean =
        wiredOwner === owner && wiredLease?.let { host.boundPort(it) != null } == true
    @Synchronized fun boundPort(owner: WiredOwner): Int? =
        if (wiredOwner === owner) wiredLease?.let(host::boundPort) else null
    @Synchronized fun detachWired(owner: WiredOwner) {
        if (wiredOwner !== owner) return
        owner.cancel()
        generation++
        val lease = wiredLease
        wiredOwner = null
        wiredLease = null
        if (lease != null) host.detach(lease)
    }
    @Synchronized fun detachWireless(owner: AirPlayListenerIdentity) {
        if (host.detachWireless(owner)) { clearWiredOwnerLocked(); generation++ }
    }
    @Synchronized fun detach() { generation++; clearWiredOwnerLocked(); host.detachAll() }
    override fun onDestroy() {
        synchronized(this) { destroyed = true; generation++; clearWiredOwnerLocked(); host.close() }
        super.onDestroy()
    }

    private fun clearWiredOwnerLocked() {
        wiredOwner?.cancel()
        val lease = wiredLease
        wiredOwner = null
        wiredLease = null
        if (lease != null) host.detach(lease)
    }
}

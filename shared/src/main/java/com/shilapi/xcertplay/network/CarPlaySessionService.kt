package com.shilapi.xcertplay.network

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.net.InetAddress

/** Bound session host. Wireless and future userspace USB never bind a VpnService. */
class CarPlaySessionService : Service() {
    inner class LocalBinder : Binder() {
        val service: CarPlaySessionService get() = this@CarPlaySessionService
    }
    sealed class AttachResult {
        data object Started : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }
    val host = AirPlaySessionHost()
    private val binder = LocalBinder()
    private var generation = 0L
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

    fun isAttached(): Boolean = host.isAttached()
    fun boundPort(): Int? = host.boundPort()
    @Synchronized fun detachWireless(owner: AirPlayListenerIdentity) {
        if (host.detachWireless(owner)) generation++
    }
    @Synchronized fun detach() { generation++; host.detachAll() }
    override fun onDestroy() {
        synchronized(this) { generation++; host.close() }
        super.onDestroy()
    }
}

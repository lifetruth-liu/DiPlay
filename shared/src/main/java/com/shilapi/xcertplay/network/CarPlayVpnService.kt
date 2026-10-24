package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the wired VPN attachment; the bound ordinary service owns AirPlay sessions. */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }
    /** Identity of one controller, distinct from its UI lifetime or a reused connection label. */
    class Owner {
        private val cancelled = AtomicBoolean(false)
        internal fun cancel() { cancelled.set(true) }
        internal fun isCancelled(): Boolean = cancelled.get()
    }
    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(
            val message: String,
            val reason: AttachFailure = AttachFailure.ATTACHMENT_FAILED,
            val ncmReusable: Boolean = true,
        ) : AttachResult()
    }
    enum class AttachFailure { VPN_UNAVAILABLE, ATTACHMENT_FAILED }

    private val binder = LocalBinder()
    private var attachmentOwner: Owner? = null
    private var sessionHost: AirPlaySessionHost? = null
    private var hostLease: AirPlaySessionHost.Lease? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0L
    private var destroyed = false
    @Volatile var lastAttachFailure: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge, linkLocal: String, hostMac: ByteArray,
        config: AirPlayConfig, identity: AirPlayIdentity, pairings: PairingStore,
        mfi: MfiAuthenticator?, listener: AirPlaySessionListener, media: AirPlayMediaHandler,
        host: AirPlaySessionHost, owner: Owner,
    ): AttachResult {
        if (destroyed) return AttachResult.Failed("CarPlay VPN service is destroyed")
        if (owner.isCancelled()) return AttachResult.Failed("CarPlay attachment is cancelled")
        releaseLocked()
        lastAttachFailure = null
        val generation = ++attachGeneration
        val address = try {
            (InetAddress.getByName(linkLocal) as? Inet6Address)
                ?.takeIf { it.isLinkLocalAddress }
                ?: throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
        } catch (error: Exception) {
            lastAttachFailure = attachFailure(error)
            return AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        if (hostMac.size != 6) {
            lastAttachFailure = "hostMac must be 6 bytes"
            return AttachResult.Failed("hostMac must be 6 bytes")
        }
        val builder = try {
            Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                .addAllowedApplication(packageName)
        } catch (error: Exception) {
            lastAttachFailure = attachFailure(error)
            return AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        val establishedTunnel = try {
            builder.establish()
        } catch (error: Exception) {
            lastAttachFailure = attachFailure(error)
            val unavailable = error is SecurityException || error is IOException
            return AttachResult.Failed(
                error.message ?: error.javaClass.simpleName,
                if (unavailable) AttachFailure.VPN_UNAVAILABLE else AttachFailure.ATTACHMENT_FAILED,
                ncmReusable = true,
            )
        } ?: run {
            lastAttachFailure = "VpnService.establish returned null"
            return AttachResult.Failed(
                "VpnService.establish returned null", AttachFailure.VPN_UNAVAILABLE, ncmReusable = true,
            )
        }
        tun = establishedTunnel
        attachmentOwner = owner
        return try {
            val ipv6Bridge = Ipv6NcmBridge(ncm, checkNotNull(tun), hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            // Publish ownership before starting threads so start failure is cleaned up too.
            bridge = ipv6Bridge
            ipv6Bridge.start()
            sessionHost = host
            hostLease = host.attach(scopedToTunnel(address, listener), config, identity, pairings,
                mfi, listener, media, onFailure = { onTransportError(generation, listener, it) },
                canAttach = { !owner.isCancelled() })
            AttachResult.Started
        } catch (error: Exception) {
            val ncmReusable = bridge == null
            releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName, ncmReusable = ncmReusable)
        }
    }

    @Synchronized fun detach(owner: Owner) {
        if (attachmentOwner === owner) releaseLocked()
    }

    @Synchronized fun isAttached(owner: Owner): Boolean =
        attachmentOwner === owner && hostLease?.let { sessionHost?.boundPort(it) } != null

    @Synchronized fun boundPort(owner: Owner): Int? =
        if (attachmentOwner === owner) hostLease?.let { sessionHost?.boundPort(it) } else null

    override fun onDestroy() {
        synchronized(this) { destroyed = true; releaseLocked() }
        super.onDestroy()
    }

    private fun onTransportError(generation: Long, listener: AirPlaySessionListener, error: Throwable) {
        Thread({
            synchronized(this) {
                if (generation != attachGeneration) return@Thread
                releaseLocked()
                try { listener.onTransportError(error.message ?: error.javaClass.simpleName) }
                finally { if (attachmentOwner == null) stopSelf() }
            }
        }, "airplay-vpn-teardown").apply { isDaemon = true; start() }
    }

    /** Invalidate ownership before touching a lease or a transport. Old controllers cannot detach new ones. */
    private fun releaseLocked() {
        attachGeneration++
        attachmentOwner = null
        val oldHost = sessionHost
        val oldLease = hostLease
        sessionHost = null
        hostLease = null
        if (oldLease != null) runCatching { oldHost?.detach(oldLease) }
            .onFailure { Log.w(TAG, "AirPlay attachment close failed", it) }
        val oldBridge = bridge
        bridge = null
        val oldTun = tun
        tun = null
        try { oldBridge?.close() } finally { oldTun?.close() }
    }

    /**
     * Linux rejects bind() to a link-local IPv6 address without a scope id (EINVAL). Android 8+
     * IoBridge.bind looks the scope up itself, but Android 7.x binds natively in
     * PlainSocketImpl.socketBind and never derives one, so the unscoped wired listener failed with
     * "Invalid argument". Scope it to the tunnel that owns the address on every version; when no
     * interface owns it, keep the unscoped address and the platform's own behavior.
     */
    private fun scopedToTunnel(address: Inet6Address, listener: AirPlaySessionListener): InetAddress {
        if (address.scopeId != 0) return address
        var interfaces = emptyList<TunnelCandidate>()
        repeat(TUN_LOOKUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(TUN_LOOKUP_DELAY_MILLIS)
            interfaces = tunnelCandidates()
            selectTunnelInterface(address, interfaces)?.let { owner ->
                runCatching { listener.onDebugLog("wired AirPlay listener scope iface=${owner.name} index=${owner.index}") }
                return Inet6Address.getByAddress(null, address.address, owner.index)
            }
        }
        val tunnels = interfaces.filter { it.name.startsWith("tun") }.joinToString(",") { it.name }.ifEmpty { "none" }
        runCatching { listener.onDebugLog("wired AirPlay listener scope iface=not_found tun=$tunnels; binding unscoped") }
        return address
    }

    private fun tunnelCandidates(): List<TunnelCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().map { networkInterface ->
            TunnelCandidate(networkInterface.name, networkInterface.index, Collections.list(networkInterface.inetAddresses))
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500
        private const val TUN_LOOKUP_ATTEMPTS = 10
        private const val TUN_LOOKUP_DELAY_MILLIS = 50L

        internal data class TunnelCandidate(val name: String, val index: Int, val addresses: List<InetAddress>)

        /**
         * Picks the interface that owns [address]. A tun interface wins when another interface
         * happens to carry the same link-local address.
         */
        internal fun selectTunnelInterface(address: Inet6Address, interfaces: List<TunnelCandidate>): TunnelCandidate? {
            val owners = interfaces.filter { candidate ->
                candidate.index > 0 && candidate.addresses.any { owned ->
                    owned is Inet6Address && owned.address.contentEquals(address.address)
                }
            }
            return owners.firstOrNull { it.name.startsWith("tun") } ?: owners.firstOrNull()
        }

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        /**
         * Android 7.1 reported only "Invalid argument" when establish failed (#557); name the
         * exception chain and the frame that threw, so the rejected VPN setting can be identified.
         */
        internal fun attachFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { failure ->
                "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
            }
            val origin = error.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
            return chain + origin
        }
    }
}

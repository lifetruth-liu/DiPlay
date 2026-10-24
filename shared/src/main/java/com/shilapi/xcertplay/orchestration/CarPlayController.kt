package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.BluetoothDiagnosticSnapshot
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoPlaybackDelivery
import com.shilapi.xcertplay.hud.BydBluetoothSuspend
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.CarPlaySessionService
import com.shilapi.xcertplay.network.backend.NcmFramePort
import com.shilapi.xcertplay.network.backend.BackendDiagnosticFailure
import com.shilapi.xcertplay.network.backend.BackendDiagnosticSnapshot
import com.shilapi.xcertplay.network.backend.BackendDiagnosticStage
import com.shilapi.xcertplay.network.backend.BackendDiagnostics
import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.BackendNativeLoadResult
import com.shilapi.xcertplay.network.backend.BackendRuntimeCounters
import com.shilapi.xcertplay.network.backend.BackendRuntimeCountersProvider
import com.shilapi.xcertplay.network.backend.WiredBackend
import com.shilapi.xcertplay.network.backend.WiredBackendFailure
import com.shilapi.xcertplay.network.backend.WiredBackendPreference
import com.shilapi.xcertplay.network.backend.WiredBackendSelector
import com.shilapi.xcertplay.network.backend.lwip.LwipSessionNetwork
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.HotspotAddressRecovery
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WifiScanPause
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessInterfaceDiagnostics
import com.shilapi.xcertplay.network.WirelessReceiveDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbAdbGrant
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbEnumeration
import com.shilapi.xcertplay.transport.IphoneUsbLifecycle
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object WaitingForWiredNetwork : CarPlayStatus()
    data object EstablishingWiredSession : CarPlayStatus()
    data object TryingUserspace : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
                      val startupFailure: WirelessStartupFailure? = null,
                      val backendFailure: BackendDiagnosticFailure? = null,
                      val retryAllowed: Boolean = true) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        WifiScanPause.restoreIfNeeded(context.applicationContext)
        BydNavigationOutputs.start(context.applicationContext)
        BydNavigationOutputs.setClusterStreamControl(::applyClusterUi)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticStartedAtNanos = System.nanoTime()
    private val diagnosticRun = AtomicInteger()
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private fun currentBluetoothAdapter(): BluetoothAdapter? =
        runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter }
            .getOrNull() ?: runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    private fun awaitBluetoothEnabled(adapter: BluetoothAdapter, timeoutMillis: Long = 3_000L): Boolean {
        if (adapter.isEnabled) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = runCatching { adapter.state }.getOrNull()
            if (state == BluetoothAdapter.STATE_ON || adapter.isEnabled) return true
            if (state != BluetoothAdapter.STATE_TURNING_ON) break
            SystemClock.sleep(100)
        }
        return adapter.isEnabled
    }
    private val iphoneHost by lazy {
        IphoneUsbHost(
            appContext,
            requireUsbManager(),
            if (config.iphoneDevices.isNotEmpty()) {
                IphoneUsbMatcher(config.iphoneDevices)
            } else {
                IphoneUsbMatcher.appleVendor()
            },
            onDiagnostic = ::connectionDiagnostic,
        )
    }
    private val iphoneLifecycle: IphoneUsbLifecycle by lazy { checkNotNull(WiredUsbLifecycle.install(appContext)) }
    private data class WiredAttempt(val generation: Int, val enumeration: IphoneUsbEnumeration)
    private val wiredGeneration = AtomicInteger()
    @Volatile private var wiredAttempt: WiredAttempt? = null
    @Volatile private var wiredEnumeration: IphoneUsbEnumeration? = null
    @Volatile private var wiredStopReason: WiredSessionStopReason? = null
    @Volatile private var wiredDirectAttempt = false
    @Volatile private var wiredRecoveryAttempt = false
    @Volatile private var pendingWiredUsb: Iap2UsbSession? = null
    @Volatile private var locallyOwnedNcm: NcmUsbBridge? = null
    @Volatile private var teardownResourceFailed = false
    private val wiredRecoveryStrategy: WiredRecoveryExperiment
        get() = if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            config.wiredRecoveryExperiment else WiredRecoveryExperiment.DEFAULT
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private val usbAdbGrantAttempts = mutableSetOf<String>()
    private val pendingUsbAdbGrants = mutableSetOf<String>()
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    private val albumReplayLock = Any()
    private var initialArtworkReplay: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer? = null

    fun nowPlayingSnapshot(): com.shilapi.xcertplay.media.CarPlayNowPlaying =
        synchronized(playbackStatus) { playbackStatus.nowPlaying }

    /** One bounded payload, never a nearest-cover guess when a reference is absent. */
    fun matchingArtworkSnapshot(id: Int?): com.shilapi.xcertplay.transport.Iap2ArtworkTransfer? =
        synchronized(albumReplayLock) { initialArtworkReplay?.takeIf { id != null && it.id == id } }


    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var wifiScanPause: WifiScanPause? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var instantBluetoothLink: InstantBluetoothLink? = null
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    @Volatile private var wiredStartWatchdog: WiredStartWatchdog? = null
    @Volatile private var wiredSessionActive = false
    @Volatile private var wiredStartSentAtNanos: Long? = null
    @Volatile private var wiredTcpAttempt: WiredAttempt? = null
    private var wiredEstablishingAttempt: WiredAttempt? = null
    private val wiredStartQuiet: WiredStartQuietPolicy
        get() = WiredStartQuietPolicy.resolve(config.wiredStartQuiet.millis,
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAlternateAddress: InetAddress? = null
    @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val vpnOwner = CarPlayVpnService.Owner()
    private val wiredOwner = CarPlaySessionService.WiredOwner()
    @Volatile private var effectiveWiredBackend: WiredBackend? = null
    @Volatile private var wiredDiagnosticCountersProvider: BackendRuntimeCountersProvider? = null
    private val bindingLock = Any()
    @Volatile private var sessionService: CarPlaySessionService? = null
    @Volatile private var sessionBound = false
    private val sessionLatch = CountDownLatch(1)
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    /** Set when the handoff fell back and kept Bluetooth as the only iAP2 channel. */
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var detachCloseable: Closeable? = null
    private var usbLifecycleCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var lastCh341Listing: String? = null
    private var lastIphoneListing: String? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(bindingLock) {
                if (closed || !vpnBound) { vpnLatch.countDown(); return }
                vpnService = (binder as CarPlayVpnService.LocalBinder).service
                vpnLatch.countDown()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(bindingLock) { vpnService = null; vpnLatch.countDown() }
            networkServiceDisconnected("CarPlay VPN service disconnected")
        }
    }

    private val sessionServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(bindingLock) {
                if (closed || !sessionBound) { sessionLatch.countDown(); return }
                sessionService = (binder as CarPlaySessionService.LocalBinder).service
                sessionLatch.countDown()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(bindingLock) { sessionService = null; sessionLatch.countDown() }
            networkServiceDisconnected("CarPlay session service disconnected")
        }
    }

    private fun networkServiceDisconnected(message: String) {
        if (closed) return
        fail(IphoneUsbException.DeviceUnavailable(message))
        // Deliver the queued failure before closing, which invalidates ordinary status callbacks.
        mainHandler.post { close() }
    }

    private data class SessionCornerMask(val session: AirPlaySession, val png: ByteArray, val width: Int)
    @Volatile private var currentCornerMask: SessionCornerMask? = null

    /** The live AirPlay session, for a new host that adopts the running stream without a session-active callback. */
    fun activeAirPlaySession(): AirPlaySession? = activeSession?.takeIf { !closed && !it.isClosed }

    /** The video session can outlive its Activity; the new host must be able to replay the mask. */
    fun activeIphoneCornerMask(): Triple<AirPlaySession, ByteArray, Int>? {
        val current = currentCornerMask ?: return null
        if (closed || activeSession !== current.session || current.session.isClosed) return null
        return Triple(current.session, current.png.copyOf(), current.width)
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onTcpAccepted(event: AirPlayTcpAccepted) {
            if (config.transport == CarPlayTransport.WIRED) {
                val attempt = wiredAttempt ?: return
                if (!isWiredCurrent(attempt)) return
                publishWired(attempt) {
                    wiredTcpAttempt = attempt
                    if (!wiredSessionActive) {
                        wiredEstablishingAttempt = attempt
                        onStatus(CarPlayStatus.EstablishingWiredSession)
                    }
                }
                connectionDiagnostic("USB TCP accepted backend=$effectiveWiredBackend")
            }
        }
        override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
            if (closed || session.isClosed) return
            if (activeSession != null && activeSession !== session) return
            currentCornerMask = SessionCornerMask(session, png.copyOf(), displayWidth)
            uiListener?.onCornerMask(session, png, displayWidth)
        }
        override fun onSessionActive(session: AirPlaySession) {
            if (closed || session.isClosed) return
            if (config.transport == CarPlayTransport.WIRED) {
                val attempt = wiredAttempt ?: return
                if (!isWiredCurrent(attempt)) return
                synchronized(wirelessResourceLock) {
                    if (closed || wiredAttempt != attempt) return
                    WiredSessionMemory.active(attempt.enumeration, System.nanoTime())
                    wiredSessionActive = true
                    wiredDirectAttempt = false
                    wiredRecoveryAttempt = false
                }
                connectionDiagnostic("USB session active")
            }
            wiredSessionActive = true
            if (config.transport == CarPlayTransport.WIRED && effectiveWiredBackend != null) {
                updateBackendDiagnostics { it.copy(stage = BackendDiagnosticStage.SESSION_ACTIVE) }
                captureWiredBackendCounters()
            }
            wiredStartWatchdog?.sessionActive()
            if (currentCornerMask?.session !== session) currentCornerMask = null
            val replacement = activeSession !== session
            if (replacement) {
                BydNavigationOutputs.start(appContext)
                BydNavigationOutputs.carPlaySessionStarted()
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            val btPrefs = appContext.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
            if (btPrefs.getBoolean("bt_suspend_during_carplay", false)) {
                // close() marks [closed], and a closing session marks itself before onSessionEnded
                // clears it, ahead of each resume; the pause checks all three under resume's lock.
                BydBluetoothSuspend.suspend(appContext, this@CarPlayController, btSuspendDelayMs(btPrefs),
                    log = { debugLog(it) }) {
                    !closed && activeSession === session && !session.isClosed
                }
            }
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (!closed && config.transport == CarPlayTransport.WIRED && activeSession === session) {
                wiredStopReason = WiredSessionStopReason.ERROR
            }
            if (currentCornerMask?.session === session) currentCornerMask = null
            if (activeSession === session) {
                activeSession = null
                synchronized(albumReplayLock) { initialArtworkReplay = null }
                synchronized(wirelessResourceLock) {
                    if (!closed) {
                        BydNavigationOutputs.endNow(preserveTurnOverlay = config.transport == CarPlayTransport.WIRELESS)
                        BydBluetoothSuspend.resume(appContext, this@CarPlayController) { debugLog(it) }
                        com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
                    }
                }
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            if (config.transport == CarPlayTransport.WIRED) wiredStopReason = WiredSessionStopReason.ERROR
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && sessionService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        if (!hasRequiredUsbService()) return
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            try {
                usbLifecycleCloseable = iphoneLifecycle.addInvalidationListener(::onWiredEnumerationInvalidated)
                permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
                attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
                detachCloseable = iphoneHost.registerDetachReceiver(::onIphoneDetached)
            } catch (error: Exception) {
                closeReceivers()
                fail(error)
                return
            }
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shilapi.xcertplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** A visible physical map and its session/stream generation; null for a paused/virtual/turn-card route. */
    fun dashboardMapRoute(): Any? {
        val session = activeSession ?: return null
        val content = session.clusterContentRoute() ?: return null
        val stream = content.first
        val route = session to stream
        val visibility = clusterUiVisibility
        val shown = visibility?.takeIf { it.first == route }?.second ?: true
        if (!DashboardMapEligibility.permits(content.second,
                dashboardMapOutputVisible, stream, shown, closed)) return null
        return Triple(session, stream, dashboardMapEpoch.get() to content.third)
    }

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        val endingEnumeration = wiredEnumeration
        var drainWiredSession = false
        synchronized(wirelessResourceLock) {
            if (closed) return
            // Failures and physical detach never wait for the experimental close candidate.
            drainWiredSession = config.transport == CarPlayTransport.WIRED &&
                config.wiredCloseExperiment == WiredCloseExperiment.GRACEFUL &&
                appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                effectiveWiredBackend == WiredBackend.USERSPACE && wiredSessionActive && wiredStopReason == null &&
                endingEnumeration != null && iphoneLifecycle.isCurrent(endingEnumeration)
            closed = true
            wiredGeneration.incrementAndGet()
            wiredAttempt = null
            vpnOwner.cancel()
            wiredOwner.cancel()
            // Order cancellation with address-preference commits and generation replacement.
            wirelessGeneration.incrementAndGet()
            dashboardMapOutputVisible = false
        }
        if (endingEnumeration != null && iphoneLifecycle.isCurrent(endingEnumeration)) {
            val reason = wiredStopReason ?: if (wiredSessionActive) WiredSessionStopReason.ACTIVE_RECONNECT
                else WiredSessionStopReason.ERROR
            WiredSessionMemory.stopped(endingEnumeration, reason, System.nanoTime())
            connectionDiagnostic("USB session stopping reason=$reason")
        }
        if (config.transport == CarPlayTransport.WIRED && effectiveWiredBackend != null) {
            captureWiredBackendCounters()
            updateBackendDiagnostics {
                if (it.stage == BackendDiagnosticStage.FAILED) it else it.copy(stage = BackendDiagnosticStage.STOPPED)
            }
            wiredDiagnosticCountersProvider = null
        }
        firstTcpWatchdog?.terminate()
        wiredStartWatchdog?.terminate()
        BydBluetoothSuspend.resume(appContext, this) { debugLog(it) }
        startupTimer.shutdownNow()
        (hotspot as? ManualHotspotManager)?.close()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        BydNavigationOutputs.endNow()
        com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
        BydNavigationOutputs.clearClusterStreamControl(::applyClusterUi)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        val ordinaryService = sessionService
        unbindVpn()
        if (!drainWiredSession) unbindSessionService()
        Thread(
            {
                var retainedNetwork: com.shilapi.xcertplay.network.AirPlaySessionHost.RetainedNetwork? = null
                try {
                    if (drainWiredSession && endingEnumeration != null && iphoneLifecycle.isCurrent(endingEnumeration)) {
                        retainedNetwork = ordinaryService?.quiesceWired(wiredOwner)
                        val retained = retainedNetwork
                        if (retained != null) {
                            val result = WiredCloseDrain.await(
                                closeSessions = retained::closeSessionsGracefully,
                                awaitEgress = retained::awaitLocalEgress,
                                cancelled = { !iphoneLifecycle.isCurrent(endingEnumeration) },
                            )
                            if (result == com.shilapi.xcertplay.network.backend.LocalEgressResult.DEADLINE ||
                                result == com.shilapi.xcertplay.network.backend.LocalEgressResult.CANCELLED) {
                                // A timed-out callback may still be returning; do not certify full release.
                                teardownResourceFailed = true
                            }
                            connectionDiagnostic("USB close candidate result=$result localEgressOnly=true")
                        } else connectionDiagnostic("USB close candidate result=NO_CURRENT_LEASE")
                    }
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(ordinaryService) }
                        closeBestEffort("Wi-Fi scan pause") { wifiScanPause?.close() }
                        wifiScanPause = null
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    closeBestEffort("pending USBMUX pipe") { pendingWiredUsb?.close() }
                    pendingWiredUsb = null
                    closeBestEffort("untransferred NCM") { locallyOwnedNcm?.close() }
                    locallyOwnedNcm = null
                    closeBestEffort("retained AirPlay network") { retainedNetwork?.close() }
                    retainedNetwork = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach(vpnOwner) }
                        closeBestEffort("userspace NCM") { ordinaryService?.detachWired(wiredOwner) }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    closeBestEffort("retained AirPlay abort") { retainedNetwork?.close() }
                    if (drainWiredSession) unbindSessionService()
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    if (executorTerminated && !teardownResourceFailed && endingEnumeration != null && iphoneLifecycle.isCurrent(endingEnumeration)) {
                        WiredSessionMemory.released(endingEnumeration, System.nanoTime())
                    }
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
            clusterUiVisibility = (session to stream) to true
        }
        if (shown == clusterUiShown && (!shown || session.clusterUrl() != null)) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            clusterUiVisibility = (session to stream) to shown
            dashboardMapEpoch.incrementAndGet()
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        BydNavigationOutputs.onFrame(frame)
        com.shilapi.xcertplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    enum class InitialAlbumRefreshResult { SENT, FAILED, CLOSED, INACTIVE_SESSION, CANCELLED, CHANNEL_UNAVAILABLE }

    /** Existing metadata channel and worker; the caller owns the single-send budget. */
    fun refreshInitialNowPlaying(
        stillNeeded: () -> Boolean,
        beforeSend: () -> Boolean = { true },
        onFinished: (InitialAlbumRefreshResult) -> Unit = {},
    ) {
        var result = InitialAlbumRefreshResult.CANCELLED
        run {
            try {
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (activeSession == null) { result = InitialAlbumRefreshResult.INACTIVE_SESSION; return@run }
                if (!stillNeeded()) return@run
                // The RFCOMM bootstrap is not the runtime metadata channel after wireless handoff.
                val channel = if (config.transport == CarPlayTransport.WIRELESS) wirelessTunnelChannel else csm
                if (channel == null) {
                    result = InitialAlbumRefreshResult.CHANNEL_UNAVAILABLE
                    debugLog("album recovery skipped channel=unavailable")
                    return@run
                }
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (!stillNeeded() || !beforeSend()) return@run
                // Claim only at the send boundary; preflight skips leave the budget intact.
                result = InitialAlbumRefreshResult.FAILED
                channel.send(com.shilapi.xcertplay.iap2.message.Iap2ControlMessages.startNowPlayingUpdates(), 250)
                result = InitialAlbumRefreshResult.SENT
                debugLog("album recovery subscription sent")
            } catch (error: Exception) {
                debugLog("album recovery subscription failed type=${error.javaClass.simpleName}")
            } finally {
                onFinished(result)
            }
        }
    }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        if (closed) return
        synchronized(albumReplayLock) {
            initialArtworkReplay = transfer.takeIf { it.bytes.size <= 1024 * 1024 }
        }
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        if (!hasRequiredUsbService()) return
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val offlineDirectory = java.io.File(appContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    requireUsbManager(),
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            reportCh341Missing(host)
            waitForMfi()
        } else {
            debugLog("mfi CH341 listed ${Ch341UsbHost.describe(device)}")
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    // "MFi coprocessor not present" said only that no configured CH341 was listed. What Android does list shows
    // whether the adapter left the bus or came back under another identity after a session (#792). Each poll
    // reports only a change.
    private fun reportCh341Missing(host: Ch341UsbHost) {
        val listed = runCatching { host.listedDevices().joinToString(", ") }.getOrElse { "unavailable" }
        if (listed == lastCh341Listing) return
        lastCh341Listing = listed
        debugLog("mfi CH341 not listed by Android; USB devices=[$listed]")
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (requireUsbManager().hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        executor.execute { runWireless(generation) }
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        Thread(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = FirstTcpWatchdog(
                listener = listenerIdentity,
                schedule = { delay, action ->
                    val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { future.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && generation == wirelessGeneration.get()) {
                        flipWirelessAddressFamily(generation)
                        fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                            "No AirPlay TCP after CarPlay StartSession"), generation)
                        Thread({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
                log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
            )
            firstTcpWatchdog = watchdog
            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            // Wireless CarPlay begins over Bluetooth: a radio that is off is found before the Wi-Fi is changed.
            val initialAdapter = currentBluetoothAdapter()
            if (initialAdapter != null && initialAdapter.state == BluetoothAdapter.STATE_TURNING_ON) {
                awaitBluetoothEnabled(initialAdapter, timeoutMillis = 3_000L)
            }
            val activeAdapter = currentBluetoothAdapter()
            BluetoothRadioCheck.blockerForState(
                adapterPresent = activeAdapter != null,
                state = activeAdapter?.state,
                pausedByDiPlay = BydBluetoothSuspend.isSuspendedByUs(appContext),
            )?.let { throw BluetoothRadioCheck.failure(it) }
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                return
            }
            pauseWifiScans(hotspotInfo.backend)
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val preferredFamily = HotspotAddressPolicy.parseFamily(
                appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
                    .getString(HOTSPOT_FAMILY_PREFERENCE_KEY, null),
            )
            val addressRecovery = HotspotAddressRecovery(
                sample = { hotspotCandidates(hotspotInfo, fallbackToSnapshot = false) },
                cancelled = { isStaleWirelessRun(generation) },
                pause = Thread::sleep,
                log = { if (!isStaleWirelessRun(generation)) debugLog(it) },
            )
            val candidates = addressRecovery.awaitCandidates(hotspotCandidates(hotspotInfo), preferredFamily)
            val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
                ?: hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                wirelessPublishedAddress = hostAddress
                wirelessAlternateAddress = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
                wirelessAddressRecovery = addressRecovery
            }
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "preference=${preferredFamily ?: "default"} " +
                    "alternate=${wirelessAlternateAddress != null} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = currentBluetoothAdapter()
                ?: throw BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER)
            val bluetoothRecoveryComplete = BydBluetoothSuspend.resumeAndWait(appContext, adapter)
            if (adapter.state == BluetoothAdapter.STATE_TURNING_ON) {
                awaitBluetoothEnabled(adapter, timeoutMillis = 3_000L)
            }
            if (!adapter.isEnabled) throw IOException(BluetoothRadioCheck.RADIO_OFF)
            if (!bluetoothRecoveryComplete) throw IOException("Bluetooth recovery did not complete before the handshake")
            val device = selectWirelessBluetoothDevice(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitSessionService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        additionalBindAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
                    )
                ) {
                    CarPlaySessionService.AttachResult.Started -> Unit
                    is CarPlaySessionService.AttachResult.Failed -> {
                        debugLog("wireless AirPlay listener attach failed detail=${service.lastAttachFailure}")
                        throw IOException(result.message)
                    }
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                additionalAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
                bonjourClient.start()
            }
            startedBonjour = bonjourClient
            diagnostics.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            val socket = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
            }
            logBluetoothConnectionSnapshot(device, "before-connect")
            val bluetoothStarted = System.nanoTime()
            val bluetoothConnectMillis: Long
            try {
                connectBluetoothSocket(socket, device.address)
                bluetoothConnectMillis = elapsedMillis(bluetoothStarted)
                connectionDiagnostic("Bluetooth connect completed elapsedMs=$bluetoothConnectMillis " +
                    "socketReportedConnected=${runCatching { socket.isConnected }.getOrNull() ?: "unknown"}")
            } catch (error: Throwable) {
                connectionDiagnostic(
                    "Bluetooth connect failed elapsedMs=${elapsedMillis(bluetoothStarted)} " +
                        "failureClass=${diagnosticFailureClass(error)}",
                )
                logBluetoothConnectionSnapshot(device, "after-failure")
                throw error
            }
            debugLog("wireless RFCOMM connected address=${device.address}")
            logBluetoothConnectionSnapshot(device, "after-connect")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val stream = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                try {
                    BluetoothRfcommDuplexStream(socket, ::connectionDiagnostic).also { bluetoothStream = it }
                } finally {
                    // The stream owns the connected socket and also closes it if stream getters
                    // fail. Do not retain a second socket owner in bootstrap teardown.
                    if (bluetoothSocket === socket) bluetoothSocket = null
                }
            }
            if (BluetoothLinkCheck.looksUnreal(bluetoothConnectMillis, 0L)) {
                InstantBluetoothLink(generation, device, stream, bluetoothConnectMillis).also {
                    instantBluetoothLink = it
                    watchForUnrealBluetooth(it)
                }
            }
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) {
                    channel.close()
                    return
                }
                csm = channel
            }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog(
                "wireless Bluetooth iAP2 bootstrap starting " +
                    "location=false vehicleStatus=false",
            )
            startedHotspot?.validateReady()
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = bootstrapIdentification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                beforeStartSession = { startedHotspot?.validateReady() },
                onStartSessionSent = { watchdog.startSessionSent(it.sentAtNanos) },
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    diagnostics.controlProgress(message)
                    debugLog(message)
                },
            )
            if (isStaleWirelessRun(generation)) {
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                // A link that "connected" at once and failed without a byte may never have been
                // real: say so instead of retrying into the same failure.
                val unreal = instantBluetoothLink?.takeIf { it.generation == generation }
                    ?.let { unrealBluetoothFailure(it, "failure") }
                fail(unreal ?: error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) wirelessDiagnostics?.connectionAccepted()
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || !watchdog.sessionEstablished()) return
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                // Keep wirelessActiveReported false until maybeCompleteWirelessHandoff() closes
                // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun onWirelessTunnelReady(generation: Int) {
        synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                wirelessFailureReported.get()) return
            wirelessTunnelReady.set(true)
        }
        wirelessConnectionProof.authenticated(generation)
        debugLog("wireless iAP2 tunnel ready; handoffRequested=${wirelessHandoffRequested.get()}")
        maybeCompleteWirelessHandoff()
    }

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    private fun maybeCompleteWirelessHandoff() {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || wirelessFailureReported.get() ||
                !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    // A queued completion must not close a replacement generation's bootstrap.
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                        wirelessFailureReported.get() || !wirelessTunnelReady.get()) return@Thread
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    closeBluetoothBootstrapTransport()
                    onStatus(CarPlayStatus.WirelessActive, generation)
                }
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun keepBluetoothControlAlive(generation: Int): Boolean = synchronized(wirelessResourceLock) {
        !closed && phase == Phase.WIRELESS && generation == wirelessGeneration.get() &&
            !wirelessFailureReported.get() && activeSession != null &&
            wirelessConnectionProof.hasRenderedFrame(generation)
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    { handleWirelessHandoffTimeout(generation) },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        // Session establishment alone also occurs on black-screen failures. Only a rendered
        // frame proves that projection is usable when the optional tunneled control is absent.
        if (activeSession != null && wirelessConnectionProof.hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            wirelessHandoffFellBack.set(true)
            // The Bluetooth link is the only iAP2 channel here. Closing it would stop NowPlaying,
            // route guidance and call updates for the rest of the session, so keep it until the
            // tunnel really takes over (see maybeCompleteWirelessHandoff).
            debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control")
            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
        } else {
            closeWirelessStack(generation = generation)
            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
        }
    }


    /** The user's grace period before Bluetooth is suspended: 5–30 s, 10 s by default. */
    private fun btSuspendDelayMs(prefs: android.content.SharedPreferences): Long =
        when (prefs.getInt("bt_suspend_delay_seconds", 10)) {
            5 -> 5_000L
            15 -> 15_000L
            30 -> 30_000L
            else -> 10_000L
        }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        if (phase == Phase.DATAPATHS || phase == Phase.CONTROL) {
            connectionDiagnostic("USB discovery restart ignored while data paths are owned")
            return
        }
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        permissionPollGeneration++
        usbAdbGrantAttempts.clear()
        pendingUsbAdbGrants.clear()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            reportIphoneMissing()
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            lastIphoneListing = null
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    // "iPhone USB device not present" said only that no Apple device was listed. What Android does list shows
    // whether it sees anything on the head unit's port, a USB stick for example, or nothing at all (#763).
    // Each poll reports only a change.
    private fun reportIphoneMissing() {
        val listed = runCatching { iphoneHost.listedDevices().joinToString(", ") }.getOrElse { "unavailable" }
        if (listed == lastIphoneListing) return
        lastIphoneListing = listed
        debugLog("wired iPhone not listed by Android; USB devices=[$listed]")
    }

    private fun requestIphonePermission(device: UsbDevice) {
        if (closed) return
        val enumeration = iphoneLifecycle.current(device) ?: return
        val attempt = synchronized(wirelessResourceLock) {
            if (closed || phase == Phase.DATAPATHS || phase == Phase.CONTROL) return
            wiredAttempt?.takeIf { it.enumeration == enumeration } ?: WiredAttempt(
                wiredGeneration.incrementAndGet(), enumeration,
            ).also {
                wiredAttempt = it
                wiredEnumeration = enumeration
                permissionGrant.set(false)
                wiredDirectAttempt = false
                wiredRecoveryAttempt = false
                wiredSessionActive = false
                wiredStartSentAtNanos = null
                wiredTcpAttempt = null
                wiredEstablishingAttempt = null
            }
        }
        mainHandler.post { if (isWiredCurrent(attempt)) doRequestIphonePermission(device) }
    }

    /** TCP callbacks belong to the attachment that installed them, even during re-enumeration. */
    private fun wiredSessionListener(): AirPlaySessionListener {
        val attempt = wiredAttempt
        return object : AirPlaySessionListener by sessionListener {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (attempt != null) publishWired(attempt) { sessionListener.onTcpAccepted(event) }
            }
        }
    }

    private fun reportWiredNetworkStage(attempt: WiredAttempt, linkOpen: Boolean, tcpObserved: Boolean) {
        publishWired(attempt) {
            if (!wiredSessionActive) {
                val stage = WiredConnectionStage.afterStart(linkOpen,
                    tcpObserved || wiredTcpAttempt == attempt || wiredEstablishingAttempt == attempt)
                if (stage == CarPlayStatus.EstablishingWiredSession) wiredEstablishingAttempt = attempt
                onStatus(stage)
            }
        }
    }

    private fun isWiredCurrent(attempt: WiredAttempt): Boolean {
        if (closed || wiredAttempt != attempt) return false
        if (!iphoneLifecycle.isCurrent(attempt.enumeration)) return false
        return synchronized(wirelessResourceLock) {
            !closed && wiredAttempt == attempt && wiredGeneration.get() == attempt.generation
        }
    }

    private fun publishWired(attempt: WiredAttempt, publish: () -> Unit): Boolean {
        if (!isWiredCurrent(attempt)) return false
        return synchronized(wirelessResourceLock) {
            if (closed || wiredAttempt != attempt || wiredGeneration.get() != attempt.generation) false
            else { publish(); true }
        }
    }

    private fun onIphoneDetached(device: UsbDevice) {
        // The lifecycle subscription delivers invalidation; do not publish it a second time.
        iphoneLifecycle.detached(device)
    }

    private fun onWiredEnumerationInvalidated(enumeration: IphoneUsbEnumeration) {
        val expected = synchronized(wirelessResourceLock) {
            val attempt = wiredAttempt ?: return
            if (closed || attempt.enumeration != enumeration) return
            wiredGeneration.incrementAndGet()
            wiredAttempt = null
            permissionPollGeneration++
            availabilityPollGeneration.incrementAndGet()
            permissionGrant.set(false)
            phase == Phase.REENUMERATION
        }
        connectionDiagnostic("USB detached expected=$expected")
        WiredSessionMemory.invalidate(enumeration)
        mainHandler.post {
            val prefix = "${enumeration.token}:"
            pendingUsbAdbGrants.removeAll { it.startsWith(prefix) }
            usbAdbGrantAttempts.removeAll { it.startsWith(prefix) }
        }
        wiredStartWatchdog?.terminate()
        if (expected) {
            val generation = wiredGeneration.get()
            mainHandler.post { waitForNewEnumeration(generation) }
        } else {
            wiredStopReason = WiredSessionStopReason.DETACHED
            vpnOwner.cancel()
            wiredOwner.cancel()
            onStatus(CarPlayStatus.Failed("iPhone USB disconnected"))
            mainHandler.post { close() }
        }
    }

    private fun waitForNewEnumeration(generation: Int) {
        if (closed || wiredGeneration.get() != generation || phase != Phase.REENUMERATION) return
        val device = iphoneHost.discover().firstOrNull()
        if (device != null) {
            connectionDiagnostic("USB transition outcome=ATTACHED")
            onIphoneAttached(device)
        } else {
            onStatus(CarPlayStatus.WaitingForReenumeration)
            mainHandler.postDelayed({ waitForNewEnumeration(generation) }, DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS)
        }
    }

    private fun doRequestIphonePermission(device: UsbDevice, allowAdbFallback: Boolean = true) {
        val attempt = wiredAttempt ?: return
        if (!isWiredCurrent(attempt) || attempt.enumeration.deviceName != device.deviceName) return
        if (usbAdbGrantKey(device, attempt) in pendingUsbAdbGrants) return
        try {
            val request = iphoneHost.requestPermission(device, allowAdbFallback)
            if (!isWiredCurrent(attempt)) return
            when (request) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog(
                        "wired iPhone USB permission requested " +
                            "afterMissingUiAdbFailure=${request.afterMissingUiAdbFailure}",
                    )
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device, request.afterMissingUiAdbFailure)
                }
                is IphoneUsbHost.PermissionRequest.AdbRequired -> {
                    debugLog("wired iPhone USB permission route=ADB; system permission UI unavailable")
                    permissionGrant.set(false)
                    permissionPollGeneration++
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    attemptIphoneUsbAdbGrant(request.device, attempt)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun usbAdbGrantKey(device: UsbDevice, attempt: WiredAttempt): String =
        "${attempt.enumeration.token}:${attempt.generation}:$phase:$reenumerationAttempts:${device.deviceName}:${device.deviceId}"

    private fun attemptIphoneUsbAdbGrant(device: UsbDevice, attempt: WiredAttempt) {
        if (!isWiredCurrent(attempt)) return
        val key = usbAdbGrantKey(device, attempt)
        if (!usbAdbGrantAttempts.add(key)) {
            doRequestIphonePermission(device, allowAdbFallback = false)
            return
        }
        pendingUsbAdbGrants.add(key)
        val grantPhase = phase
        val grantGeneration = permissionPollGeneration
        connectionDiagnostic(
            "USB permission ADB grant starting phase=$phase reenumerationAttempts=$reenumerationAttempts",
        )
        executor.execute {
            if (!isWiredCurrent(attempt)) return@execute
            val result = runCatching { IphoneUsbAdbGrant.grant(appContext, device) }
                .getOrElse { IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.ERROR) }
            connectionDiagnostic(
                "USB permission ADB grant result=${result.code} attached=${result.attached} " +
                    "granted=${result.granted}",
            )
            mainHandler.post {
                if (!isWiredCurrent(attempt) || phase != grantPhase || permissionPollGeneration != grantGeneration) return@post
                // A stale grant must not clear the same key owned by a newer reconnect generation.
                pendingUsbAdbGrants.remove(key)
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                } else {
                    doRequestIphonePermission(device, allowAdbFallback = false)
                }
            }
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        if (closed || phase != Phase.IPHONE && phase != Phase.REENUMERATION) return
        val attempt = wiredAttempt ?: return
        val resultDevice = when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> result.device
            is IphoneUsbHost.PermissionResult.Denied -> result.device
        }
        if (!isWiredCurrent(attempt) || iphoneLifecycle.current(resultDevice) != attempt.enumeration) return
        val currentDevice = iphoneHost.discover().firstOrNull {
            it.deviceName == attempt.enumeration.deviceName && it.vendorId == attempt.enumeration.vendorId &&
                it.productId == attempt.enumeration.productId
        } ?: return
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                if (!requireUsbManager().hasPermission(currentDevice)) return
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(currentDevice)
                        val carPlayMode = configuration?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true
                        val enumeration = attempt.enumeration
                        val before = WiredSessionMemory.snapshot(enumeration)
                        if (before?.directFailureAtNanos != null && !wiredRecoveryAttempt) {
                            if (!WiredSessionMemory.claimRecovery(enumeration)) {
                                failAttempt(IphoneUsbException.Protocol("USB recovery exhausted; unplug and reconnect the iPhone"),
                                    retryAllowed = false)
                                return
                            }
                            wiredRecoveryAttempt = true
                        }
                        val context = RecoveryContext(enumeration, WiredSessionMemory.snapshot(enumeration), wiredRecoveryStrategy)
                        val decision = WiredRecoveryPolicy.decide(
                            configuration != null, carPlayMode, reenumerationAttempts,
                            MAXIMUM_REENUMERATION_ATTEMPTS, context,
                        )
                        val action = decision.action
                        wiredDirectAttempt = action == WiredConfigurationAction.USE && carPlayMode && !wiredRecoveryAttempt
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                "action=${action.diagnostic} reason=${decision.reason}",
                        )
                        when (action) {
                            WiredConfigurationAction.USE,
                            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET -> openDataPaths(currentDevice)
                            WiredConfigurationAction.REQUEST_TRANSITION -> beginReenumeration(
                                currentDevice,
                                WiredConfigurationAction.fallbackAfterRejection(
                                    configuration != null, carPlayMode, context),
                            )
                            WiredConfigurationAction.REJECT -> failAttempt(
                                IphoneUsbException.Protocol(
                                    if (decision.reason == "previous-session-not-released")
                                        "Previous USB session has not fully closed; unplug and reconnect the iPhone"
                                    else "iPhone did not expose a complete CarPlay USB configuration; unplug and reconnect the iPhone",
                                ),
                                retryAllowed = false,
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice, afterMissingUiAdbFailure: Boolean = false) {
        val attempt = wiredAttempt ?: return
        val generation = ++permissionPollGeneration
        // A custom OEM permission page may still be visible even when the standard AOSP component
        // is absent. Preserve the full user-response window after the ADB fallback fails.
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (!isWiredCurrent(attempt) || generation != permissionPollGeneration) return
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                if (afterMissingUiAdbFailure) {
                                    "The head unit's standard USB authorization page is unavailable, and neither " +
                                        "approved ADB nor its system permission request granted iPhone access"
                                } else {
                                    "iPhone USB permission was not granted; tap Reconnect iPhone to retry"
                                },
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    /** [fallbackAfterRejection]: the iPhone already offers USBMUX and NCM, so try that if it refuses. */
    private fun beginReenumeration(device: UsbDevice, fallbackAfterRejection: Boolean = false) {
        val attempt = wiredAttempt ?: return
        if (!isWiredCurrent(attempt)) return
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        WiredSessionMemory.transitionRequested(attempt.enumeration)
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            mainHandler.post {
                if (!isWiredCurrent(attempt) || phase != Phase.REENUMERATION) return@post
                when (transition) {
                    IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                        onStatus(CarPlayStatus.WaitingForReenumeration)
                        watchReenumeration(attempt)
                    }
                    is IphoneUsbHost.TransitionResult.Failed -> {
                        connectionDiagnostic("USB transition outcome=REJECTED fallback=$fallbackAfterRejection")
                        if (fallbackAfterRejection) openDataPaths(device) else fail(transition.error)
                    }
                }
            }
        }
    }

    /**
     * Only a USB attach ended the wait for the iPhone to come back in CarPlay mode, and an iPhone that ignores
     * the request never attaches again: after Save and reconnect an iPhone still in the mode of the ended
     * session left wired CarPlay waiting for minutes (#335). Without an attach the iPhone is looked at again,
     * which uses the mode it already offers, as before the request.
     */
    private fun watchReenumeration(attempt: WiredAttempt) {
        val transitionCount = reenumerationAttempts
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed({
            if (!isWiredCurrent(attempt) || phase != Phase.REENUMERATION || transitionCount != reenumerationAttempts ||
                generation != availabilityPollGeneration.get()) return@postDelayed
            val listed = iphoneHost.discover().firstOrNull { iphoneLifecycle.current(it) == attempt.enumeration } ?: return@postDelayed
            connectionDiagnostic(
                "USB transition unanswered after ${REENUMERATION_WAIT_MILLIS}ms count=$transitionCount; checking the iPhone again outcome=UNCHANGED_TIMEOUT",
            )
            requestIphonePermission(listed)
        }, REENUMERATION_WAIT_MILLIS)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        if (closed) return
        val enumeration = iphoneLifecycle.current(device) ?: return
        if (wiredAttempt?.enumeration == enumeration) return // duplicate attach must not cancel the transition timer
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        val attempt = wiredAttempt ?: return
        if (!isWiredCurrent(attempt) || phase == Phase.DATAPATHS || phase == Phase.CONTROL) return
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            if (!isWiredCurrent(attempt)) {
                if (result is IphoneUsbHost.Iap2SessionResult.Connected) result.session.close()
                return@openIap2UsbSessionAsync
            }
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    if (!publishWired(attempt) { pendingWiredUsb = result.session }) {
                        result.session.close()
                        return@openIap2UsbSessionAsync
                    }
                    try {
                        val ncm = openNcm(device)
                        if (!publishWired(attempt) { locallyOwnedNcm = ncm }) {
                            ncm.close()
                            result.session.close()
                            return@openIap2UsbSessionAsync
                        }
                        runStack(result.session, ncm, attempt)
                    } catch (error: Throwable) {
                        result.session.close()
                        if (isWiredCurrent(attempt)) fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> if (isWiredCurrent(attempt)) fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = requireUsbManager().openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function, onDiagnostic = ::connectionDiagnostic)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge, attempt: WiredAttempt) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (!isWiredCurrent(attempt)) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            if (!publishWired(attempt) { this.mux = mux; pendingWiredUsb = null }) {
                mux.close()
                return
            }
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient, attempt)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            // Temporary lab capture, limited to accessory/authentication messages and two minutes.
            try {
                val relay = carKitClient.openService(pairRecord, config.label, "com.apple.syslog_relay")
                Thread({
                    try {
                        relay.use {
                            val deadline = System.nanoTime() + 120_000_000_000L
                            val pending = StringBuilder()
                            val relevant = Regex(" (accessoryd|ACCCarPlayService|iap2d|CarPlay)([\\[(])", RegexOption.IGNORE_CASE)
                            while (!closed && System.nanoTime() < deadline) {
                                val bytes = relay.recv(8192, 1000) ?: continue
                                if (bytes.isEmpty()) break
                                pending.append(bytes.toString(Charsets.UTF_8).replace('\u0000', '\n'))
                                while (true) {
                                    val end = pending.indexOf("\n")
                                    if (end < 0) break
                                    val line = pending.substring(0, end)
                                    pending.delete(0, end + 1)
                                    if (relevant.containsMatchIn(line)) debugLog("PHONE ${line.take(2000)}")
                                }
                                if (pending.length > 65536) pending.clear()
                            }
                        }
                        debugLog("phone authentication diagnostic capture ended")
                    } catch (error: Exception) {
                        debugLog("phone authentication diagnostic capture ended: ${error.javaClass.simpleName}")
                    }
                }, "carplay-lab-phone-diagnostics").apply { isDaemon = true; start() }
                debugLog("phone authentication diagnostic capture started")
            } catch (error: Exception) {
                debugLog("phone authentication diagnostics unavailable: ${error.message}")
            }
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                if (!isWiredCurrent(attempt)) return
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient, attempt)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        debugLog("wired link TX begin ${wireSummary(data)}")
                        // Bound each TLS write while diagnosing the stalled certificate transfer.
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            if (!publishWired(attempt) { this.csm = csm }) {
                csm.close()
                return
            }
            debugLog("wired iAP2 CSM channel opened")

            if (!isWiredCurrent(attempt)) return
            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            val selector = WiredBackendSelector(config.wiredBackendPreference)
            val backend = selector.begin() ?: throw IllegalStateException("Wired backend attempt was already selected")
            effectiveWiredBackend = backend
            BackendDiagnostics.record(
                BackendDiagnosticSnapshot(
                    requested = config.wiredBackendPreference,
                    effective = backend,
                    attempt = 1,
                    generation = diagnosticAttempt,
                    stage = BackendDiagnosticStage.SELECTED,
                    stackVersion = WiredBackendSelector.STACK_VERSION,
                    abi = Build.SUPPORTED_ABIS.firstOrNull(),
                    nativeLoadResult = if (backend == WiredBackend.USERSPACE) BackendNativeLoadResult.PENDING
                        else BackendNativeLoadResult.NOT_USED,
                    mtu = 1500,
                ),
            )
            debugLog("wired backend requested=${config.wiredBackendPreference} effective=$backend attempt=1")
            val attached = when (backend) {
                WiredBackend.VPN -> {
                    updateBackendDiagnostics { it.copy(stage = BackendDiagnosticStage.VPN_ATTACH) }
                    val vpn = attachVpn(ncm, ncmHostMac)
                    if (vpn.started) {
                        ncmOwnedLocally = false
                        locallyOwnedNcm = null
                        true
                    } else {
                        if (!vpn.ncmReusable) { ncmOwnedLocally = false; locallyOwnedNcm = null }
                        val fallback = selector.afterFailure(
                            backend = WiredBackend.VPN,
                            failure = vpn.failure ?: WiredBackendFailure.INITIALIZATION,
                            cancelled = closed,
                        )
                        if (fallback == WiredBackend.USERSPACE && vpn.ncmReusable && !closed) {
                            effectiveWiredBackend = fallback
                            updateBackendDiagnostics {
                                it.copy(
                                    effective = fallback,
                                    attempt = 2,
                                    stage = BackendDiagnosticStage.USERSPACE_ATTACH,
                                    failureCode = null,
                                    fallbackReason = vpn.failure,
                                    nativeLoadResult = BackendNativeLoadResult.PENDING,
                                )
                            }
                            publishWired(attempt) { onStatus(CarPlayStatus.TryingUserspace) }
                            debugLog("wired backend fallback requested=AUTO effective=$fallback attempt=2")
                            ncmOwnedLocally = false
                            locallyOwnedNcm = null
                            attachWiredUserspace(ncm, ncmHostMac, fallback = true)
                        } else {
                            updateBackendDiagnostics {
                                it.copy(
                                    stage = BackendDiagnosticStage.FAILED,
                                    failureCode = diagnosticFailureCode(vpn.failure),
                                )
                            }
                            false
                        }
                    }
                }
                WiredBackend.USERSPACE -> {
                    updateBackendDiagnostics { it.copy(stage = BackendDiagnosticStage.USERSPACE_ATTACH) }
                    // The helper takes responsibility for closing NCM even when construction or attach fails.
                    ncmOwnedLocally = false
                    locallyOwnedNcm = null
                    attachWiredUserspace(ncm, ncmHostMac)
                }
            }
            if (!attached) {
                updateBackendDiagnostics {
                    it.copy(
                        stage = BackendDiagnosticStage.FAILED,
                        failureCode = it.failureCode ?: BackendDiagnosticFailure.INITIALIZATION,
                    )
                }
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/${(effectiveWiredBackend ?: backend).name} AirPlay transport")
            }
            val activeBackend = checkNotNull(selector.currentBackend())
            updateBackendDiagnostics { it.copy(stage = BackendDiagnosticStage.NETWORK_ATTACHED) }
            debugLog("wired NCM/${activeBackend.name} AirPlay transport attached")
            if (!isWiredCurrent(attempt)) {
                when (activeBackend) {
                    WiredBackend.VPN -> vpnService?.detach(vpnOwner)
                    WiredBackend.USERSPACE -> sessionService?.detachWired(wiredOwner)
                }
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = wiredAirPlayPort(selector),
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            publishWired(attempt) {
                if (wiredTcpAttempt != attempt && wiredEstablishingAttempt != attempt) {
                    onStatus(CarPlayStatus.RunningControl)
                    onStatus(CarPlayStatus.ConnectingControl)
                }
            }
            lateinit var startWatchdog: WiredStartWatchdog
            startWatchdog = WiredStartWatchdog(
                schedule = { delay, action ->
                    val future = runCatching {
                        startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    val cancel: () -> Unit = { future?.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (isWiredCurrent(attempt) && wiredStartWatchdog === startWatchdog) {
                        debugLog(
                            "wired start: no AirPlay session ${WiredStartWatchdog.TIMEOUT_MILLIS / 1000} s " +
                                "after carplay-start-session; NCM ${ncm.trafficSummary(endpoint.airPlayPort)}",
                        )
                        failWiredStartup(attempt)
                    }
                },
            )
            wiredStartWatchdog = startWatchdog
            if (wiredSessionActive) startWatchdog.sessionActive()
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = progress@{ message ->
                    if (!isWiredCurrent(attempt)) return@progress
                    if (message == "iap2 identification accepted") {
                        publishWired(attempt) {
                            if (wiredTcpAttempt != attempt && wiredEstablishingAttempt != attempt && !wiredSessionActive)
                                onStatus(CarPlayStatus.ConnectingControl)
                        }
                    }
                    if (message == Iap2WiredControlClient.START_SESSION_AGAIN_PROGRESS) {
                        connectionDiagnostic("USB Start resent")
                        startWatchdog.startSessionSentAgain()
                    } else if (message.startsWith(START_SESSION_PROGRESS)) {
                        if (wiredStartSentAtNanos == null) {
                            val now = System.nanoTime()
                            wiredStartSentAtNanos = now
                            WiredSessionMemory.started(attempt.enumeration, now)
                            connectionDiagnostic("USB Start sent")
                        }
                        reportWiredNetworkStage(attempt, ncm.linkOpenedAtNanos != null,
                            ncm.connectionAttemptSince(endpoint.airPlayPort, 0L))
                        startWatchdog.startSessionSent()
                    }
                    debugLog("wired $message")
                },
                startSessionAgain = startAgain@{
                    if (!isWiredCurrent(attempt)) return@startAgain Iap2WiredControlClient.StartAgain.DONE
                    val opened = ncm.linkOpenedAtNanos
                    reportWiredNetworkStage(attempt, opened != null,
                        ncm.connectionAttemptSince(endpoint.airPlayPort, 0L))
                    WiredStartAgain.decide(
                        nowNanos = System.nanoTime(),
                        startSentAtNanos = wiredStartSentAtNanos ?: System.nanoTime(),
                        linkOpenedAtNanos = opened,
                        sessionActive = wiredSessionActive,
                        quiet = wiredStartQuiet,
                        connectionAttemptSinceLinkOpened = wiredTcpAttempt == attempt ||
                            (opened != null && ncm.connectionAttemptSince(endpoint.airPlayPort, opened)),
                    )
                },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            if (!isWiredCurrent(attempt)) return
            debugLog("wired bring-up failed", error)
            updateBackendDiagnostics {
                it.copy(
                    stage = BackendDiagnosticStage.FAILED,
                    failureCode = diagnosticFailureCode(error = error),
                    nativeLoadResult = if (diagnosticFailureCode(error = error) == BackendDiagnosticFailure.NATIVE_UNAVAILABLE)
                        BackendNativeLoadResult.FAILED else it.nativeLoadResult,
                )
            }
            if (!ncmOwnedLocally) when (effectiveWiredBackend) {
                WiredBackend.VPN -> vpnService?.detach(vpnOwner)
                WiredBackend.USERSPACE -> sessionService?.detachWired(wiredOwner)
                null -> Unit
            }
            fail(error)
        } finally {
            captureWiredBackendCounters()
            wiredDiagnosticCountersProvider = null
            wiredStartWatchdog?.terminate()
            if (ncmOwnedLocally) ncm.close()
            if (locallyOwnedNcm === ncm) locallyOwnedNcm = null
        }
    }

    private fun failWiredStartup(attempt: WiredAttempt) {
        if (!isWiredCurrent(attempt)) return
        synchronized(wirelessResourceLock) {
            if (closed || wiredAttempt != attempt || wiredSessionActive) return
            if (wiredDirectAttempt) WiredSessionMemory.directFailed(attempt.enumeration, System.nanoTime())
            fail(IphoneUsbException.TimedOut("The iPhone did not open CarPlay over USB in time"))
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient, attempt: WiredAttempt): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also { if (isWiredCurrent(attempt)) savePairRecord(it) }

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val readyDeadline = System.nanoTime() + WirelessStartupPolicy.HOTSPOT_READY_MILLIS * 1_000_000
        val hotspotMode = config.wirelessHotspotMode
        if (com.shilapi.xcertplay.network.CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            val result = com.shilapi.xcertplay.network.CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !com.shilapi.xcertplay.network.CarHotspotSettings.enabled(appContext) },
                timeoutMillis = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting hotspot interface") },
            )
            val manualFallback = result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.UNSUPPORTED &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == null
            if (result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                        result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        var pastOffReport = false
        if (hotspotMode == WirelessHotspotMode.MANUAL) {
            val state = com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext)
            if (com.shilapi.xcertplay.network.CarHotspotSettings.reportedOff(appContext, state)) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "The car hotspot is off. Turn it on in the car settings and connect again.")
            }
            if (state == false) debugLog("car hotspot: Android reports it off; connecting anyway, as the driver chose")
            pastOffReport = state == false
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext, ::debugLog,
                preferredChannel = config.wifiP2pPreferredChannel)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LocalOnlyHotspotManager(appContext, ::debugLog)
            } else {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "Android ${Build.VERSION.RELEASE} has no local-only hotspot. Choose Car hotspot or Wi-Fi Direct.")
            }
            WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                appContext, config.existingWifiSsid, config.existingWifiPassphrase, ::debugLog,
                onNetworkChanged = { if (!isStaleWirelessRun(generation)) restartWireless() },
            )
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = { debugLog("generation=$generation $it") },
                isCancelled = { isStaleWirelessRun(generation) },
                preferredInterface = config.manualHotspotInterface,
            )
        }
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            hotspot = manager
        }
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            if (pastOffReport && (failure as? WirelessStartupException)?.reason == WirelessStartupFailure.HOTSPOT_NOT_READY &&
                com.shilapi.xcertplay.network.CarHotspotSettings.hotspotNeverCameUp(
                    appContext, com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext))) {
                debugLog("car hotspot: Android still reports it off and no hotspot came up; the off warning is back")
            }
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    // close() sets [closed] under this lock, so a lease is either taken first and released by
    // close, or never taken.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(wirelessResourceLock) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        // Settings → Connection lets a driver keep the car's automatic joining, e.g. for the phone's hotspot (#755).
        if (!appContext.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
                .getBoolean("wifi_direct_scan_pause", true)) {
            if (wifiScanPause == null) debugLog("Wi-Fi scan pause off in Settings; the car keeps searching and joining networks")
            return@synchronized
        }
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    /** An RFCOMM link that came up too fast to have reached a phone, kept for [unrealBluetoothFailure]. */
    private class InstantBluetoothLink(
        val generation: Int,
        val device: BluetoothDevice,
        val stream: BluetoothRfcommDuplexStream,
        val connectMillis: Long,
    )

    /**
     * Some head units answer every RFCOMM connect with success at once and then carry no byte:
     * calls and music run on the maker's own Bluetooth module, and Android's adapter fronts
     * nothing a phone can hear. Some of them leave the link open and silent, others drop it after
     * a few seconds, so the link is put to the test both when it has stayed silent for a while and
     * when the attempt fails without a byte received (see the bring-up failure handler).
     */
    private fun watchForUnrealBluetooth(link: InstantBluetoothLink) {
        Thread({
            try {
                Thread.sleep(BluetoothLinkCheck.SILENCE_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (isStaleWirelessRun(link.generation)) return@Thread
            val failure = unrealBluetoothFailure(link, "silence") ?: return@Thread
            if (isStaleWirelessRun(link.generation)) return@Thread
            fail(failure, link.generation)
            closeWirelessStack(generation = link.generation)
        }, "diplay-bluetooth-link-check").apply { isDaemon = true; start() }
    }

    /**
     * The failure to report when [link] was never real, or null when it was or cannot be told.
     * The claim is put to a service no phone offers, which a real stack cannot connect to; only
     * when that "succeeds" at once as well is the link called unreal. A link that has delivered
     * a single byte is not tested at all.
     */
    private fun unrealBluetoothFailure(link: InstantBluetoothLink, trigger: String): WirelessStartupException? {
        val received = link.stream.bytesReceived
        if (!BluetoothLinkCheck.looksUnreal(link.connectMillis, received)) return null
        val started = System.nanoTime()
        val control = runCatching {
            link.device.createRfcommSocketToServiceRecord(UUID.randomUUID())
                .use { connectBluetoothSocket(it, link.device.address) }
        }
        val controlMillis = elapsedMillis(started)
        val unreal = BluetoothLinkCheck.controlProvesUnreal(control.isSuccess, controlMillis)
        connectionDiagnostic(
            "Bluetooth link check trigger=$trigger connectMs=${link.connectMillis} receivedBytes=$received " +
                "controlConnect=${if (control.isSuccess) "succeeded" else "failed"} " +
                "controlMs=$controlMillis unreal=$unreal",
        )
        if (!unreal) return null
        return WirelessStartupException(
            WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            "Android Bluetooth on this head unit reports ${BluetoothLinkCheck.UNREAL_MARK}: " +
                "a connection to a service no phone offers also succeeded in ${controlMillis}ms",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        val failure = result.get() ?: return
        if (BluetoothLinkCheck.socketServiceMissing(failure)) {
            connectionDiagnostic("Bluetooth socket manager missing failureClass=${failure.javaClass.simpleName}")
        }
        throw BluetoothLinkCheck.connectFailure(failure, address)
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val bondState = device.bondState
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            val activeAdapter = currentBluetoothAdapter()
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${activeAdapter?.isEnabled} " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlaySessionService? = sessionService, generation: Int? = null) =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            val diagnostics = wirelessDiagnostics
            wirelessDiagnostics = null
            diagnostics?.close()
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            val activeTunnel = wirelessTunnelChannel
            wirelessTunnelChannel = null
            if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

            closeBluetoothBootstrapTransport()

            val activeBonjour = bonjour
            bonjour = null
            if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

            val activeHotspot = hotspot
            hotspot = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessPublishedAddress = null
            wirelessAlternateAddress = null
            wirelessAddressRecovery = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)

            if (service != null && owner != null) closeBestEffort("AirPlay service") { service.detachWireless(owner) }
        }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
            // A head unit is the sink side of a phone's audio and calls, so the iPhone shows up under
            // these hidden profiles. Asked only when connected: an absent profile service never
            // answers the proxy request and would cost its full timeout.
            for (profile in intArrayOf(PROFILE_A2DP_SINK, PROFILE_HEADSET_CLIENT)) {
                val connected = try {
                    adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth profile $profile state", error)
                    false
                }
                if (connected) addAll(connectedBluetoothDevices(adapter, profile, BluetoothProfile::class.java))
            }
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun flipWirelessAddressFamily(generation: Int) = synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return@synchronized
        val published = wirelessPublishedAddress
        val next = published?.let { wirelessAddressRecovery?.nextFamily(it) }
        val from = published?.let {
            if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
        }
        if (isStaleWirelessRun(generation)) return@synchronized
        if (next == null || from == null) {
            debugLog(
                "wireless address family flip skipped published=${from ?: "none"} " +
                    "alternate=${next != null}",
            )
            return@synchronized
        }
        appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOTSPOT_FAMILY_PREFERENCE_KEY, next)
            .apply()
        debugLog("wireless address family flip published=$from next=$next")
    }

    private fun hotspotCandidates(info: WirelessHotspotInfo, fallbackToSnapshot: Boolean = true): List<InetAddress> {
        val name = info.interfaceName ?: return info.hostAddresses
        val scanned = try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
        } catch (error: Exception) {
            debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
            emptyList()
        }
        return if (scanned.isEmpty() && fallbackToSnapshot) info.hostAddresses else scanned
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            if (closed && config.transport == CarPlayTransport.WIRED) teardownResourceFailed = true
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        // Identification/authentication remain bounded. The bootstrap loop can extend this
        // deadline only once this generation has actually rendered a CarPlay frame.
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private data class VpnAttachOutcome(
        val started: Boolean,
        val failure: WiredBackendFailure? = null,
        val ncmReusable: Boolean = false,
    )

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): VpnAttachOutcome {
        onStatus(CarPlayStatus.AttachingNetwork)
        if (closed) return VpnAttachOutcome(false, WiredBackendFailure.CANCELLED, ncmReusable = true)
        if (config.wiredBackendPreference == WiredBackendPreference.AUTO &&
            config.vpnPreflightFailure == WiredBackendFailure.VPN_UNAVAILABLE) {
            debugLog("wired VPN unavailable during startup prerequisites; skipping VPN bind")
            return VpnAttachOutcome(false, WiredBackendFailure.VPN_UNAVAILABLE, ncmReusable = true)
        }
        val ordinaryService = awaitSessionService() ?: run {
            debugLog("wired session service bind failed")
            return VpnAttachOutcome(false, if (closed) WiredBackendFailure.CANCELLED else WiredBackendFailure.INITIALIZATION,
                ncmReusable = true)
        }
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            return VpnAttachOutcome(false, if (closed) WiredBackendFailure.CANCELLED else WiredBackendFailure.VPN_UNAVAILABLE,
                ncmReusable = true)
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                host = ordinaryService.host,
                owner = vpnOwner,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = wiredSessionListener(),
                media = media,
            )
        } catch (error: Throwable) {
            return VpnAttachOutcome(false, WiredBackendFailure.INITIALIZATION, ncmReusable = true)
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                VpnAttachOutcome(started = true)
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                VpnAttachOutcome(false, WiredBackendFailure.CANCELLED)
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message} detail=${service.lastAttachFailure}")
                VpnAttachOutcome(
                    started = false,
                    failure = if (result.reason == CarPlayVpnService.AttachFailure.VPN_UNAVAILABLE) {
                        WiredBackendFailure.VPN_UNAVAILABLE
                    } else {
                        WiredBackendFailure.INITIALIZATION
                    },
                    ncmReusable = result.ncmReusable,
                )
            }
        }
    }

    private fun wiredAirPlayPort(selector: WiredBackendSelector): Int =
        when (checkNotNull(selector.currentBackend())) {
            WiredBackend.VPN -> vpnService?.boundPort(vpnOwner)
            WiredBackend.USERSPACE -> sessionService?.boundPort(wiredOwner)
        } ?: airPlayConfig.port

    /** Takes ownership of NCM at entry and never binds or invokes the VPN service. */
    private fun attachWiredUserspace(ncm: NcmUsbBridge, hostMac: ByteArray, fallback: Boolean = false): Boolean {
        if (closed) { ncm.close(); return false }
        onStatus(if (fallback) CarPlayStatus.TryingUserspace else CarPlayStatus.AttachingNetwork)
        val service = awaitSessionService() ?: run {
            debugLog("wired session service bind failed for user-space backend")
            ncm.close()
            return false
        }
        var framePort: NcmFramePort? = null
        var network: LwipSessionNetwork? = null
        var transferred = false
        try {
            val ownedFramePort = NcmFramePort(ncm)
            framePort = ownedFramePort
            val linkLocal = InetAddress.getByName(config.linkLocal)
            require(linkLocal is Inet6Address && linkLocal.isLinkLocalAddress) {
                "linkLocal must be a link-local IPv6 literal"
            }
            val ownedNetwork = LwipSessionNetwork.native(
                port = ownedFramePort,
                mac = hostMac,
                ipv6 = linkLocal.address,
                linkId = "wired-$diagnosticAttempt",
            )
            network = ownedNetwork
            wiredDiagnosticCountersProvider = ownedNetwork
            updateBackendDiagnostics {
                it.copy(nativeLoadResult = BackendNativeLoadResult.LOADED, mtu = 1500)
            }
            // attachWiredUserspace consumes and closes the network on every result.
            transferred = true
            return when (val result = service.attachWiredUserspace(
                owner = wiredOwner,
                network = ownedNetwork,
                bindAddress = linkLocal,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = wiredSessionListener(),
                media = media,
            )) {
                CarPlaySessionService.AttachResult.Started -> {
                    debugLog("wired user-space NCM/lwIP transport attach result=started")
                    true
                }
                is CarPlaySessionService.AttachResult.Failed -> {
                    debugLog("wired user-space NCM/lwIP attach result=failed ${result.message} detail=${service.lastAttachFailure}")
                    false
                }
            }
        } catch (error: Exception) {
            debugLog("wired user-space NCM/lwIP construction failed", error)
            val failureCode = diagnosticFailureCode(error = error)
            updateBackendDiagnostics {
                it.copy(
                    failureCode = failureCode,
                    nativeLoadResult = if (failureCode == BackendDiagnosticFailure.NATIVE_UNAVAILABLE)
                        BackendNativeLoadResult.FAILED else it.nativeLoadResult,
                )
            }
            return false
        } finally {
            if (!transferred) {
                runCatching { network?.close() ?: framePort?.close() ?: ncm.close() }
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        if (closed) return null
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(NETWORK_SERVICE_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() = synchronized(bindingLock) {
        if (closed) { vpnLatch.countDown(); return@synchronized }
        if (vpnBound) return@synchronized
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() = synchronized(bindingLock) {
        vpnLatch.countDown()
        if (!vpnBound) return@synchronized
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun awaitSessionService(): CarPlaySessionService? {
        if (closed) return null
        sessionService?.let { return it }
        bindSessionService()
        return try {
            if (sessionLatch.await(NETWORK_SERVICE_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) sessionService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindSessionService() = synchronized(bindingLock) {
        if (closed) { sessionLatch.countDown(); return@synchronized }
        if (sessionBound) return@synchronized
        sessionBound = true
        try {
            val intent = Intent(appContext, CarPlaySessionService::class.java)
            if (!appContext.bindService(intent, sessionServiceConnection, Context.BIND_AUTO_CREATE)) {
                sessionBound = false
                sessionLatch.countDown()
            }
        } catch (_: Throwable) {
            sessionBound = false
            sessionLatch.countDown()
        }
    }

    private fun unbindSessionService() = synchronized(bindingLock) {
        sessionLatch.countDown()
        if (!sessionBound) return@synchronized
        sessionBound = false
        try {
            appContext.unbindService(sessionServiceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        sessionService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, detachCloseable, usbLifecycleCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        detachCloseable = null
        usbLifecycleCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun requireUsbManager(): UsbManager =
        usbManager ?: throw IOException("USB service is unavailable on this device")

    private fun hasRequiredUsbService(): Boolean {
        if (usbManager != null ||
            config.transport != CarPlayTransport.WIRED && config.mfiTarget != MfiTarget.USB_CH341) return true
        fail(IOException("USB service is unavailable on this device"))
        return false
    }

    private fun fail(error: Throwable, generation: Int? = null) = failAttempt(error, generation)

    private fun failAttempt(error: Throwable, generation: Int? = null, retryAllowed: Boolean = true) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (!wireless) {
                wiredEnumeration?.let { enumeration ->
                    wiredStopReason = if (wiredSessionActive) WiredSessionStopReason.ERROR else WiredSessionStopReason.STARTUP_FAILURE
                    WiredSessionMemory.stopped(enumeration, checkNotNull(wiredStopReason), System.nanoTime())
                    if (wiredRecoveryAttempt) WiredSessionMemory.recoveryFailed(enumeration, System.nanoTime())
                }
            }
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val causes = generateSequence(error) { it.cause }.toList()
            val wiredFailure = if (config.transport == CarPlayTransport.WIRED && effectiveWiredBackend != null) {
                val current = BackendDiagnostics.snapshot().takeIf { it.generation == diagnosticAttempt }
                current?.failureCode ?: diagnosticFailureCode(error = error)
            } else null
            if (wiredFailure != null) {
                updateBackendDiagnostics {
                    it.copy(stage = BackendDiagnosticStage.FAILED, failureCode = it.failureCode ?: wiredFailure)
                }
            }
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                causes.any { it is com.shilapi.xcertplay.network.P2pResetRequiredException },
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason,
                wiredFailure, retryAllowed = retryAllowed),
                if (wireless) wirelessGeneration.get() else null)
            if (!retryAllowed) mainHandler.post { close() }
        }
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase enum=${wiredEnumeration?.token ?: "none"} wiredGeneration=${wiredGeneration.get()} elapsedMs=${elapsedMillis(diagnosticStartedAtNanos)} $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun updateBackendDiagnostics(transform: (BackendDiagnosticSnapshot) -> BackendDiagnosticSnapshot) {
        BackendDiagnostics.update(diagnosticAttempt, transform)
    }

    private fun captureWiredBackendCounters() {
        val provider = wiredDiagnosticCountersProvider ?: return
        val counters = runCatching { provider.backendRuntimeCounters() }.getOrElse { BackendRuntimeCounters() }
        updateBackendDiagnostics { it.copy(runtime = counters) }
    }

    private fun diagnosticFailureCode(
        failure: WiredBackendFailure? = null,
        error: Throwable? = null,
    ): BackendDiagnosticFailure {
        when (failure) {
            WiredBackendFailure.VPN_UNAVAILABLE -> return BackendDiagnosticFailure.VPN_UNAVAILABLE
            WiredBackendFailure.USER_CANCELLED -> return BackendDiagnosticFailure.USER_CANCELLED
            WiredBackendFailure.CANCELLED -> return BackendDiagnosticFailure.CANCELLED
            WiredBackendFailure.USB -> return BackendDiagnosticFailure.USB
            WiredBackendFailure.MFI -> return BackendDiagnosticFailure.MFI
            WiredBackendFailure.PROTOCOL -> return BackendDiagnosticFailure.PROTOCOL
            WiredBackendFailure.NATIVE_UNAVAILABLE -> return BackendDiagnosticFailure.NATIVE_UNAVAILABLE
            WiredBackendFailure.INITIALIZATION -> return BackendDiagnosticFailure.INITIALIZATION
            null -> Unit
        }
        val causes = generateSequence(error) { it.cause }.toList()
        causes.filterIsInstance<BackendFailure>().firstOrNull()?.let { backend ->
            return when (backend.kind) {
                BackendFailure.Kind.VPN_UNAVAILABLE -> BackendDiagnosticFailure.VPN_UNAVAILABLE
                BackendFailure.Kind.VPN_DENIED -> BackendDiagnosticFailure.USER_CANCELLED
                BackendFailure.Kind.NATIVE_UNAVAILABLE -> BackendDiagnosticFailure.NATIVE_UNAVAILABLE
                BackendFailure.Kind.USB_DETACHED, BackendFailure.Kind.USB_IO -> BackendDiagnosticFailure.USB
                BackendFailure.Kind.PROTOCOL -> BackendDiagnosticFailure.PROTOCOL
                BackendFailure.Kind.CANCELLED -> BackendDiagnosticFailure.CANCELLED
                else -> BackendDiagnosticFailure.INITIALIZATION
            }
        }
        if (causes.any { it is IphoneUsbException.Protocol }) return BackendDiagnosticFailure.PROTOCOL
        if (causes.any { it is IphoneUsbException }) return BackendDiagnosticFailure.USB
        if (causes.any { it is LinkageError }) return BackendDiagnosticFailure.NATIVE_UNAVAILABLE
        if (causes.any { it.javaClass.simpleName.contains("Mfi", ignoreCase = true) }) return BackendDiagnosticFailure.MFI
        return if (error == null) BackendDiagnosticFailure.UNKNOWN else BackendDiagnosticFailure.INITIALIZATION
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        val wiredEpoch = if (config.transport == CarPlayTransport.WIRED) wiredGeneration.get() else null
        mainHandler.post {
            if (!closed && (wiredEpoch == null || wiredEpoch == wiredGeneration.get()) && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.WaitingForWiredNetwork -> "STEP usb/network-wait: waiting for iPhone USB network"
        CarPlayStatus.EstablishingWiredSession -> "STEP usb/session: establishing CarPlay session"
        CarPlayStatus.TryingUserspace -> "STEP network/fallback: VPN unavailable; trying userspace"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        // BluetoothProfile.A2DP_SINK and HEADSET_CLIENT, hidden from the public SDK.
        private const val PROFILE_A2DP_SINK = 11
        private const val PROFILE_HEADSET_CLIENT = 16
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val NETWORK_SERVICE_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val WIRELESS_PREFS = "diplay_wireless"
        private const val HOTSPOT_FAMILY_PREFERENCE_KEY = "hotspot_address_family"
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        /** An iPhone answers the mode request within about a second; one took six (#335). */
        const val REENUMERATION_WAIT_MILLIS = 10_000L
        private const val START_SESSION_PROGRESS = "iap2 tx=0x4301"
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

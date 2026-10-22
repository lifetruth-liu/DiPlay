package com.shilapi.xcertplay.network.backend.lwip

/** Raw prototype ABI. Tokens are generations, never native pointers. No native Java callbacks. */
internal object LwipNativeBindings {
    private val loaded by lazy {
        System.loadLibrary("diplay_lwip")
        if (abiVersion() != 2) throw UnsatisfiedLinkError("Unsupported lwIP context/UDP ABI")
    }
    fun ensureLoaded() { loaded }
    external fun abiVersion(): Int
    external fun create(mac: ByteArray, ip: ByteArray, mtu: Int, now: Long, entropy: ByteArray): Long
    external fun supply(handle: Long, now: Long, entropy: ByteArray?): Int
    external fun prepareEcho(handle: Long): Int
    external fun setReady(handle: Long, ready: Boolean): Int
    external fun input(handle: Long, frame: ByteArray): Int
    external fun poll(handle: Long): Int
    external fun nextTimeout(handle: Long): Long
    external fun takeOutput(handle: Long, target: ByteArray): Int
    external fun stats(handle: Long): LongArray
    external fun entropyRemaining(handle: Long): Int
    external fun destroy(handle: Long): Int
    /** Bind result is [endpoint token, actual port]; receive metadata is [source port, original, copied]. */
    external fun udpBind(context: Long, port: Int, result: LongArray): Int
    external fun udpSend(context: Long, endpoint: Long, destination: ByteArray, port: Int, payload: ByteArray, offset: Int, length: Int): Int
    external fun udpReceive(context: Long, endpoint: Long, target: ByteArray, source: ByteArray, metadata: IntArray): Int
    external fun udpClose(context: Long, endpoint: Long): Int
}

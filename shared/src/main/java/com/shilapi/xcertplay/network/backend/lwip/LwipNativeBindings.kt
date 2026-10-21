package com.shilapi.xcertplay.network.backend.lwip

/** Raw prototype ABI. Tokens are generations, never native pointers. No native Java callbacks. */
internal object LwipNativeBindings {
    private val loaded by lazy {
        System.loadLibrary("diplay_lwip")
        if (abiVersion() != 1) throw UnsatisfiedLinkError("Unsupported lwIP prototype ABI")
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
}

package com.shilapi.xcertplay.airplay

import java.io.Closeable
import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.network.backend.system.EndpointOwner
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    network: SessionNetwork? = null,
    private val gracefulClose: () -> Boolean = { false },
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val transport = EndpointOwner(network, "airplay-iap", bindAddress)
    private val sessionNetwork get() = transport.network
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val readCounter = AtomicLong(0)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<TcpListener>()
    private var socket: TcpStream? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localEndpoint}")
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        runCatching {
            transport.listen(sessionNetwork.endpoint(secondaryAddress, bound.localEndpoint.port), reuseAddress = true)
        }.onSuccess { secondary ->
            servers += secondary
            listener.onDebug("AirPlay iAP tunnel secondary listener bound=${secondary.localEndpoint}")
        }.onFailure { error ->
            listener.onDebug("AirPlay iAP tunnel secondary listener failed family=${secondaryAddress.address.size} port=${bound.localEndpoint.port}: ${error.message}")
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localEndpoint.port
    }

    private fun bindAny(): TcpListener = transport.listen(sessionNetwork.endpoint(bindAddress, 0), reuseAddress = true)

    override fun close() {
        val oldSocket = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            socket.also { current ->
                if (gracefulClose() && current != null) {
                    runCatching { current.configure(TcpOptions(resetOnClose = false)) }
                }
                socket = null
            }
        }
        safeClose(oldSocket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
        transport.close()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** No accepted socket may enable reset after close has captured the graceful mode. */
    internal fun publishSocket(accepted: TcpStream): Boolean = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized false
        accepted.configure(TcpOptions(resetOnClose = true))
        socket = accepted
        true
    }
    private fun accept(bound: TcpListener) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localEndpoint}",
        )
        while (!closed.get()) {
            val accepted = try {
                transport.accept(bound)
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            if (!publishSocket(accepted)) { accepted.close(); return }
            if (closed.get()) { accepted.close(); return }
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteEndpoint.toString())
            run(accepted)
        }
    }

    private fun run(sock: TcpStream) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.inputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext += buffer.copyOf(read)
                val decrypted = decryptFrames(ciphertext)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                plaintext = parsePackages(plaintext)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            synchronized(lifecycleLock) {
                if (socket === sock) {
                    if (closed.get() && gracefulClose()) runCatching { sock.configure(TcpOptions(resetOnClose = false)) }
                    socket = null
                }
            }
            runCatching { transport.release(sock) }
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - offset < frameLength) break
            val aad = buffer.copyOfRange(offset, offset + FRAME_HEADER_LEN)
            val sealed = buffer.copyOfRange(offset + FRAME_HEADER_LEN, offset + frameLength)
            val plain = AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(readCounter.get()), sealed, aad,
            )
            readCounter.incrementAndGet()
            output.add(plain)
            offset += frameLength
        }
        return concatBytes(*output.toTypedArray()) to buffer.copyOfRange(offset, buffer.size)
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        var offset = 0
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            if (size < PACKAGE_HEADER_LEN || size > MAX_PACKAGE) break
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                listener.onDebug(
                    "AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}",
                )
                listener.onIap(buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size))
            }
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}

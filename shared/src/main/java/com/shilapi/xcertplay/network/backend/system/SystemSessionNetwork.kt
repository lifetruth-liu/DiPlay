package com.shilapi.xcertplay.network.backend.system

import com.shilapi.xcertplay.network.TcpLiveness
import com.shilapi.xcertplay.network.backend.*
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * System network adapter. Scope belongs to this adapter, independently of the protocol link ID.
 * Read/write/connect deadlines use nonblocking channels so closing never waits for socket I/O.
 */
class SystemSessionNetwork(
    sessionLabel: String,
    private val ipv6ScopeId: Int = 0,
    override val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 },
) : SessionNetwork {
    // A reused connection label must never make endpoints from an old generation valid again.
    override val linkId: String = "$sessionLabel/${sessionSequence.incrementAndGet()}"
    private val lock = Any()
    private var closed = false
    private val resources = mutableSetOf<Resource>()

    init {
        require(sessionLabel.isNotBlank())
        require(ipv6ScopeId >= 0)
    }

    private fun socketAddress(endpoint: NetworkEndpoint): InetSocketAddress {
        require(endpoint.linkId == linkId) { "Endpoint belongs to another session" }
        val bytes = endpoint.address
        val address = if (bytes.size == 16) Inet6Address.getByAddress(null, bytes, ipv6ScopeId)
            else InetAddress.getByAddress(bytes)
        return InetSocketAddress(address, endpoint.port)
    }

    private fun endpoint(address: SocketAddress): NetworkEndpoint {
        val inet = address as InetSocketAddress
        return NetworkEndpoint(inet.address.address, inet.port, linkId)
    }

    private fun <T : Resource> own(resource: T): T = synchronized(lock) {
        if (closed) {
            resource.close()
            throw BackendFailure(BackendFailure.Kind.CANCELLED)
        }
        resources.add(resource)
        resource
    }

    override fun listen(local: NetworkEndpoint, backlog: Int, reuseAddress: Boolean): TcpListener {
        require(backlog > 0)
        val address = socketAddress(local)
        val listener = own(Listener(ServerSocketChannel.open()))
        return try {
            listener.io {
                listener.channel.socket().reuseAddress = reuseAddress
                listener.channel.socket().bind(address, backlog)
                listener.bound = endpoint(listener.channel.socket().localSocketAddress)
            }
            listener
        } catch (error: Exception) { listener.close(); throw error }
    }

    override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream {
        val address = socketAddress(remote)
        require(remote.port != 0)
        Deadline.remainingMillis(deadlineMillis, clock)
        val stream = own(Stream(SocketChannel.open()))
        return try {
            stream.io {
                if (!stream.channel.connect(address)) {
                    stream.waitFor(SelectionKey.OP_CONNECT, deadlineMillis) { stream.channel.finishConnect() }
                }
                stream.initialize()
            }
            stream
        } catch (error: Exception) { stream.close(); throw error }
    }

    /** Explicit compatibility boundary for an existing system socket, never a backend fallback. */
    fun adopt(socket: Socket): TcpStream = own(AdoptedStream(socket))

    override fun bindUdp(local: NetworkEndpoint): UdpEndpoint {
        val address = socketAddress(local)
        val udp = own(Datagram(DatagramSocket(null)))
        return try {
            udp.io {
                udp.socket.bind(address)
                udp.bound = endpoint(udp.socket.localSocketAddress)
            }
            udp
        } catch (error: Exception) { udp.close(); throw error }
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        closeAll(snapshot)
    }

    private abstract inner class Resource(val transport: java.io.Closeable) : java.io.Closeable {
        val stopped = AtomicBoolean(false)
        private val waitLock = Any()
        private val waiters = mutableSetOf<Selector>()

        init { (transport as? SelectableChannel)?.configureBlocking(false) }

        fun checkOpen() {
            if (stopped.get()) throw BackendFailure(BackendFailure.Kind.CANCELLED)
        }

        fun <T> io(block: () -> T): T {
            checkOpen()
            return try { block() } catch (error: IOException) {
                if (error is BackendFailure) throw error
                val kind = when {
                    stopped.get() -> BackendFailure.Kind.CANCELLED
                    error is BindException -> BackendFailure.Kind.ADDRESS_CONFLICT
                    isPeerReset(error) -> BackendFailure.Kind.PEER_RESET
                    else -> BackendFailure.Kind.INTERNAL
                }
                throw BackendFailure(kind, error)
            } catch (error: CancelledKeyException) {
                throw BackendFailure(if (stopped.get()) BackendFailure.Kind.CANCELLED else BackendFailure.Kind.INTERNAL, error)
            } catch (error: ClosedSelectorException) {
                throw BackendFailure(if (stopped.get()) BackendFailure.Kind.CANCELLED else BackendFailure.Kind.INTERNAL, error)
            }
        }

        /** Predicate returns true when complete. No polling while idle; close wakes selectors. */
        fun waitFor(operation: Int, deadline: Long, ready: () -> Boolean) {
            Selector.open().use { selector ->
                synchronized(waitLock) {
                    checkOpen()
                    waiters.add(selector)
                }
                try {
                    (transport as SelectableChannel).register(selector, operation)
                    while (true) {
                        checkOpen()
                        val remaining = Deadline.remainingMillis(deadline, clock)
                        if (Thread.currentThread().isInterrupted) throw BackendFailure(BackendFailure.Kind.CANCELLED)
                        if (ready()) return
                        if (remaining == 0L) selector.select() else selector.select(remaining)
                        selector.selectedKeys().clear()
                    }
                } finally {
                    // Keep it registered with the owner until synchronous deregistration ends.
                    synchronized(waitLock) {
                        try { selector.close() } finally { waiters.remove(selector) }
                    }
                }
            }
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            // Closing a registered NIO channel can defer its FD release until deregistration.
            // Close the waiters synchronously so detach returns with the real port released,
            // independently of when cancelled reader/accept threads are scheduled again.
            try {
                synchronized(waitLock) {
                    val pending = waiters.toList()
                    pending.forEach { it.wakeup() }
                    closeAll(listOf(transport) + pending)
                }
            }
            finally { synchronized(lock) { resources.remove(this) } }
        }
    }

    private fun isPeerReset(error: IOException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.take(16).any { cause ->
            // Android libcore keeps errno in the cause; JVM sockets expose canonical text.
            (cause is android.system.ErrnoException && cause.errno == android.system.OsConstants.ECONNRESET) ||
                // Android NIO may discard errno and throw this exact plain IOException.
                (cause is IOException && cause.message.equals("Connection reset by peer", ignoreCase = true)) ||
                (cause is SocketException && cause.message.orEmpty().let {
                    it.contains("ECONNRESET", ignoreCase = true) || it.contains("Connection reset", ignoreCase = true)
                })
        }

    private inner class Listener(val channel: ServerSocketChannel) : Resource(channel), TcpListener {
        lateinit var bound: NetworkEndpoint
        override val localEndpoint: NetworkEndpoint get() = bound
        private val acceptOperation = EndpointOperation(clock)

        override fun accept(deadlineMillis: Long): TcpStream = acceptOperation.run(deadlineMillis) {
            io {
                var accepted: SocketChannel? = null
                waitFor(SelectionKey.OP_ACCEPT, deadlineMillis) {
                    channel.accept().also { accepted = it } != null
                }
                val socket = checkNotNull(accepted)
                try {
                    val stream = own(Stream(socket))
                    try {
                        stream.initialize()
                        // A close racing with accept must not publish a new live connection.
                        checkOpen()
                        stream
                    } catch (error: Exception) { stream.close(); throw error }
                } catch (error: Exception) { socket.close(); throw error }
            }
        }
    }

    private inner class Stream(val channel: SocketChannel) : Resource(channel), TcpStream {
        override lateinit var localEndpoint: NetworkEndpoint
            private set
        override lateinit var remoteEndpoint: NetworkEndpoint
            private set
        private val readOperation = EndpointOperation(clock)
        private val writeOperation = EndpointOperation(clock)

        fun initialize() {
            channel.socket().tcpNoDelay = true
            channel.socket().keepAlive = true
            localEndpoint = endpoint(channel.socket().localSocketAddress)
            remoteEndpoint = endpoint(channel.socket().remoteSocketAddress)
        }

        override fun configure(options: TcpOptions) = io {
            channel.socket().setSoLinger(options.resetOnClose, 0)
        }

        override fun configureLiveness(diagnostic: (String) -> Unit) = io {
            TcpLiveness.configure(channel.socket(), diagnostic)
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int = readOperation.run(deadlineMillis) {
            range(bytes, offset, length)
            io {
                Deadline.remainingMillis(deadlineMillis, clock)
                if (length == 0) return@io 0
                val buffer = ByteBuffer.wrap(bytes, offset, length)
                var count = 0
                waitFor(SelectionKey.OP_READ, deadlineMillis) { channel.read(buffer).also { count = it } != 0 }
                count
            }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = writeOperation.run(deadlineMillis) {
            range(bytes, offset, length)
            io {
                val buffer = ByteBuffer.wrap(bytes, offset, length)
                waitFor(SelectionKey.OP_WRITE, deadlineMillis) {
                    channel.write(buffer)
                    !buffer.hasRemaining()
                }
            }
        }

        override fun shutdownOutput() = writeOperation.run(0) { io { channel.socket().shutdownOutput() } }
    }

    private inner class AdoptedStream(val socket: Socket) : Resource(socket), TcpStream {
        override val localEndpoint get() = NetworkEndpoint(socket.localAddress?.address ?: ByteArray(4), socket.localPort.coerceAtLeast(0), linkId)
        override val remoteEndpoint get() = NetworkEndpoint(socket.inetAddress?.address ?: ByteArray(4), socket.port.coerceAtLeast(0), linkId)
        private val readOperation = EndpointOperation(clock)
        private val writeOperation = EndpointOperation(clock)
        override fun configure(options: TcpOptions) = io {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.setSoLinger(options.resetOnClose, 0)
        }
        override fun configureLiveness(diagnostic: (String) -> Unit) = io { TcpLiveness.configure(socket, diagnostic) }
        override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int = readOperation.run(deadlineMillis) {
            range(bytes, offset, length)
            io {
                val remaining = Deadline.remainingMillis(deadlineMillis, clock)
                if (length == 0) return@io 0
                socket.soTimeout = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                try { socket.getInputStream().read(bytes, offset, length) }
                catch (error: SocketTimeoutException) { throw BackendFailure(BackendFailure.Kind.TIMEOUT, error) }
            }
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = writeOperation.run(deadlineMillis) {
            range(bytes, offset, length)
            io {
                val remaining = Deadline.remainingMillis(deadlineMillis, clock)
                val state = AtomicInteger(0)
                val timeout = if (remaining == 0L) null else sendDeadlines.schedule({
                    if (state.compareAndSet(0, 2)) close()
                }, remaining, TimeUnit.MILLISECONDS)
                try {
                    socket.getOutputStream().write(bytes, offset, length)
                    if (!state.compareAndSet(0, 1)) throw BackendFailure(BackendFailure.Kind.TIMEOUT)
                } catch (error: IOException) {
                    if (state.get() == 2) throw BackendFailure(BackendFailure.Kind.TIMEOUT, error)
                    throw error
                } finally { state.compareAndSet(0, 1); timeout?.cancel(false) }
            }
        }
        override fun shutdownOutput() = writeOperation.run(0) { io { socket.shutdownOutput() } }
    }

    private inner class Datagram(val socket: DatagramSocket) : Resource(socket), UdpEndpoint {
        lateinit var bound: NetworkEndpoint
        override val localEndpoint: NetworkEndpoint get() = bound
        private val receiveOperation = EndpointOperation(clock)
        private val sendOperation = EndpointOperation(clock)
        // Maximum IP datagram, reused per endpoint so truncation always reports the wire length.
        private val receiveBuffer = ByteArray(65535)

        override fun receiveBufferSize(requestedBytes: Int): Int = io {
            require(requestedBytes >= 0)
            if (requestedBytes > 0) socket.receiveBufferSize = requestedBytes
            socket.receiveBufferSize
        }

        override fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long) = sendOperation.run(deadlineMillis) {
            require(bytes.size <= 65527) { "UDP payload exceeds IPv6 maximum" }
            require(destination.port != 0)
            val address = socketAddress(destination)
            io {
                val remaining = Deadline.remainingMillis(deadlineMillis, clock)
                // Blocking send distinguishes successful empty packets from EAGAIN. A deadline
                // closes the endpoint to cancel a blocked OS send; that endpoint cannot be reused.
                val state = AtomicInteger(0) // pending / completed / timed out
                val timeout = if (remaining == 0L) null else sendDeadlines.schedule({
                    if (state.compareAndSet(0, 2)) close()
                }, remaining, TimeUnit.MILLISECONDS)
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, address))
                    if (!state.compareAndSet(0, 1)) throw BackendFailure(BackendFailure.Kind.TIMEOUT)
                } catch (error: IOException) {
                    if (state.get() == 2) throw BackendFailure(BackendFailure.Kind.TIMEOUT, error)
                    throw error
                } finally {
                    state.compareAndSet(0, 1)
                    timeout?.cancel(false)
                }
            }
        }

        override fun receive(bytes: ByteArray, deadlineMillis: Long): ReceivedDatagram = receiveOperation.run(deadlineMillis) {
            io {
                val remaining = Deadline.remainingMillis(deadlineMillis, clock)
                socket.soTimeout = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(packet)
                } catch (error: SocketTimeoutException) {
                    throw BackendFailure(BackendFailure.Kind.TIMEOUT, error)
                }
                val original = packet.length
                val copied = minOf(original, bytes.size)
                receiveBuffer.copyInto(bytes, endIndex = copied)
                ReceivedDatagram(endpoint(packet.socketAddress), original, copied)
            }
        }
    }

    private fun closeAll(endpoints: Iterable<java.io.Closeable>) {
        var failure: Throwable? = null
        for (endpoint in endpoints) {
            try { endpoint.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun range(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid buffer range" }
    }

    private companion object {
        val sessionSequence = AtomicLong()
        val sendDeadlines = ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "system-udp-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** One outstanding operation per direction; no monitor waits outside the deadline. */
internal class EndpointOperation(private val clock: MonotonicClock) {
    private val lock = ReentrantLock()
    fun <T> run(deadline: Long, operation: () -> T): T {
        Deadline.remainingMillis(deadline, clock)
        if (!lock.tryLock()) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
        return try { operation() } finally { lock.unlock() }
    }
}

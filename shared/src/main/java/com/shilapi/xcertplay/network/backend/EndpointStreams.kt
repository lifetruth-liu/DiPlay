package com.shilapi.xcertplay.network.backend

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/** Stream framing adapters; all I/O and ownership stay with the protocol endpoint. */
fun TcpStream.inputStream(): InputStream = object : InputStream() {
    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte) < 0) -1 else byte[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = this@inputStream.read(bytes, offset, length)
    override fun close() = this@inputStream.close()
}

fun TcpStream.outputStream(): OutputStream = object : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
    override fun write(bytes: ByteArray, offset: Int, length: Int) = this@outputStream.write(bytes, offset, length)
    override fun close() = this@outputStream.close()
}

fun SessionNetwork.endpoint(address: InetAddress, port: Int) = NetworkEndpoint(address.address, port, linkId)
fun SessionNetwork.wildcard(port: Int = 0, ipv6: Boolean = true) = NetworkEndpoint(ByteArray(if (ipv6) 16 else 4), port, linkId)

/** A borrowed network is shared by every channel in the control session. */
internal data class SessionConnection(
    val network: SessionNetwork,
    val stream: TcpStream,
    val ownsNetwork: Boolean = false,
    val localAddress: InetAddress? = InetAddress.getByAddress(stream.localEndpoint.address),
    val remoteAddress: InetAddress? = InetAddress.getByAddress(stream.remoteEndpoint.address),
)

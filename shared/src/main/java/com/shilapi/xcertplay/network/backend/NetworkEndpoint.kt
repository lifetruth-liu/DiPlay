package com.shilapi.xcertplay.network.backend

/** Protocol address. [linkId] is a session identity, never a kernel IPv6 scope index. */
class NetworkEndpoint(address: ByteArray, val port: Int, val linkId: String) {
    private val bytes = address.copyOf()
    val address: ByteArray get() = bytes.copyOf()

    init {
        require(bytes.size == 4 || bytes.size == 16) { "Expected IPv4 or IPv6 bytes" }
        require(port in 0..65535) { "Invalid port" }
        require(linkId.isNotBlank()) { "Missing session link identity" }
    }

    override fun equals(other: Any?): Boolean = other is NetworkEndpoint &&
        port == other.port && linkId == other.linkId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + port) + linkId.hashCode()
    override fun toString(): String = "${if (bytes.size == 16) "IPv6" else "IPv4"}:$port"
}

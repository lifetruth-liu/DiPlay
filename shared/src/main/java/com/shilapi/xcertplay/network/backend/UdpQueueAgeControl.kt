package com.shilapi.xcertplay.network.backend

/** Optional UDP capability. Callers must check support before enabling a media queue budget.
 * Generic control endpoints keep expiration disabled unless explicitly configured.
 */
interface UdpQueueAgeControl {
    /** Maximum residence time in milliseconds, 0..4294967295; zero disables expiration.
     * Changes apply to queued packets too. Datagrams at the limit are discarded.
     */
    fun setReceiveMaxAge(maxAgeMillis: Long)
    /** Cumulative expired datagrams for this endpoint, including zero-length datagrams. */
    fun expiredDatagrams(): Long
}
